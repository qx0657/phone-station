package dev.phonestation.adbkeep;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

/** 锁屏和亮屏没有剪贴板回调，本地等待也要被叫醒。 */
final class LocalEventSignals {
    private static boolean installed;

    static void install(Context context) {
        if (installed) {
            return;
        }
        synchronized (LocalEventSignals.class) {
            if (installed) {
                return;
            }
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            context.getApplicationContext().registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) {
                    PhoneRelayClient.event("clipboard");
                }
            }, filter, Context.RECEIVER_NOT_EXPORTED);
            installed = true;
        }
    }

    private LocalEventSignals() {}
}
