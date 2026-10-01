package dev.phonestation.adbkeep;

import android.content.Context;
import android.content.SharedPreferences;

final class KeeperStore {
    private static final String NAME = "keeper";
    private static final String ENABLED = "enabled";
    private static final String FAILURES = "failures";
    private static final String LAST_ATTEMPT = "last_attempt";
    private static final String LAST_HANDLE = "last_handle";

    private KeeperStore() {}

    static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(ENABLED, true);
    }

    static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(ENABLED, enabled).commit();
    }

    static int failures(Context context) {
        return prefs(context).getInt(FAILURES, 0);
    }

    static void setFailures(Context context, int failures) {
        prefs(context).edit().putInt(FAILURES, failures).commit();
    }

    static long lastAttemptMs(Context context) {
        return prefs(context).getLong(LAST_ATTEMPT, 0L);
    }

    static void setLastAttemptMs(Context context, long now) {
        prefs(context).edit().putLong(LAST_ATTEMPT, now).commit();
    }

    static long lastHandle(Context context) {
        return prefs(context).getLong(LAST_HANDLE, Long.MIN_VALUE);
    }

    static void setLastHandle(Context context, long handle) {
        prefs(context).edit().putLong(LAST_HANDLE, handle).commit();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }
}
