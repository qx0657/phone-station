package dev.phonestation.adbkeep;

import java.util.Map;
import java.util.Set;

/** User intent is independent of system permission and transport authorization. */
final class FeaturePolicy {
    static final String[] KEYS = {"clipboard", "notifications", "alerts", "files.read", "files.write", "open", "awake", "torch", "capture", "screen", "shell"};
    static final String[] MCP_GROUPS = {"files", "commands", "controls"};
    static String[] groupKeys(String group) {
        switch (group) {
            case "files": return new String[] {"files.read", "files.write", "open"};
            case "commands": return new String[] {"shell"};
            case "controls": return new String[] {"screen", "capture", "awake", "torch"};
            default: return new String[0];
        }
    }
    static String groupTitle(String group) {
        switch (group) {
            case "files": return "文件访问";
            case "commands": return "命令与安装";
            case "controls": return "屏幕与手机控制";
            default: return "功能管理";
        }
    }
    private static final Set<String> STATUS = Set.of("station_device_status", "station_controls_status", "station_shell_status",
            "station_notification_status", "station_clipboard_state");
    static String feature(String tool) {
        if (tool.startsWith("station_clipboard_")) { return "clipboard"; }
        if (tool.startsWith("station_notification_")) { return "notifications"; }
        if (tool.equals("station_notify")) { return "alerts"; }
        if (tool.equals("station_file_open")) { return "open"; }
        if (tool.equals("station_stay_awake")) { return "awake"; }
        if (tool.equals("station_torch")) { return "torch"; }
        if (tool.startsWith("station_screen_capture")) { return "capture"; }
        if (tool.startsWith("station_screen_")) { return "screen"; }
        if (tool.startsWith("station_shell_") || tool.startsWith("station_terminal_")) { return "shell"; }
        switch (tool) {
            case "station_file_access_policy": case "station_storage_summary": case "station_file_list":
            case "station_file_stat": case "station_file_read_text": case "station_file_read_bytes":
            case "station_file_search": case "station_file_search_text": return "files.read";
            case "station_file_write_text": case "station_file_replace_text": case "station_file_append_text":
            case "station_file_write_bytes": case "station_file_append_bytes": case "station_file_patch_bytes":
            case "station_file_create_directory": case "station_file_delete_directory": case "station_file_truncate_bytes":
            case "station_file_move": case "station_file_copy": case "station_file_delete": return "files.write";
            default: return "";
        }
    }
    static boolean allows(boolean master, Map<String, Boolean> enabled, String tool) {
        // Cleanup and metadata remain safe even while stopping a service.
        if (STATUS.contains(tool) || tool.equals("station_screen_close") || tool.equals("station_terminal_close")
                || tool.equals("station_screen_capture_release")) { return true; }
        if (!master) { return false; }
        if (tool.equals("station_operation_status")) { return true; } // checked against receipt kind separately
        if (tool.equals("station_clipboard_configure") || tool.equals("station_notification_configure")) { return true; }
        String feature = feature(tool);
        return !feature.isEmpty() && Boolean.TRUE.equals(enabled.get(feature));
    }
    static boolean safe(String tool) {
        return STATUS.contains(tool) || tool.equals("station_screen_close") || tool.equals("station_terminal_close")
                || tool.equals("station_screen_capture_release");
    }
    static String title(String key) {
        switch (key) {
            case "clipboard": return "共享剪贴板";
            case "notifications": return "手机通知 → Mac";
            case "alerts": return "电脑提醒 → 手机";
            case "files.read": return "文件读取";
            case "files.write": return "文件修改";
            case "open": return "在手机打开文件";
            case "awake": return "电脑控制保持亮屏";
            case "torch": return "电脑控制手电筒";
            case "capture": return "截取画面";
            case "screen": return "投屏、录屏与操控";
            case "shell": return "Shell 与 APK 安装";
            default: return "手机工位";
        }
    }
    static String scope(String key) {
        switch (key) {
            case "clipboard": case "notifications": return "personal";
            case "alerts": case "open": case "awake": case "torch": case "capture": return "controls";
            default: return key;
        }
    }
    static String denied(boolean master, String key) {
        return !master ? "手机工位总开关已关闭，请在手机首页重新开启。"
                : "「" + title(key) + "」已关闭，请在手机「功能开关」中开启。";
    }
    private FeaturePolicy() {}
}
