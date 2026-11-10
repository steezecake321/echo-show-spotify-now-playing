package com.adam.nowplaying;

import android.animation.ObjectAnimator;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.text.format.DateFormat;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Screensaver that plays the pictures of a shared Google Photos album, with the clock and what is playing.
 * Swipe left/right for the next/previous picture, tap for controls, double-tap to open Now Playing.
 */
public class PhotoDream extends DreamService implements PlayerState.Listener {

    private static final long SHOW_MS = 12_000;
    private static final long FADE_MS = 1_400;
    private static final long CONTROLS_MS = 6_000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private ImageView front, back;
    private TextView clock, now, message, pausePill;
    private View controls, musicRow;
    private ImageView playPause;
    private List<String> urls;
    private final List<String> history = new ArrayList<>();
    private int histPos = -1;
    private int index;
    private Bitmap ready;          // downloaded and waiting for its turn
    private String readyUrl;
    private boolean fetching;
    private boolean paused;
    private volatile boolean alive;
    private int w, h;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(true);
        setFullscreen(true);
        setScreenBright(true);
        alive = true;
        PlayerService.dreaming = true;
        PlayerService.start(this);

        w = getResources().getDisplayMetrics().widthPixels;
        h = getResources().getDisplayMetrics().heightPixels;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        back = new ImageView(this);
        front = new ImageView(this);
        for (ImageView v : new ImageView[]{back, front}) {
            v.setScaleType(ImageView.ScaleType.MATRIX);
            v.setAlpha(0f);
            root.addView(v, new FrameLayout.LayoutParams(-1, -1));
        }

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(Ui.dp(this, 22), Ui.dp(this, 10), Ui.dp(this, 22), Ui.dp(this, 16));
        info.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{Color.argb(170, 0, 0, 0), Color.TRANSPARENT}));
        clock = Ui.text(this, 34, Color.WHITE, "sans-serif-light", 1);
        clock.setShadowLayer(8, 0, 2, Color.BLACK);
        now = Ui.text(this, 15, Color.argb(220, 255, 255, 255), "sans-serif", 1);
        now.setShadowLayer(6, 0, 1, Color.BLACK);
        info.addView(clock);
        info.addView(now);
        root.addView(info, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        message = Ui.text(this, 16, Color.argb(200, 255, 255, 255), "sans-serif", 3);
        message.setGravity(Gravity.CENTER);
        root.addView(message, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        buildControls(root);

        final GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                toggleControls();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                openApp();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null || b == null) return false;
                float dx = b.getX() - a.getX(), dy = b.getY() - a.getY();
                if (Math.abs(dx) < Ui.dp(PhotoDream.this, 60) || Math.abs(dx) < Math.abs(dy) * 1.5f) return false;
                if (dx < 0) step(1);
                else step(-1);
                return true;
            }
        });
        root.setOnTouchListener((v, e) -> gestures.onTouchEvent(e));
        setContentView(root);

        PlayerState.get().addListener(this);
        onStateChanged();
        tickClock.run();

        if (PhotoAlbum.albumUrl(this) == null) {
            message.setText("No photo album set.\nSee the app's README to add a shared album link.");
            return;
        }
        message.setText("Loading photos…");
        PhotoAlbum.load(this, false, (list, err) -> {
            if (!alive) return;
            if (list.isEmpty()) {
                message.setText(err);
                return;
            }
            urls = new ArrayList<>(list);
            Collections.shuffle(urls);
            message.setVisibility(View.GONE);
            prefetch();
            ui.postDelayed(advanceRun, 600);
        });
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        alive = false;
        ui.removeCallbacksAndMessages(null);
        PlayerState.get().removeListener(this);
        PlayerService.dreaming = false;
        io.shutdownNow();
        front.setImageDrawable(null);
        back.setImageDrawable(null);
        ready = null;
    }

    // ---------- controls ----------

    private void buildControls(FrameLayout root) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setGravity(Gravity.CENTER_HORIZONTAL);
        bar.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 26));
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(200, 0, 0, 0), Color.TRANSPARENT}));

        LinearLayout photos = new LinearLayout(this);
        photos.setGravity(Gravity.CENTER);
        photos.addView(pill("‹ Previous", () -> step(-1)));
        pausePill = pill("Pause photos", this::togglePause);
        photos.addView(pausePill);
        photos.addView(pill("Next ›", () -> step(1)));
        photos.addView(pill("Now Playing", this::openApp));
        photos.addView(pill("Exit", this::wakeUp));
        bar.addView(photos);

        LinearLayout music = new LinearLayout(this);
        music.setGravity(Gravity.CENTER);
        music.setPadding(0, Ui.dp(this, 10), 0, 0);
        music.addView(icon(R.drawable.ic_prev, () -> PlayerService.command(this, "skip_prev")));
        playPause = icon(R.drawable.ic_pause, () -> PlayerService.togglePlay(this));
        music.addView(playPause);
        music.addView(icon(R.drawable.ic_next, () -> PlayerService.command(this, "skip_next")));
        bar.addView(music);
        musicRow = music;

        bar.setVisibility(View.GONE);
        bar.setClickable(true);
        root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        controls = bar;
    }

    private TextView pill(String label, Runnable tap) {
        TextView t = Ui.pill(this, label, false);
        t.setBackground(Ui.round(this, Color.argb(120, 40, 40, 44), 20));
        t.setOnClickListener(v -> {
            tap.run();
            showControls();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = Ui.dp(this, 4);
        lp.rightMargin = Ui.dp(this, 4);
        t.setLayoutParams(lp);
        return t;
    }

    private ImageView icon(int res, Runnable tap) {
        ImageView v = new ImageView(this);
        v.setImageResource(res);
        v.setColorFilter(Color.WHITE);
        int pad = Ui.dp(this, 8);
        v.setPadding(pad, pad, pad, pad);
        v.setBackground(Ui.round(this, Color.argb(120, 40, 40, 44), 24));
        v.setOnClickListener(x -> {
            tap.run();
            showControls();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48));
        lp.leftMargin = Ui.dp(this, 8);
        lp.rightMargin = Ui.dp(this, 8);
        v.setLayoutParams(lp);
        return v;
    }

    private final Runnable hideControls = () -> controls.setVisibility(View.GONE);

    private void toggleControls() {
        if (controls.getVisibility() == View.VISIBLE) {
            ui.removeCallbacks(hideControls);
            controls.setVisibility(View.GONE);
        } else {
            showControls();
        }
    }

    private void showControls() {
        onStateChanged();
        controls.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideControls);
        ui.postDelayed(hideControls, CONTROLS_MS);
    }

    private void openApp() {
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        wakeUp();
    }

    private void togglePause() {
        paused = !paused;
        pausePill.setText(paused ? "Resume photos" : "Pause photos");
        ui.removeCallbacks(advanceRun);
        if (!paused) ui.postDelayed(advanceRun, SHOW_MS);
    }

    // ---------- state ----------

    private final Runnable tickClock = new Runnable() {
        @Override
        public void run() {
            if (!alive) return;
            clock.setText(DateFormat.format(DateFormat.is24HourFormat(PhotoDream.this) ? "H:mm" : "h:mm", new Date()));
            ui.postDelayed(this, 10_000);
        }
    };

    @Override
    public void onStateChanged() {
        PlayerState s = PlayerState.get();
        boolean music = s.hasTrack();
        if (music && s.isPlaying()) {
            now.setText(s.trackName + "  ·  " + s.artists);
            now.setVisibility(View.VISIBLE);
        } else {
            now.setVisibility(View.GONE);
        }
        musicRow.setVisibility(music ? View.VISIBLE : View.GONE);
        playPause.setImageResource(s.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    // ---------- slideshow ----------

    /** Downloads the next picture in the background so the change is instant. */
    private void prefetch() {
        if (fetching || ready != null || urls == null || !alive) return;
        fetching = true;
        final String base = urls.get(index % urls.size());
        index++;
        io.execute(() -> {
            final Bitmap got = download(base);
            ui.post(() -> {
                fetching = false;
                if (!alive) return;
                if (got == null) {
                    prefetch();         // skip pictures that fail and try the next one
                    return;
                }
                ready = got;
                readyUrl = base;
            });
        });
    }

    private Bitmap download(String base) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(PhotoAlbum.sized(base, Math.max(w, h) * 5 / 4, 0)).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            InputStream in = c.getInputStream();
            Bitmap b = BitmapFactory.decodeStream(in);
            in.close();
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private final Runnable advanceRun = this::advance;

    /** dir = 1 for the next picture, -1 for the previous one (from the history). */
    private void step(int dir) {
        if (urls == null) return;
        ui.removeCallbacks(advanceRun);
        if (dir < 0 && histPos <= 0) {
            scheduleNext();
        } else if (dir < 0 || histPos < history.size() - 1) {
            final int target = histPos + dir;
            io.execute(() -> {
                final Bitmap b = download(history.get(target));
                ui.post(() -> {
                    if (!alive) return;
                    if (b != null) {
                        histPos = target;
                        display(b);
                    }
                    scheduleNext();
                });
            });
        } else {
            advance();
        }
    }

    private void scheduleNext() {
        if (!paused) ui.postDelayed(advanceRun, SHOW_MS);
    }

    private void advance() {
        if (!alive) return;
        if (ready == null) {
            prefetch();
            ui.postDelayed(advanceRun, 800);
            return;
        }
        Bitmap next = ready;
        history.add(readyUrl);
        histPos = history.size() - 1;
        ready = null;
        display(next);
        prefetch();
        scheduleNext();
    }

    /** Fills the screen like center-crop, but keeps the upper part of tall pictures so heads stay in frame. */
    private Matrix cropMatrix(Bitmap b) {
        // follow the current screen orientation
        w = getResources().getDisplayMetrics().widthPixels;
        h = getResources().getDisplayMetrics().heightPixels;
        float fit = Math.min(w / (float) b.getWidth(), h / (float) b.getHeight());
        float fill = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
        // show the whole picture; only zoom in (at most 8%) when that closes small gaps. Bigger mismatches get bars.
        float scale = Math.min(fill, fit * 1.08f);
        float dx = (w - b.getWidth() * scale) / 2f;
        float dy = (h - b.getHeight() * scale) * 0.35f;   // 0 = top edge, 0.5 = centre
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate(dx, dy);
        return m;
    }

    /** A dimmed, blurred copy of the picture that fills the screen behind it (where the picture leaves bars). */
    private BitmapDrawable blurredBackdrop(Bitmap b) {
        try {
            // centre-crop to the screen's shape, then shrink to a tiny size; stretching it back up gives a cheap blur
            float screen = w / (float) h;
            int cw = b.getWidth(), ch = b.getHeight();
            if (cw / (float) ch > screen) cw = Math.round(ch * screen);
            else ch = Math.round(cw / screen);
            Bitmap crop = Bitmap.createBitmap(b, (b.getWidth() - cw) / 2, (b.getHeight() - ch) / 2, cw, ch);
            int tw = 24;
            Bitmap tiny = Bitmap.createScaledBitmap(crop, tw, Math.max(1, Math.round(tw / screen)), true);
            if (crop != b) crop.recycle();
            BitmapDrawable d = new BitmapDrawable(getResources(), tiny);
            d.setFilterBitmap(true);
            d.setColorFilter(0xFF8A8A8A, android.graphics.PorterDuff.Mode.MULTIPLY);   // dim it
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Crossfades the new picture in over the current one, with a slow zoom. */
    private void display(Bitmap next) {
        // the view that is currently hidden gets the new picture and fades in over the other one
        final ImageView in = back, out = front;
        final Bitmap old = out.getDrawable() instanceof BitmapDrawable ? ((BitmapDrawable) out.getDrawable()).getBitmap() : null;
        in.animate().cancel();
        in.setImageBitmap(next);
        in.setImageMatrix(cropMatrix(next));
        in.setBackground(blurredBackdrop(next));
        in.setScaleX(1f);
        in.setScaleY(1f);
        in.setAlpha(0f);
        in.bringToFront();
        ((View) clock.getParent()).bringToFront();
        message.bringToFront();
        controls.bringToFront();
        in.animate().alpha(1f).setDuration(FADE_MS).setInterpolator(new LinearInterpolator()).withEndAction(() -> {
            out.setAlpha(0f);
            out.setImageDrawable(null);
            if (old != null && old != next) old.recycle();
        }).start();
        ObjectAnimator zx = ObjectAnimator.ofFloat(in, View.SCALE_X, 1f, 1.25f);
        ObjectAnimator zy = ObjectAnimator.ofFloat(in, View.SCALE_Y, 1f, 1.25f);
        for (ObjectAnimator z : new ObjectAnimator[]{zx, zy}) {
            z.setDuration(SHOW_MS + FADE_MS);
            z.setInterpolator(new LinearInterpolator());
            z.start();
        }
        front = in;
        back = out;
    }
}
