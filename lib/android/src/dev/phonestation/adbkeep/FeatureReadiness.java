package dev.phonestation.adbkeep;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import java.util.ArrayList;
import java.util.List;

/** Only checks facts; system authorization starts after the user's explicit settings action. */
final class FeatureReadiness {
    static String reason(android.content.Context context, String key) {
        if (!StationFeatures.selected(context, key)) { return "已关闭"; }
        if (!StationFeatures.master(context)) { return "总开关已关闭，选择已保留"; }
        PermissionProbe permissions = PermissionProbe.read(context);
        List<String> missing = new ArrayList<>();
        if (needsStorage(key) && !permissions.storage) { missing.add("所有文件访问未授权"); }
        if (needsShizuku(key) && ShizukuLink.read(context) != PermissionCopy.ShizukuState.GRANTED) {
            missing.add(ShizukuShell.status(context).get("reason").string());
        }
        if ("awake".equals(key) && !permissions.canWrite) { missing.add("写入系统设置未授权，需在电脑安装时授予"); }
        if ("alerts".equals(key) && !permissions.notifications) { missing.add("通知显示未授权"); }
        if ("notifications".equals(key)) {
            if (!PhoneNotifications.accessGranted(context)) { missing.add("通知使用权未授权"); }
            if (PhoneNotifications.selected(context).isEmpty()) { missing.add("尚未选择要同步的应用"); }
        }
        if (RemoteStore.enabled(context) && RemoteStore.configured(context)
                && !RemoteStore.permission(context, FeaturePolicy.scope(key))) { missing.add("远程访问未允许，本地功能可按系统权限使用"); }
        if ("shell".equals(key) && RemoteStore.enabled(context)
                && (!RemoteStore.permission(context, "files.read") || !RemoteStore.permission(context, "files.write"))) {
            missing.add("远程 APK 安装还需允许文件读取和修改");
        }
        return missing.isEmpty() ? "已开启" : "已开启 · " + String.join("；", missing);
    }
    static boolean needsStorage(String key) {
        return key.startsWith("files.") || "open".equals(key) || "capture".equals(key);
    }
    static boolean needsShizuku(String key) {
        return "clipboard".equals(key) || "capture".equals(key) || "screen".equals(key) || "shell".equals(key) || "torch".equals(key);
    }
    static PermissionCopy.Board board(android.content.Context context, String feature) {
        PermissionProbe facts = PermissionProbe.read(context);
        java.util.Set<String> required = new java.util.HashSet<>();
        if (feature != null && !feature.isEmpty()) { addPermissions(required, feature); }
        else if (StationFeatures.master(context)) {
            for (String key : FeaturePolicy.KEYS) { if (StationFeatures.selected(context, key)) { addPermissions(required, key); } }
            if (KeeperStore.isEnabled(context)) { required.add("写入系统设置"); required.add("精确闹钟"); }
            if (KeeperStore.isEnabled(context) || KeeperStore.mcpEnabled(context) || RemoteStore.enabled(context)) {
                required.add("电池优化"); required.add("自启动"); required.add("通知");
            }
        }
        return PermissionCopy.relevant(PermissionCopy.present(facts.canWrite, facts.notifications,
                facts.banner, facts.storage, facts.battery, facts.alarm,
                StationFeatures.selected(context, "alerts") && KeeperStore.alertPops(context), ShizukuLink.read(context)), required);
    }
    private static void addPermissions(java.util.Set<String> required, String key) {
        if (needsStorage(key)) { required.add("所有文件访问"); }
        if (needsShizuku(key)) { required.add("Shizuku"); }
        if ("awake".equals(key)) { required.add("写入系统设置"); }
        if ("alerts".equals(key)) { required.add("通知"); }
    }
    static Json checks(android.content.Context context, String feature) {
        Json all = PhoneHealth.read(context).get("issues");
        if (feature == null || feature.isEmpty()) { return HealthStatus.attention(all); }
        Json result = Json.arr();
        for (Json issue : HealthStatus.forFeature(all, feature).array()) {
            if (!"remotePermissions".equals(issue.get("destination").string())) { result.add(issue); }
        }
        if (StationFeatures.active(context, feature) && RemoteStore.configured(context) && RemoteStore.enabled(context)) {
            java.util.Set<String> scopes = new java.util.LinkedHashSet<>();
            scopes.add(FeaturePolicy.scope(feature));
            if ("shell".equals(feature)) { scopes.add("files.read"); scopes.add("files.write"); }
            for (String scope : scopes) {
                if (RemoteStore.permission(context, scope)) { continue; }
                result.add(Json.obj().put("id", "remote-scope-" + scope)
                        .put("title", "允许远程使用：" + RemotePermissionInfo.title(scope))
                        .put("detail", "远程使用此功能还需允许访问；本地功能不受这项限制。")
                        .put("destination", "remotePermissions").put("scope", scope));
            }
        }
        return result;
    }
    static void prompt(Activity activity, String key) {
        if (!StationFeatures.active(activity, key)) { return; }
        String reason = reason(activity, key);
        if ("已开启".equals(reason)) { return; }
        new AlertDialog.Builder(activity).setTitle(FeaturePolicy.title(key) + "已开启")
                .setMessage(reason + "。开关选择会保留；完成设置后重新检查，操作不会自动补执行。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("完成所需设置", (dialog, which) -> activity.startActivity(
                        new Intent(activity, PermissionActivity.class).putExtra("feature", key)))
                .show();
    }
    static void promptMaster(Activity activity) {
        if (!StationFeatures.master(activity)) { return; }
        List<String> missing = new ArrayList<>();
        PermissionCopy.Board board = board(activity, "");
        if (board.missing > 0) { missing.add("已开启功能还有 " + board.missing + " 项系统权限需要处理"); }
        for (String key : FeaturePolicy.KEYS) {
            if (!StationFeatures.selected(activity, key)) { continue; }
            String reason = reason(activity, key);
            if (!"已开启".equals(reason)) { missing.add(FeaturePolicy.title(key) + "：" + reason.replaceFirst("^已开启 · ", "")); }
        }
        if (missing.isEmpty()) { return; }
        new AlertDialog.Builder(activity).setTitle("手机工位已开启")
                .setMessage(String.join("\n", missing) + "\n\n原有开关选择已保留。完成设置后恢复可用功能，不补执行旧操作。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("查看所需权限", (dialog, which) -> activity.startActivity(new Intent(activity, PermissionActivity.class)))
                .show();
    }
    private FeatureReadiness() {}
}
