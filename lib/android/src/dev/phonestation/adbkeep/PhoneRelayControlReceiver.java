package dev.phonestation.adbkeep;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/** Pairing is installed through the existing adb connection; bearer values never enter logcat. */
public final class PhoneRelayControlReceiver extends BroadcastReceiver {
    static final String ACTION = "dev.phonestation.adbkeep.PHONE_RELAY";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 34
                && !AlertSender.allowed(getSentFromUid(), android.os.Process.myUid(), intent.getFlags())) {
            Log.w(KeeperEngine.TAG, "remote pairing rejected");
            return;
        }
        String action = intent.getStringExtra("action");
        if ("forget".equals(action)) {
            RemoteStore.forget(context);
            FileMcpService.start(context);
            return;
        }
        if (!"pair".equals(action)) {
            return;
        }
        boolean configured = RemoteStore.configure(
                context,
                intent.getStringExtra("endpoint"),
                intent.getStringExtra("pin"),
                intent.getStringExtra("token"));
        if (!configured) {
            Log.w(KeeperEngine.TAG, "remote pairing values rejected");
            return;
        }
        RemoteStore.setEnabled(context, true);
        KeeperStore.setMcpEnabled(context, true);
        FileMcpService.start(context);
    }
}
