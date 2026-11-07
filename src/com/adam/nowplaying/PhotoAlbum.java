package com.adam.nowplaying;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picture list of a Google Photos album that was shared with a link. Google closed the official API to
 * existing albums in 2025, so this reads the public share page, which lists every picture's address.
 * It needs no sign-in, but it is unofficial and could stop working if Google changes that page.
 */
final class PhotoAlbum {

    private static final long REFRESH_MS = 24L * 60 * 60 * 1000;
    private static final Pattern ITEM = Pattern.compile("\\[\"(https://lh3\\.googleusercontent\\.com/pw/[^\"]+)\",(\\d+),(\\d+)");
    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private PhotoAlbum() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("photos", Context.MODE_PRIVATE);
    }

    static String albumUrl(Context c) {
        return prefs(c).getString("album_url", null);
    }

    static void setAlbumUrl(Context c, String url) {
        prefs(c).edit().putString("album_url", url.trim()).remove("items").putLong("fetched", 0).apply();
    }

    /** Calls back with the picture addresses (cached for a day); the error is set when the list is empty. */
    static void load(Context c, boolean forceRefresh, SpotifyWebApi.Callback<List<String>> cb) {
        final Context app = c.getApplicationContext();
        io.execute(() -> {
            SharedPreferences p = prefs(app);
            List<String> cached = split(p.getString("items", ""));
            boolean fresh = !forceRefresh && !cached.isEmpty()
                    && System.currentTimeMillis() - p.getLong("fetched", 0) < REFRESH_MS;
            List<String> result = cached;
            String error = null;
            if (!fresh) {
                String url = albumUrl(app);
                if (url == null) {
                    error = "No album link set.";
                } else {
                    try {
                        List<String> got = fetch(url);
                        if (got.isEmpty()) {
                            error = "Couldn't find pictures in that album link.";
                        } else {
                            result = got;
                            p.edit().putString("items", join(got)).putLong("fetched", System.currentTimeMillis()).apply();
                        }
                    } catch (Exception e) {
                        error = "Couldn't reach Google Photos (" + e.getClass().getSimpleName() + ")";
                    }
                }
            }
            final List<String> out = result;
            final String err = out.isEmpty() ? (error == null ? "Album is empty." : error) : null;
            main.post(() -> cb.done(out, err));
        });
    }

    private static List<String> fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36");
        InputStream in = c.getInputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        String html = b.toString("UTF-8");
        Set<String> urls = new LinkedHashSet<>();
        Matcher m = ITEM.matcher(html);
        while (m.find()) urls.add(m.group(1));
        return new ArrayList<>(urls);
    }

    /** Address of a picture sized to the screen (Google resizes on its side, so downloads stay small). */
    static String sized(String base, int w, int h) {
        // width-limited only: a "w-h" box would shrink tall pictures to fit inside it, and they'd then be blown up to fill the screen
        return h > 0 ? base + "=w" + w + "-h" + h : base + "=w" + w;
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(s).append('\n');
        return sb.toString();
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        for (String line : s.split("\n")) if (!line.isEmpty()) out.add(line);
        return out;
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
