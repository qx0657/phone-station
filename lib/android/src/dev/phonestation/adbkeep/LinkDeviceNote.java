package dev.phonestation.adbkeep;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.provider.Settings;

/** 把电量、网络、亮屏和调试开关推进链路的 device 主题。只读，不改 adb_wifi_enabled。 */
final class LinkDeviceNote {
    private LinkDeviceNote() {}

    static void now(Context context) {
        if (context == null) {
            return;
        }
        try {
            BatteryManager battery = context.getSystemService(BatteryManager.class);
            if (battery == null) {
                return;
            }
            int percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            if (percent < 0 || percent > 100) {
                return;
            }
            Json issues = null;
            try {
                Json health = PhoneHealth.read(context);
                if (health != null && health.get("issues") != null) {
                    issues = health.get("issues");
                }
            } catch (RuntimeException ignored) {
                // 读不到健康问题时仍发旧快照，菜单栏继续轮询。
            }
            LinkLiveEvents.state("device", null, LinkSnapshots.device(
                    percent,
                    battery.isCharging(),
                    network(context),
                    screenOn(context),
                    setting(context, Settings.Global.ADB_ENABLED),
                    setting(context, "adb_wifi_enabled"),
                    issues));
        } catch (RuntimeException ignored) {
            // 读不到状态时不发旧值。
        }
    }

    private static boolean screenOn(Context context) {
        PowerManager power = context.getSystemService(PowerManager.class);
        return power != null && power.isInteractive();
    }

    private static boolean setting(Context context, String name) {
        return Settings.Global.getInt(context.getContentResolver(), name, 0) == 1;
    }

    private static String network(Context context) {
        ConnectivityManager networks = context.getSystemService(ConnectivityManager.class);
        if (networks == null) {
            return "none";
        }
        boolean wifi = false;
        boolean cellular = false;
        Network[] all = networks.getAllNetworks();
        for (int i = 0; i < all.length; i++) {
            NetworkCapabilities caps = networks.getNetworkCapabilities(all[i]);
            if (caps == null) {
                continue;
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                wifi = true;
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                cellular = true;
            }
        }
        if (wifi) {
            return "wifi";
        }
        if (cellular) {
            return "cellular";
        }
        return "none";
    }
}
