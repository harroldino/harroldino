package com.harroldino.mytv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Everything the browse page can ask the Fire Stick to do. Exposed to JavaScript as window.Android. */
public class Bridge {

    static final String PREFS = "mytv";
    private static final String KEY_LOGIN = "login";

    private final MainActivity act;
    private final WebView web;
    private final SharedPreferences prefs;
    // A few requests at once: the category lists can be large and slow.
    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    Bridge(MainActivity act, WebView web) {
        this.act = act;
        this.web = web;
        this.prefs = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The saved provider login as JSON ({server, username, password} or {m3u}), or "" if none. */
    @JavascriptInterface
    public String getLogin() {
        return prefs.getString(KEY_LOGIN, "");
    }

    /** Kept only on this Fire Stick, in the app's private storage. */
    @JavascriptInterface
    public void setLogin(String json) {
        if (json == null || json.isEmpty()) prefs.edit().remove(KEY_LOGIN).apply();
        else prefs.edit().putString(KEY_LOGIN, json).apply();
    }

    /** Fetches a URL off the main thread; answers with window.__httpCb(id, ok, body). */
    @JavascriptInterface
    public void httpGet(final String url, final String id) {
        pool.execute(() -> {
            boolean ok = false;
            String body;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", Player.USER_AGENT);
                int code = c.getResponseCode();
                InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
                body = in == null ? "" : read(in);
                ok = code < 400;
                if (!ok) body = "HTTP " + code;
            } catch (Exception e) {
                body = e.getClass().getSimpleName() + ": " + e.getMessage();
            } finally {
                if (c != null) c.disconnect();
            }
            callback(id, ok, body);
        });
    }

    /** Downloads an M3U playlist and answers with its channels as JSON [{name, url, group, logo}]. */
    @JavascriptInterface
    public void loadM3u(final String url, final String id) {
        pool.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(60000);
                c.setRequestProperty("User-Agent", Player.USER_AGENT);
                if (c.getResponseCode() >= 400) throw new Exception("HTTP " + c.getResponseCode());
                String text;
                try (InputStream in = c.getInputStream()) { text = read(in); }
                List<Channel> list = new M3uParser().parse(text);
                JSONArray arr = new JSONArray();
                for (Channel ch : list) {
                    JSONObject o = new JSONObject();
                    o.put("name", ch.name);
                    o.put("url", ch.url);
                    o.put("group", ch.group);
                    if (ch.logo != null) o.put("logo", ch.logo);
                    arr.put(o);
                }
                callback(id, true, arr.toString());
            } catch (Exception e) {
                callback(id, false, String.valueOf(e.getMessage()));
            }
        });
    }

    /**
     * Plays something full screen. {@code json} is {index, live, items:[{name, url, key, img, sub}]}.
     * For live TV, items is the whole category so Up/Down can change channel.
     */
    @JavascriptInterface
    public void play(String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONArray items = o.getJSONArray("items");
            List<Player.Item> list = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.getJSONObject(i);
                list.add(new Player.Item(it.optString("name"), it.getString("url"), it.optString("key", null),
                        it.optString("img", null), it.optString("sub", "")));
            }
            Player.queue = list;
            Player.queueIndex = o.optInt("index", 0);
            Player.queueLive = o.optBoolean("live", false);
            act.runOnUiThread(() -> act.startActivity(new Intent(act, PlayerActivity.class)));
        } catch (Exception e) {
            act.js("window.toast && window.toast(" + JSONObject.quote("Couldn't play that: " + e.getMessage()) + ")");
        }
    }

    /** Where you got up to in movies and episodes: {key: {pos, dur, name, url, img, sub, at}}. */
    @JavascriptInterface
    public String getProgress() {
        return Player.progress(act).toString();
    }

    @JavascriptInterface
    public void clearProgress(String key) {
        Player.clearProgress(act, key);
    }

    @JavascriptInterface
    public String version() {
        try {
            return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    @JavascriptInterface
    public void exit() {
        act.runOnUiThread(act::finish);
    }

    private void callback(String id, boolean ok, String body) {
        String js = "window.__httpCb && window.__httpCb(" + JSONObject.quote(id) + "," + ok + "," + JSONObject.quote(body) + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    static String read(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
