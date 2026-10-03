package dev.phonestation.adbkeep;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

final class PhoneNotifications {
    private static NotificationSyncState state;
    private static final Handler expiry = new Handler(Looper.getMainLooper());
    private static Runnable expire;
    private PhoneNotifications() {}
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("notification_sync", Context.MODE_PRIVATE);
    }
    static boolean enabled(Context context) { return prefs(context).getBoolean("enabled", false); }
    static Set<String> selected(Context context) {
        return new HashSet<>(prefs(context).getStringSet("packages", Collections.emptySet()));
    }
    static boolean accessGranted(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return manager != null && manager.isNotificationListenerAccessGranted(
                new ComponentName(context, PhoneNotificationListener.class));
    }
    static synchronized NotificationSyncState state(Context context) {
        if (state == null) { state = new NotificationSyncState(context.getPackageName()); }
        state.configure(enabled(context), selected(context));
        state.access(accessGranted(context));
        return state;
    }
    static synchronized void select(Context context, String pkg, boolean on) {
        if (context.getPackageName().equals(pkg)) { return; }
        Set<String> packages = selected(context);
        if (on) { packages.add(pkg); } else { packages.remove(pkg); }
        prefs(context).edit().putStringSet("packages", packages).apply();
        state(context);
    }
    static synchronized Json configure(Context context, Json args) {
        Json on = args.get("enabled");
        if (on != null && !on.isNull()) { prefs(context).edit().putBoolean("enabled", on.boolValue()).apply(); }
        return status(context);
    }
    static Json status(Context context) { return state(context).status(SystemClock.elapsedRealtime()); }
    static synchronized Json poll(Context context, Json args) {
        Json id = args.get("clientId");
        Json cursor = args.get("cursor");
        NotificationSyncState current = state(context);
        Json result = current.poll(id == null ? null : id.string(),
                cursor == null || cursor.isNull() ? null : cursor.string(), SystemClock.elapsedRealtime());
        if (expire != null) { expiry.removeCallbacks(expire); }
        expire = () -> current.status(SystemClock.elapsedRealtime());
        expiry.postDelayed(expire, NotificationSyncState.ONLINE_MS);
        return result;
    }
}
