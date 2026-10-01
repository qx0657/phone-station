package dev.phonestation.adbkeep;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 用户把常驻通知划掉。全部清除不会走到这里。划掉后过一小会儿再发出来。 */
public final class StationHideReceiver extends BroadcastReceiver {
    static final String ACTION = "dev.phonestation.adbkeep.HIDE_NOTE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        StationNotifications.onUserDismissed(intent.getIntExtra(StationNotifications.EXTRA_TOKEN, -1));
    }
}
