package com.harroldino.mytv;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * Keeps the playlist link and a copy of the downloaded playlist on the stick,
 * so the app opens straight into TV even before the network refresh finishes.
 */
public final class Playlist {

    private static final String PREFS = "mytv";
    private static final String KEY_URL = "playlist_url";
    private static final String KEY_LAST = "last_channel";
    private static final String CACHE = "playlist.m3u";

    private final Context context;
    private final SharedPreferences prefs;

    public Playlist(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String url() { return prefs.getString(KEY_URL, null); }

    public void setUrl(String url) { prefs.edit().putString(KEY_URL, url).apply(); }

    public String lastChannelUrl() { return prefs.getString(KEY_LAST, null); }

    public void setLastChannelUrl(String url) { prefs.edit().putString(KEY_LAST, url).apply(); }

    /** The playlist saved from the last successful download, or an empty list. */
    public List<Channel> cached() {
        File f = new File(context.getFilesDir(), CACHE);
        if (!f.exists()) return Collections.emptyList();
        try (InputStream in = new FileInputStream(f)) {
            return new M3uParser().parse(new String(readAll(in), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    /** Downloads and saves the playlist. Call off the main thread. */
    public List<Channel> download() throws IOException {
        String link = url();
        if (link == null) throw new IOException("No playlist link set");
        HttpURLConnection c = (HttpURLConnection) new URL(link).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        // Some providers refuse requests that don't look like a media player.
        c.setRequestProperty("User-Agent", "MyTV/1.0 (Linux; Android) ExoPlayer");
        try {
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new IOException("The playlist link returned HTTP " + code);
            byte[] body;
            try (InputStream in = c.getInputStream()) { body = readAll(in); }
            String text = new String(body, StandardCharsets.UTF_8);
            List<Channel> channels = new M3uParser().parse(text);
            if (channels.isEmpty()) throw new IOException("That link isn't an M3U playlist, or it has no channels");
            File tmp = new File(context.getFilesDir(), CACHE + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) { out.write(body); }
            if (!tmp.renameTo(new File(context.getFilesDir(), CACHE))) throw new IOException("Couldn't save the playlist");
            return channels;
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
