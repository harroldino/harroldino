package com.harroldino.mytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

/**
 * The browse screen (design 05, "streaming rows"). The interface is the HTML/CSS/JS in assets/,
 * shown full screen in a WebView; {@link Bridge} lets it talk to the provider and start playback.
 */
public class MainActivity extends Activity {

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(11, 11, 15));
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        // Posters come from the provider, usually over plain http.
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        web.setWebViewClient(new WebViewClient() {
            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return url == null || !url.startsWith("file:");
            }
        });

        takeLogin(getIntent());
        web.addJavascriptInterface(new Bridge(this, web), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        web.requestFocus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (takeLogin(intent) && web != null) web.reload();
    }

    /**
     * Lets the PC installer sign the app in, so the login is typed on a keyboard instead of with the remote:
     * adb shell am start -n com.harroldino.mytv/.MainActivity --es server URL --es username U --es password P
     */
    private boolean takeLogin(Intent i) {
        if (i == null) return false;
        String server = i.getStringExtra("server"), user = i.getStringExtra("username"), pass = i.getStringExtra("password");
        if (server == null || user == null || pass == null) return false;
        server = server.trim();
        if (!server.matches("(?i)^https?://.*")) server = "http://" + server;
        while (server.endsWith("/")) server = server.substring(0, server.length() - 1);
        try {
            JSONObject o = new JSONObject();
            o.put("server", server);
            o.put("username", user.trim());
            o.put("password", pass.trim());
            getSharedPreferences(Bridge.PREFS, MODE_PRIVATE).edit().putString("login", o.toString()).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    void js(final String code) {
        if (web != null) web.post(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.requestFocus();
        // Coming back from the player: refresh "Continue watching".
        js("window.onResume && window.onResume()");
    }

    @Override
    public void onBackPressed() {
        // The page decides what Back does, and calls Android.exit() when it should close the app.
        js("window.onBack && window.onBack()");
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                js("window.onMenu && window.onMenu()");
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
