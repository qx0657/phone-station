package dev.phonestation.adbkeep;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

final class KeeperAlarm {
    private static final int REQUEST = 1001;

    private KeeperAlarm() {}

    static void schedule(Context context, long delayMs) {
        long delay = delayMs < 1_000L ? 1_000L : delayMs;
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) {
            return;
        }
        PendingIntent pending = pending(context);
        long at = SystemClock.elapsedRealtime() + delay;
        try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending);
        } catch (SecurityException error) {
            Log.w(KeeperEngine.TAG, "exact alarm denied", error);
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending);
        }
    }

    static void cancel(Context context) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) {
            return;
        }
        alarms.cancel(pending(context));
    }

    private static PendingIntent pending(Context context) {
        Intent intent = new Intent(context, AlarmReceiver.class);
        return PendingIntent.getBroadcast(
                context,
                REQUEST,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
