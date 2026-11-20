package com.harroldino.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the "Continue watching" list. Android only lets an app see what other apps are playing
 * (their media sessions: title, episode, artwork, position) if it is a notification listener, which is
 * why this is one. It doesn't read or store notifications; it only records what was playing, on this device.
 */
public class MediaWatcher extends NotificationListenerService {

    static final String PREFS = "watch";
    static final String KEY = "history";
    private static final int MAX_ITEMS = 12;

    /** Live sessions by package, so a click can resume playback in an app that is still running. */
    static final Map<String, MediaController> live = new HashMap<>();
    static volatile Runnable onChange;

    private MediaSessionManager msm;
    private final Map<String, MediaController.Callback> callbacks = new HashMap<>();
    private final Map<String, Long> lastSave = new HashMap<>();

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override
                public void onActiveSessionsChanged(List<MediaController> controllers) {
                    track(controllers);
                }
            };

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        try {
            msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName me = new ComponentName(this, MediaWatcher.class);
            msm.addOnActiveSessionsChangedListener(sessionsListener, me);
            track(msm.getActiveSessions(me));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onListenerDisconnected() {
        try {
            if (msm != null) msm.removeOnActiveSessionsChangedListener(sessionsListener);
        } catch (Exception ignored) {
        }
        super.onListenerDisconnected();
    }

    private void track(List<MediaController> controllers) {
        if (controllers == null) return;
        synchronized (live) {
            for (Map.Entry<String, MediaController> e : live.entrySet()) {
                MediaController.Callback cb = callbacks.remove(e.getKey());
                if (cb != null) e.getValue().unregisterCallback(cb);
            }
            live.clear();
            for (final MediaController c : controllers) {
                final String pkg = c.getPackageName();
                if (pkg == null || pkg.equals(getPackageName()) || live.containsKey(pkg)) continue;
                live.put(pkg, c);
                MediaController.Callback cb = new MediaController.Callback() {
                    @Override
                    public void onMetadataChanged(MediaMetadata metadata) {
                        record(c, true);
                    }

                    @Override
                    public void onPlaybackStateChanged(PlaybackState state) {
                        record(c, false);
                    }
                };
                callbacks.put(pkg, cb);
                c.registerCallback(cb);
                record(c, true);
            }
        }
    }

    private void record(MediaController c, boolean force) {
        try {
            MediaMetadata m = c.getMetadata();
            PlaybackState ps = c.getPlaybackState();
            if (m == null || ps == null) return;
            int st = ps.getState();
            if (st != PlaybackState.STATE_PLAYING && st != PlaybackState.STATE_PAUSED) return;

            String title = firstNonEmpty(m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE), m.getString(MediaMetadata.METADATA_KEY_TITLE));
            if (title == null) return;
            String sub = firstNonEmpty(m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                    m.getString(MediaMetadata.METADATA_KEY_ARTIST), m.getString(MediaMetadata.METADATA_KEY_ALBUM));
            String pkg = c.getPackageName();
            String id = pkg + "|" + title;

            long now = System.currentTimeMillis();
            Long prev = lastSave.get(id);
            if (!force && st == PlaybackState.STATE_PLAYING && prev != null && now - prev < 20_000) return;
            lastSave.put(id, now);

            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            JSONArray old = new JSONArray(sp.getString(KEY, "[]"));
            JSONObject item = null;
            List<JSONObject> rest = new ArrayList<>();
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.getJSONObject(i);
                if (id.equals(o.optString("id"))) item = o; else rest.add(o);
            }
            if (item == null) item = new JSONObject().put("id", id);
            item.put("pkg", pkg).put("title", title).put("sub", sub == null ? "" : sub).put("t", now);
            String mediaId = m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
            if (mediaId != null) item.put("mediaId", mediaId);
            long dur = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
            if (dur > 0) item.put("dur", dur);
            if (ps.getPosition() > 0) item.put("pos", ps.getPosition());

            if (!item.has("art")) {
                Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                String path = saveArt(id, art);
                if (path != null) item.put("art", path);
            }

            JSONArray out = new JSONArray().put(item);
            for (int i = 0; i < rest.size() && out.length() < MAX_ITEMS; i++) out.put(rest.get(i));
            sp.edit().putString(KEY, out.toString()).apply();
            cleanArt(out);

            Runnable r = onChange;
            if (r != null) r.run();
        } catch (Exception ignored) {
        }
    }

    private String saveArt(String id, Bitmap art) {
        if (art == null) return null;
        try {
            File dir = new File(getFilesDir(), "art");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File f = new File(dir, Integer.toHexString(id.hashCode()) + ".jpg");
            int w = Math.min(640, art.getWidth());
            int h = Math.max(1, Math.round(art.getHeight() * (w / (float) art.getWidth())));
            Bitmap scaled = Bitmap.createScaledBitmap(art, w, h, true);
            FileOutputStream fos = new FileOutputStream(f);
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, fos);
            fos.close();
            return f.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    /** Deletes artwork for shows that have dropped off the list. */
    private void cleanArt(JSONArray keep) {
        File dir = new File(getFilesDir(), "art");
        File[] files = dir.listFiles();
        if (files == null) return;
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < keep.length(); i++) paths.add(keep.optJSONObject(i).optString("art"));
        for (File f : files) if (!paths.contains(f.getAbsolutePath())) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) if (v != null && !v.trim().isEmpty()) return v.trim();
        return null;
    }
}
