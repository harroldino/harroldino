package com.harroldino.home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything the home screen page can ask the Fire Stick to do. Exposed to JavaScript as window.Android. */
public class Bridge {

    private static final String AMAZON_HOME = "com.amazon.tv.launcher";

    private final Activity act;
    private final WebView web;
    private final Map<String, String> iconCache = new HashMap<>();

    Bridge(Activity act, WebView web) {
        this.act = act;
        this.web = web;
    }

    /** Installed apps that can be opened, as a JSON array of {pkg, label}. */
    @JavascriptInterface
    public String getApps() {
        PackageManager pm = act.getPackageManager();
        LinkedHashMap<String, String> apps = new LinkedHashMap<>();
        String[] cats = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String cat : cats) {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(cat);
            List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
            for (ResolveInfo ri : list) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(act.getPackageName()) || apps.containsKey(pkg)) continue;
                CharSequence label = ri.loadLabel(pm);
                apps.put(pkg, label == null ? pkg : label.toString());
            }
        }
        JSONArray arr = new JSONArray();
        try {
            for (Map.Entry<String, String> e : apps.entrySet()) {
                JSONObject o = new JSONObject();
                o.put("pkg", e.getKey());
                o.put("label", e.getValue());
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        return arr.toString();
    }

    /** An app's icon as a PNG data URL, or "" if it can't be read. */
    @JavascriptInterface
    public String getIcon(String pkg) {
        synchronized (iconCache) {
            String cached = iconCache.get(pkg);
            if (cached != null) return cached;
        }
        String out = "";
        try {
            Drawable d = act.getPackageManager().getApplicationIcon(pkg);
            int size = 160;
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, size, size);
            d.draw(c);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
            out = "data:image/png;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception ignored) {
        }
        synchronized (iconCache) {
            iconCache.put(pkg, out);
        }
        return out;
    }

    /** Opens an app. Returns false if it isn't installed or can't be opened. */
    @JavascriptInterface
    public boolean launch(String pkg) {
        PackageManager pm = act.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return start(i);
    }

    /** Searches the Amazon Appstore (falls back to any app store) for an app that isn't installed. */
    @JavascriptInterface
    public void openStore(String query) {
        String q;
        try {
            q = URLEncoder.encode(query, "UTF-8");
        } catch (Exception e) {
            q = query;
        }
        if (!start(new Intent(Intent.ACTION_VIEW, Uri.parse("amzn://apps/android?s=" + q)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
            start(new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + q)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    @JavascriptInterface
    public void openAccessibility() {
        start(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @JavascriptInterface
    public void openSettings() {
        start(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** Shows Amazon's own home screen and stops the Home button redirect for a few minutes. */
    @JavascriptInterface
    public void openAmazonHome(int minutes) {
        HomeButtonService.pauseFor(minutes * 60_000L);
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_HOME);
        i.setPackage(AMAZON_HOME);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        start(i);
    }

    /** True when the Home button service is switched on in Accessibility settings. */
    @JavascriptInterface
    public boolean homeServiceOn() {
        try {
            String s = Settings.Secure.getString(act.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.contains(act.getPackageName() + "/");
        } catch (Exception e) {
            return false;
        }
    }

    /** Network and storage, as JSON {online, type, mbps, freeGB, totalGB}. */
    @JavascriptInterface
    public String getSystem() {
        JSONObject o = new JSONObject();
        try {
            ConnectivityManager cm = (ConnectivityManager) act.getSystemService(Activity.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            boolean online = ni != null && ni.isConnected();
            o.put("online", online);
            String type = "none";
            if (ni != null) {
                if (ni.getType() == ConnectivityManager.TYPE_WIFI) type = "wifi";
                else if (ni.getType() == ConnectivityManager.TYPE_ETHERNET) type = "ethernet";
                else type = "other";
            }
            o.put("type", type);
            if ("wifi".equals(type)) {
                WifiManager wm = (WifiManager) act.getApplicationContext().getSystemService(Activity.WIFI_SERVICE);
                WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
                if (wi != null) o.put("mbps", wi.getLinkSpeed());
            }
            StatFs st = new StatFs(Environment.getDataDirectory().getPath());
            o.put("freeGB", st.getAvailableBytes() / 1e9);
            o.put("totalGB", st.getTotalBytes() / 1e9);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    /**
     * Fetches a URL off the main thread and hands the result to window.__httpCb(id, ok, body).
     * Done natively so weather and scores aren't blocked by browser cross-site rules.
     */
    @JavascriptInterface
    public void httpGet(final String url, final String id) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                String body;
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(12000);
                    c.setRequestProperty("User-Agent", "CommandCentre/1.0 (Android TV home screen)");
                    c.setRequestProperty("Accept", "application/json");
                    int code = c.getResponseCode();
                    InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
                    body = in == null ? "" : read(in);
                    ok = code < 400;
                    if (!ok) body = "HTTP " + code;
                } catch (Exception e) {
                    body = String.valueOf(e.getMessage());
                } finally {
                    if (c != null) c.disconnect();
                }
                final String js = "window.__httpCb && window.__httpCb(" + JSONObject.quote(id) + "," + ok + "," + JSONObject.quote(body) + ")";
                web.post(new Runnable() {
                    @Override
                    public void run() {
                        web.evaluateJavascript(js, null);
                    }
                });
            }
        }).start();
    }

    // ------------------------------------------------------------------ continue watching

    /** What's been played recently, newest first, as a JSON array (see MediaWatcher). */
    @JavascriptInterface
    public String getWatch() {
        return act.getSharedPreferences(MediaWatcher.PREFS, Activity.MODE_PRIVATE).getString(MediaWatcher.KEY, "[]");
    }

    /** Saved artwork for a show as a JPEG data URL, or "". Only reads files MediaWatcher saved. */
    @JavascriptInterface
    public String getArt(String path) {
        if (path == null || path.isEmpty()) return "";
        synchronized (iconCache) {
            String c = iconCache.get("art:" + path);
            if (c != null) return c;
        }
        String out = "";
        try {
            File f = new File(path);
            File dir = new File(act.getFilesDir(), "art");
            if (f.getCanonicalPath().startsWith(dir.getCanonicalPath()) && f.exists()) {
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                byte[] b = new byte[(int) f.length()];
                int off = 0, n;
                while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
                in.close();
                out = "data:image/jpeg;base64," + Base64.encodeToString(b, Base64.NO_WRAP);
            }
        } catch (Exception ignored) {
        }
        synchronized (iconCache) {
            iconCache.put("art:" + path, out);
        }
        return out;
    }

    /** True when Command Centre is allowed to see what's playing. */
    @JavascriptInterface
    public boolean watchAccess() {
        try {
            String s = Settings.Secure.getString(act.getContentResolver(), "enabled_notification_listeners");
            return s != null && s.contains(act.getPackageName() + "/");
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public boolean openWatchAccess() {
        return start(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** Removes one show from the list. */
    @JavascriptInterface
    public void removeWatch(String id) {
        try {
            SharedPreferences sp = act.getSharedPreferences(MediaWatcher.PREFS, Activity.MODE_PRIVATE);
            JSONArray old = new JSONArray(sp.getString(MediaWatcher.KEY, "[]")), out = new JSONArray();
            for (int i = 0; i < old.length(); i++) if (!id.equals(old.getJSONObject(i).optString("id"))) out.put(old.get(i));
            sp.edit().putString(MediaWatcher.KEY, out.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /**
     * Goes back to a show: opens the exact video/episode where the app supports a link to it,
     * otherwise opens the app, then presses play if that app's player is still running.
     */
    @JavascriptInterface
    public boolean resumeWatch(String id) {
        JSONObject item = null;
        try {
            JSONArray arr = new JSONArray(getWatch());
            for (int i = 0; i < arr.length(); i++) if (id.equals(arr.getJSONObject(i).optString("id"))) item = arr.getJSONObject(i);
        } catch (Exception ignored) {
        }
        if (item == null) return false;
        final String pkg = item.optString("pkg");
        String mediaId = item.optString("mediaId", "");

        boolean opened = false;
        String link = deepLink(pkg, mediaId);
        if (link != null) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
            i.setPackage(pkg);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (pkg.contains("netflix")) i.putExtra("source", "30");
            opened = start(i);
        }
        if (!opened) opened = launch(pkg);
        if (!opened) return false;

        final MediaController live;
        synchronized (MediaWatcher.live) {
            live = MediaWatcher.live.get(pkg);
        }
        if (live != null && link == null) {
            web.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        live.getTransportControls().play();
                    } catch (Exception ignored) {
                    }
                }
            }, 2500);
        }
        return true;
    }

    private static String deepLink(String pkg, String mediaId) {
        if (mediaId == null || mediaId.isEmpty()) return null;
        if (pkg.contains("youtube") && mediaId.matches("[A-Za-z0-9_-]{11}")) return "https://www.youtube.com/watch?v=" + mediaId;
        if (pkg.contains("netflix") && mediaId.matches("\\d{5,12}")) return "https://www.netflix.com/watch/" + mediaId;
        return null;
    }

    // ------------------------------------------------------------------ spotify

    private String spotArtKey = null, spotArtUrl = "";

    private static MediaController spotifySession() {
        synchronized (MediaWatcher.live) {
            for (Map.Entry<String, MediaController> e : MediaWatcher.live.entrySet()) {
                if (MediaWatcher.isMusic(e.getKey())) return e.getValue();
            }
        }
        return null;
    }

    /**
     * What Spotify is playing, as JSON {title, artist, album, art, dur, pos, playing}.
     * Empty object when Spotify has no media session (not running, or nothing queued).
     */
    @JavascriptInterface
    public String getSpotify() {
        JSONObject o = new JSONObject();
        try {
            MediaController c = spotifySession();
            MediaMetadata m = c == null ? null : c.getMetadata();
            if (m == null) return o.toString();
            String title = m.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (title == null || title.trim().isEmpty()) title = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            if (title == null || title.trim().isEmpty()) return o.toString();
            String artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (artist == null) artist = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            o.put("title", title.trim());
            o.put("artist", artist == null ? "" : artist.trim());
            String album = m.getString(MediaMetadata.METADATA_KEY_ALBUM);
            o.put("album", album == null ? "" : album.trim());
            o.put("dur", Math.max(0, m.getLong(MediaMetadata.METADATA_KEY_DURATION)));

            PlaybackState ps = c.getPlaybackState();
            boolean playing = false;
            long pos = 0;
            if (ps != null) {
                int st = ps.getState();
                playing = st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING;
                pos = ps.getPosition();
                if (st == PlaybackState.STATE_PLAYING && ps.getLastPositionUpdateTime() > 0) {
                    pos += (long) ((SystemClock.elapsedRealtime() - ps.getLastPositionUpdateTime()) * ps.getPlaybackSpeed());
                }
            }
            o.put("playing", playing);
            o.put("pos", Math.max(0, pos));

            String key = title + "|" + artist + "|" + album;
            if (!key.equals(spotArtKey)) {
                spotArtKey = key;
                Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                spotArtUrl = toJpegUrl(art, 320);
            }
            o.put("art", spotArtUrl);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    /** Spotify's package on this device, or null if it isn't installed. */
    private String spotifyPkg() {
        PackageManager pm = act.getPackageManager();
        for (String p : new String[]{"com.spotify.tv.android", "com.spotify.music"}) {
            try {
                pm.getPackageInfo(p, 0);
                return p;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * Sends play/pause ("toggle"), "pause", "next" or "prev" to Spotify.
     * If Spotify isn't running yet, a Play press is sent straight to it so it resumes your music in the
     * background while Command Centre stays on screen. False if Spotify isn't installed.
     */
    @JavascriptInterface
    public boolean spotifyControl(String action) {
        MediaController c = spotifySession();
        if (c == null) {
            if ("pause".equals(action)) return true;
            String pkg = spotifyPkg();
            if (pkg == null) return false;
            int code = "next".equals(action) ? KeyEvent.KEYCODE_MEDIA_NEXT
                    : "prev".equals(action) ? KeyEvent.KEYCODE_MEDIA_PREVIOUS : KeyEvent.KEYCODE_MEDIA_PLAY;
            try {
                for (int a : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
                    Intent i = new Intent(Intent.ACTION_MEDIA_BUTTON);
                    i.setPackage(pkg);
                    i.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(a, code));
                    act.sendBroadcast(i);
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        try {
            MediaController.TransportControls t = c.getTransportControls();
            if ("next".equals(action)) t.skipToNext();
            else if ("prev".equals(action)) t.skipToPrevious();
            else if ("pause".equals(action)) t.pause();
            else {
                PlaybackState ps = c.getPlaybackState();
                int st = ps == null ? PlaybackState.STATE_NONE : ps.getState();
                if (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING) t.pause();
                else t.play();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Keeps the TV from dropping to the screensaver while music is playing, so the song stays on screen. */
    @JavascriptInterface
    public void keepAwake(final boolean on) {
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (on) act.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else act.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });
    }

    // ------------------------------------------------------------------ fire tv settings

    /** Media volume as JSON {v, max, fixed}. "fixed" means the TV or soundbar controls volume, not the Stick. */
    @JavascriptInterface
    public String getVolume() {
        JSONObject o = new JSONObject();
        try {
            AudioManager am = (AudioManager) act.getSystemService(Activity.AUDIO_SERVICE);
            o.put("v", am.getStreamVolume(AudioManager.STREAM_MUSIC));
            o.put("max", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            o.put("fixed", am.isVolumeFixed());
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    /** Volume up (+1) or down (-1), showing Fire OS's own volume bar. */
    @JavascriptInterface
    public void stepVolume(int dir) {
        try {
            AudioManager am = (AudioManager) act.getSystemService(Activity.AUDIO_SERVICE);
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
        } catch (Exception ignored) {
        }
    }

    /**
     * Opens one page of Fire TV settings. Each page has a few intents to try, because Fire OS versions
     * differ; if none of them exist the main settings screen opens and this returns false.
     */
    @JavascriptInterface
    public boolean openSystem(String key) {
        String[] tries;
        switch (key == null ? "" : key) {
            case "network": tries = new String[]{Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS}; break;
            case "display": tries = new String[]{Settings.ACTION_DISPLAY_SETTINGS}; break;
            case "sound": tries = new String[]{Settings.ACTION_SOUND_SETTINGS}; break;
            case "screensaver": tries = new String[]{Settings.ACTION_DREAM_SETTINGS, Settings.ACTION_DISPLAY_SETTINGS}; break;
            case "bluetooth": tries = new String[]{Settings.ACTION_BLUETOOTH_SETTINGS}; break;
            case "apps": tries = new String[]{Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, Settings.ACTION_APPLICATION_SETTINGS}; break;
            case "accessibility": tries = new String[]{Settings.ACTION_ACCESSIBILITY_SETTINGS}; break;
            case "date": tries = new String[]{Settings.ACTION_DATE_SETTINGS}; break;
            case "language": tries = new String[]{Settings.ACTION_LOCALE_SETTINGS}; break;
            case "privacy": tries = new String[]{Settings.ACTION_PRIVACY_SETTINGS}; break;
            case "storage": tries = new String[]{Settings.ACTION_INTERNAL_STORAGE_SETTINGS, Settings.ACTION_MEMORY_CARD_SETTINGS}; break;
            case "about": tries = new String[]{Settings.ACTION_DEVICE_INFO_SETTINGS}; break;
            case "developer": tries = new String[]{Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS}; break;
            default: tries = new String[0];
        }
        PackageManager pm = act.getPackageManager();
        for (String a : tries) {
            Intent i = new Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (i.resolveActivity(pm) != null && start(i)) return true;
        }
        start(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return tries.length == 0;
    }

    private static String toJpegUrl(Bitmap bmp, int maxSide) {
        if (bmp == null) return "";
        try {
            float k = Math.min(1f, maxSide / (float) Math.max(bmp.getWidth(), bmp.getHeight()));
            Bitmap s = k < 1f ? Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(bmp.getWidth() * k)), Math.max(1, Math.round(bmp.getHeight() * k)), true) : bmp;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            s.compress(Bitmap.CompressFormat.JPEG, 85, bos);
            return "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    @JavascriptInterface
    public String version() {
        try {
            return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private boolean start(final Intent i) {
        try {
            act.startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toString("UTF-8");
    }
}
