package com.harroldino.mytv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** What the player should play next, and where you got up to in movies and episodes. */
final class Player {

    static final String USER_AGENT = "MyTV/1.0 (Linux; Android) ExoPlayer";
    private static final String KEY_PROGRESS = "progress";
    private static final int KEEP = 40;

    /** Handed from the browse page to {@link PlayerActivity}. Live categories can hold thousands of channels, too many for an Intent. */
    static List<Item> queue = new ArrayList<>();
    static int queueIndex;
    static boolean queueLive;

    static final class Item {
        final String name, url, key, img, sub;

        Item(String name, String url, String key, String img, String sub) {
            this.name = name;
            this.url = url;
            this.key = key;
            this.img = img;
            this.sub = sub;
        }
    }

    private Player() { }

    static JSONObject progress(Context c) {
        try {
            return new JSONObject(prefs(c).getString(KEY_PROGRESS, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static long resumeAt(Context c, String key) {
        if (key == null) return 0;
        JSONObject p = progress(c).optJSONObject(key);
        return p == null ? 0 : p.optLong("pos", 0);
    }

    /** Saves the position, or forgets it once you're near the end. Keeps the 40 most recent. */
    static void save(Context c, Item item, long pos, long dur) {
        if (item.key == null || dur <= 0) return;
        try {
            JSONObject all = progress(c);
            if (pos > dur * 0.95 || pos < 30_000) {
                all.remove(item.key);
            } else {
                JSONObject o = new JSONObject();
                o.put("pos", pos);
                o.put("dur", dur);
                o.put("name", item.name);
                o.put("url", item.url);
                o.put("sub", item.sub);
                if (item.img != null) o.put("img", item.img);
                o.put("at", System.currentTimeMillis());
                all.put(item.key, o);
            }
            while (all.length() > KEEP) {
                String oldest = null;
                long oldestAt = Long.MAX_VALUE;
                for (Iterator<String> it = all.keys(); it.hasNext(); ) {
                    String k = it.next();
                    long at = all.getJSONObject(k).optLong("at");
                    if (at < oldestAt) { oldestAt = at; oldest = k; }
                }
                all.remove(oldest);
            }
            prefs(c).edit().putString(KEY_PROGRESS, all.toString()).apply();
        } catch (Exception ignored) { }
    }

    static void clearProgress(Context c, String key) {
        JSONObject all = progress(c);
        all.remove(key);
        prefs(c).edit().putString(KEY_PROGRESS, all.toString()).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(Bridge.PREFS, Context.MODE_PRIVATE);
    }
}
