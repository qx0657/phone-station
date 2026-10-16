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
    private static Runnable contentExpire;
    private static long contentExpiryAt = -1;
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
        state.configure(StationFeatures.master(context) && enabled(context), selected(context));
        state.access(accessGranted(context));
        return state;
    }
    static synchronized void select(Context context, String pkg, boolean on) {
        if (context.getPackageName().equals(pkg)) { return; }
        Set<String> packages = selected(context);
        if (on) { packages.add(pkg); } else { packages.remove(pkg); }
        prefs(context).edit().putStringSet("packages", packages).apply();
        state(context);
        PhoneRelayClient.event("notifications");
    }
    static synchronized Json configure(Context context, Json args) {
        Json on = args.get("enabled");
        if (on != null && !on.isNull()) { prefs(context).edit().putBoolean("enabled", on.boolValue()).apply(); }
        StationFeatures.publish(context);
        PhoneRelayClient.event("notifications");
        return status(context);
    }
    // Content expiry is independent of lease renewals: an idle subscription must
    // never postpone removal of the bounded notification queue.
    static synchronized void eventsChanged() {
        if (state == null) { return; }
        long now = SystemClock.elapsedRealtime();
        long delay = state.nextEventExpiry(now);
        if (delay < 0) {
            if (contentExpire != null) { expiry.removeCallbacks(contentExpire); }
            contentExpire = null; contentExpiryAt = -1; return;
        }
        if (contentExpiryAt >= now && contentExpiryAt <= now + delay) { return; }
        if (contentExpire != null) { expiry.removeCallbacks(contentExpire); }
        contentExpiryAt = now + delay;
        contentExpire = () -> {
            synchronized (PhoneNotifications.class) { contentExpiryAt = -1; eventsChanged(); }
        };
        expiry.postDelayed(contentExpire, delay);
    }
    static synchronized boolean lease(Context context, String id) {
        NotificationSyncState current = state(context);
        long now = SystemClock.elapsedRealtime();
        boolean was = current.status(now).get("macOnline").boolValue();
        boolean active = current.lease(id, now);
        noteMac(was, current.status(now).get("macOnline").boolValue());
        return active;
    }
    static Json status(Context context) { return state(context).status(SystemClock.elapsedRealtime()).put("enabled", enabled(context)).put("stationEnabled", StationFeatures.master(context)); }
    static synchronized Json poll(Context context, Json args) {
        Json id = args.get("clientId");
        Json cursor = args.get("cursor");
        NotificationSyncState current = state(context);
        long now = SystemClock.elapsedRealtime();
        boolean was = current.status(now).get("macOnline").boolValue();
        Json result = current.poll(id == null ? null : id.string(),
                cursor == null || cursor.isNull() ? null : cursor.string(), now);
        noteMac(was, result.get("macOnline").boolValue());
        return result;
    }
    static long untilOffline(long now) {
        NotificationSyncState current;
        synchronized (PhoneNotifications.class) { current = state; }
        return current == null ? -1L : current.untilOffline(now);
    }
    private static void noteMac(boolean was, boolean now) {
        if (was != now) { StationNotifications.connectionChanged(); }
        else { StationNotifications.touchFreshness(); }
    }
}
