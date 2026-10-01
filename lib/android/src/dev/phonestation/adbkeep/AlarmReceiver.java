package dev.phonestation.adbkeep;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!KeeperStore.isEnabled(context)) {
            KeeperAlarm.cancel(context);
            return;
        }
        long next = KeeperEngine.tick(context);
        if (next >= 0L) {
            KeeperAlarm.schedule(context, next);
        }
        KeeperService.start(context);
    }
}
