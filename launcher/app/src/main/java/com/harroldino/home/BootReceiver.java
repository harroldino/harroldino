package com.harroldino.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Opens Command Centre as soon as the Fire Stick finishes starting up. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !"android.intent.action.QUICKBOOT_POWERON".equals(a)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        HomeButtonService.openCommandCentre(context);
    }
}
