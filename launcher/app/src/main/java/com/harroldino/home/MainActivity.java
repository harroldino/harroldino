package com.harroldino.home;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * The home screen itself. The interface is the HTML/CSS/JS in assets/, shown full screen in a WebView.
 * {@link Bridge} gives that page access to the installed apps, launching, weather/scores requests and system info.
 */
public class MainActivity extends Activity {

    private WebView web;
    private BroadcastReceiver packageReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(11, 14, 13));
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);

        web.setWebViewClient(new WebViewClient() {
            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // The home screen never navigates away from its own page.
                return url == null || !url.startsWith("file:");
            }
        });

        web.addJavascriptInterface(new Bridge(this, web), "Android");
        MediaWatcher.onChange = new Runnable() {
            @Override
            public void run() {
                js("window.onWatchChanged && window.onWatchChanged()");
            }
        };
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        web.requestFocus();

        packageReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                js("window.onAppsChanged && window.onAppsChanged()");
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packageReceiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(packageReceiver, f);
        }
    }

    void js(final String code) {
        if (web == null) return;
        web.post(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(code, null);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.requestFocus();
        js("window.onResume && window.onResume()");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while we're already showing: go back to the main view.
        js("window.onHome && window.onHome()");
    }

    @Override
    public void onBackPressed() {
        // This is the home screen, so Back never closes it. The page decides what Back does.
        js("window.onBack && window.onBack()");
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                js("window.onMenu && window.onMenu()");
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onDestroy() {
        MediaWatcher.onChange = null;
        try {
            unregisterReceiver(packageReceiver);
        } catch (Exception ignored) {
        }
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
