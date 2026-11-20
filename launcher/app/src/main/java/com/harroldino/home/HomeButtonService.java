package com.harroldino.home;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Fire OS doesn't let you pick a different home screen, so this service watches for Amazon's home
 * screen coming to the front (which is what the remote's Home button does) and opens Command Centre instead.
 * It's limited to Amazon's launcher package in res/xml/home_button_service.xml and reads no screen content.
 */
public class HomeButtonService extends AccessibilityService {

    private static final String AMAZON_HOME = "com.amazon.tv.launcher";
    private static volatile long pausedUntil = 0;

    static void pauseFor(long millis) {
        pausedUntil = System.currentTimeMillis() + millis;
    }

    static void openCommandCentre(Context context) {
        Intent i = new Intent(context, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            context.startActivity(i);
        } catch (Exception ignored) {
        }
    }

    /** The system starts this service during boot, so this also covers switching the Fire Stick on. */
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        if (System.currentTimeMillis() < pausedUntil) return;
        openCommandCentre(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !AMAZON_HOME.contentEquals(pkg)) return;
        if (System.currentTimeMillis() < pausedUntil) return;

        openCommandCentre(this);
    }

    /**
     * Takes the remote's Home press before Fire OS sees it. If the system handled it, Amazon's home
     * screen would open first and Android would hold back our launch for about 5 seconds.
     * Every Home event is consumed (down, repeats, up) so Fire OS never sees half a press.
     */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            pausedUntil = 0;
            openCommandCentre(this);
        }
        return true;
    }

    @Override
    public void onInterrupt() {
    }
}
