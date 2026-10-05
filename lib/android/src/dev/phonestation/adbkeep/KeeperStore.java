package dev.phonestation.adbkeep;

import android.content.Context;
import android.content.SharedPreferences;

final class KeeperStore {
    private static final String NAME = "keeper";
    private static final String ENABLED = "enabled";
    private static final String FAILURES = "failures";
    private static final String LAST_ATTEMPT = "last_attempt";
    private static final String LAST_HANDLE = "last_handle";
    private static final String ALERT_POPUP = "alert_popup";
    private static final String ALERT_SOUND = "alert_sound";
    private static final String ALERT_STACK = "alert_stack";
    /** 用户要 MCP 开着。进程被杀掉之后仍留着，开机再拉起。明确关掉才写成 false。 */
    private static final String MCP = "mcp";

    private KeeperStore() {}

    static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(ENABLED, true);
    }

    static boolean setEnabled(Context context, boolean enabled) {
        return prefs(context).edit().putBoolean(ENABLED, enabled).commit();
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

    /** 会话提醒是否在屏幕上弹出。默认弹出。 */
    static boolean alertPops(Context context) {
        return prefs(context).getBoolean(ALERT_POPUP, true);
    }

    static void setAlertPops(Context context, boolean popup) {
        prefs(context).edit().putBoolean(ALERT_POPUP, popup).commit();
    }

    /** 会话提醒是否逐条保留。默认只显示最新一条，由手机设置统一决定。 */
    static boolean alertStacks(Context context) {
        return prefs(context).getBoolean(ALERT_STACK, false);
    }

    static void setAlertStacks(Context context, boolean stack) {
        prefs(context).edit().putBoolean(ALERT_STACK, stack).commit();
    }

    /**
     * 会话提醒的铃声。没存过是 {@code null}，跟随系统通知铃声。
     * {@link AlertSoundPlan#SILENT_TOKEN} 是静音。其余是铃声地址。
     */
    static String alertSound(Context context) {
        SharedPreferences prefs = prefs(context);
        if (!prefs.contains(ALERT_SOUND)) {
            return null;
        }
        return prefs.getString(ALERT_SOUND, null);
    }

    static boolean mcpEnabled(Context context) {
        return prefs(context).getBoolean(MCP, SharedClipboard.shared(context));
    }

    static boolean setMcpEnabled(Context context, boolean enabled) {
        return prefs(context).edit().putBoolean(MCP, enabled).commit();
    }

    static void setAlertSound(Context context, String sound) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (sound == null) {
            editor.remove(ALERT_SOUND);
        } else {
            editor.putString(ALERT_SOUND, sound);
        }
        editor.commit();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }
}
