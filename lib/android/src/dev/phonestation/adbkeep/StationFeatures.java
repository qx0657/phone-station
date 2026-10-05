package dev.phonestation.adbkeep;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import java.util.LinkedHashMap;
import java.util.Map;

/** Phone-owned master switch. Turning it off never rewrites child selections. */
final class StationFeatures {
    static final String MASTER_SETTING = "phonestation_enabled";
    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences("station_features", Context.MODE_PRIVATE); }
    static synchronized void initialize(Context context) {
        if (prefs(context).getBoolean("initialized", false)) { return; }
        boolean existing = legacy(context);
        SharedPreferences.Editor editor = prefs(context).edit();
        for (String key : FeaturePolicy.KEYS) {
            if (!key.equals("clipboard") && !key.equals("notifications") && !prefs(context).contains(key)) {
                editor.putBoolean(key, existing);
            }
        }
        if (!editor.putBoolean("initialized", true).commit()) { throw new FileFailure("功能开关迁移未保存，请重试"); }
        publish(context);
    }
    static boolean master(Context context) { return prefs(context).getBoolean("master", true); }
    static boolean legacy(Context context) {
        return context.getSharedPreferences("keeper", Context.MODE_PRIVATE).contains("mcp") || RemoteStore.configured(context);
    }
    static boolean selected(Context context, String key) {
        if ("clipboard".equals(key)) { return SharedClipboard.shared(context); }
        if ("notifications".equals(key)) { return PhoneNotifications.enabled(context); }
        return prefs(context).getBoolean(key, legacy(context));
    }
    static boolean active(Context context, String key) { return master(context) && selected(context, key); }
    static Map<String, Boolean> selections(Context context) {
        Map<String, Boolean> values = new LinkedHashMap<>();
        for (String key : FeaturePolicy.KEYS) { values.put(key, selected(context, key)); }
        return values;
    }
    static void require(Context context, String tool) {
        if (!FeaturePolicy.allows(master(context), selections(context), tool)) {
            throw new FileFailure(FeaturePolicy.denied(master(context), FeaturePolicy.feature(tool)));
        }
    }
    static void requireJob(Context context, Json receipt) {
        Json kind = receipt.get("kind");
        if (kind == null) { return; }
        switch (kind.string()) {
            case "shell": case "terminal": require(context, "station_shell_start"); return;
            case "capture": require(context, "station_screen_capture_start"); return;
            case "screen.open": require(context, "station_screen_open"); return;
            default: throw new FileFailure("未知任务类型，无法读取结果");
        }
    }
    static boolean set(Context context, String key, boolean enabled) {
        if (!java.util.Arrays.asList(FeaturePolicy.KEYS).contains(key)) { throw new IllegalArgumentException("未知功能"); }
        boolean saved;
        if ("clipboard".equals(key)) { SharedClipboard.configure(context, Json.obj().put("shared", enabled)); saved = true; }
        else if ("notifications".equals(key)) { PhoneNotifications.configure(context, Json.obj().put("enabled", enabled)); saved = true; }
        else { saved = prefs(context).edit().putBoolean(key, enabled).commit(); }
        if (saved) {
            if (enabled && (key.equals("clipboard") || key.equals("notifications"))) { KeeperStore.setMcpEnabled(context, true); }
            changed(context, key);
        }
        return saved;
    }
    static boolean setMaster(Context context, boolean enabled) {
        if (!prefs(context).edit().putBoolean("master", enabled).commit()) { return false; }
        publish(context);
        RemoteStore.invalidate(context);
        if (!enabled) {
            SharedClipboard.close(); PhoneNotifications.state(context);
            StationBridge.closeJobs(); ShizukuTorch.close();
            context.stopService(new Intent(context, KeeperService.class));
            KeeperAlarm.cancel(context); FileMcpService.stop(context);
            // Restore only the snapshot created by this application.
            StayAwakeController.release(context);
        } else {
            if (KeeperStore.isEnabled(context)) { KeeperService.start(context); }
            if (KeeperStore.mcpEnabled(context) || RemoteStore.enabled(context)) { FileMcpService.start(context); }
        }
        StationNotifications.connectionChanged();
        return true;
    }
    static void changed(Context context, String key) {
        publish(context); RemoteStore.invalidate(context);
        if ("clipboard".equals(key)) { SharedClipboard.close(); }
        if ("notifications".equals(key)) { PhoneNotifications.state(context); }
        if (!selected(context, key)) {
            if ("screen".equals(key)) { ShizukuScreen.close(); }
            if ("shell".equals(key)) { ShizukuTerminal.close(); }
            if ("torch".equals(key)) { ShizukuTorch.close(); }
            if ("awake".equals(key)) { StayAwakeController.release(context); }
        }
        if (master(context) && (KeeperStore.mcpEnabled(context) || RemoteStore.enabled(context))) { FileMcpService.start(context); }
    }
    static void publish(Context context) {
        try {
            Settings.Global.putInt(context.getContentResolver(), MASTER_SETTING, master(context) ? 1 : 0);
            for (String key : FeaturePolicy.KEYS) {
                Settings.Global.putInt(context.getContentResolver(), "phonestation_feature_" + key.replace('.', '_'), selected(context, key) ? 1 : 0);
            }
        } catch (RuntimeException ignored) { /* UI and MCP use preferences; adb projection is best effort. */ }
    }
    static Json status(Context context) {
        Json choices = Json.obj(), reasons = Json.obj();
        for (String key : FeaturePolicy.KEYS) {
            choices.put(key, selected(context, key));
            reasons.put(key, FeatureReadiness.reason(context, key));
        }
        return Json.obj().put("master", master(context)).put("selected", choices).put("reasons", reasons).put("version", 1);
    }
    private StationFeatures() {}
}
