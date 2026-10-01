package dev.phonestation.adbkeep;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.provider.Settings;
import android.util.Log;

final class KeeperEngine {
    static final String TAG = "AdbKeep";
    private static final String ADB = "adb_enabled";
    private static final String WIFI_ADB = "adb_wifi_enabled";
    private static final String WRITE = "android.permission.WRITE_SECURE_SETTINGS";

    private KeeperEngine() {}

    static boolean canWrite(Context context) {
        return context.checkSelfPermission(WRITE) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean adbEnabled(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), ADB, 0) == 1;
    }

    static boolean wifiAdbEnabled(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), WIFI_ADB, 0) == 1;
    }

    /** 当前 Wi-Fi 网络的 handle。没连上时是 -1。不看是不是默认网络，手机开着 VPN 时 Wi-Fi 仍算连着。 */
    static long wifiHandle(Context context) {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        if (connectivity == null) {
            return -1L;
        }
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                continue;
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                continue;
            }
            return network.getNetworkHandle();
        }
        return -1L;
    }

    /** 写回当前值，确认这个应用的 uid 能改这个设置。已经是 1 时不会把开关关掉。 */
    static boolean probeWrite(Context context) {
        int current = Settings.Global.getInt(context.getContentResolver(), WIFI_ADB, 0);
        try {
            Settings.Global.putInt(context.getContentResolver(), WIFI_ADB, current);
            Log.i(TAG, "write-ok");
            return true;
        } catch (SecurityException error) {
            Log.e(TAG, "write-denied", error);
            return false;
        }
    }

    /**
     * 看一眼开关。需要再看时返回等待毫秒；保持功能关着时返回 -1。
     * 写入之前先把失败次数加一，避免系统立刻拨回关时观察者马上再写一次。
     */
    static synchronized long tick(Context context) {
        Context app = context.getApplicationContext();
        if (!KeeperStore.isEnabled(app)) {
            KeeperAlarm.cancel(app);
            return -1L;
        }

        boolean adb = adbEnabled(app);
        boolean wifiAdb = wifiAdbEnabled(app);
        long handle = wifiHandle(app);
        boolean wifi = handle != -1L;
        int failures = KeeperStore.failures(app);
        long last = KeeperStore.lastAttemptMs(app);
        long now = System.currentTimeMillis();
        long since = last == 0L ? Long.MAX_VALUE : now - last;

        if (wifiAdb && failures > 0 && since >= KeeperPolicy.CONFIRM_MS) {
            KeeperStore.setFailures(app, 0);
            failures = 0;
        }

        long knownHandle = KeeperStore.lastHandle(app);
        if (wifi && handle != knownHandle) {
            KeeperStore.setLastHandle(app, handle);
            if (knownHandle != Long.MIN_VALUE) {
                KeeperStore.setFailures(app, 0);
                failures = 0;
                since = Long.MAX_VALUE;
            }
        } else if (!wifi && knownHandle != -1L) {
            KeeperStore.setLastHandle(app, -1L);
        }

        long delay = KeeperPolicy.enableDelayMs(
                true, adb, wifiAdb, wifi, failures, since);
        Log.i(TAG, "state adb=" + (adb ? 1 : 0)
                + " wifiAdb=" + (wifiAdb ? 1 : 0)
                + " wifi=" + (wifi ? 1 : 0)
                + " failures=" + failures
                + " delay=" + delay);

        if (delay != 0L) {
            return delay < 0L ? KeeperPolicy.INTERVAL_MS : delay;
        }

        KeeperStore.setFailures(app, failures + 1);
        KeeperStore.setLastAttemptMs(app, now);
        try {
            Settings.Global.putInt(app.getContentResolver(), WIFI_ADB, 1);
            Log.i(TAG, "write-ok");
        } catch (SecurityException error) {
            Log.e(TAG, "write-denied", error);
        }
        return KeeperPolicy.CONFIRM_MS;
    }

    static String summary(Context context) {
        if (!KeeperStore.isEnabled(context)) {
            return "已暂停";
        }
        if (!canWrite(context)) {
            return "还没有写入权限";
        }
        if (!adbEnabled(context)) {
            return "USB 调试关着";
        }
        if (wifiHandle(context) == -1L) {
            return "Wi-Fi 未连接";
        }
        if (wifiAdbEnabled(context)) {
            return "无线调试开着";
        }
        return "无线调试关着，即将打开";
    }
}
