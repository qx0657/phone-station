package dev.phonestation.adbkeep;

import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/** Phone-owned permissions. New tools are denied until explicitly classified. */
final class RemotePolicy {
    static final String[] SCOPES = {"files.read", "files.write", "personal", "controls", "screen", "shell"};
    private static final Map<String, String> TOOLS = new HashMap<>();
    static {
        add("status", "station_device_status", "station_controls_status", "station_shell_status", "station_notification_status");
        add("files.read", "station_file_access_policy", "station_storage_summary", "station_file_list", "station_file_stat",
                "station_file_read_text", "station_file_read_bytes", "station_file_search", "station_file_search_text");
        add("files.write", "station_file_write_text", "station_file_replace_text", "station_file_append_text", "station_file_write_bytes",
                "station_file_append_bytes", "station_file_patch_bytes", "station_file_create_directory", "station_file_delete_directory",
                "station_file_truncate_bytes", "station_file_move", "station_file_copy", "station_file_delete");
        add("personal", "station_clipboard_get", "station_clipboard_set", "station_clipboard_state", "station_clipboard_configure",
                "station_clipboard_exchange", "station_notification_poll", "station_notification_icon", "station_notification_configure");
        add("controls", "station_file_open", "station_notify", "station_stay_awake", "station_torch", "station_screen_capture",
                "station_screen_capture_start", "station_screen_capture_status", "station_screen_capture_release");
        add("screen", "station_screen_open", "station_screen_status");
        add("shell", "station_shell_exec", "station_shell_start", "station_terminal_open", "station_terminal_read",
                "station_terminal_input", "station_terminal_resize");
    }
    private static void add(String scope, String... tools) {
        for (String tool : tools) { TOOLS.put(tool, scope); }
    }

    private final Set<String> grants;
    RemotePolicy(Set<String> grants) { this.grants = new HashSet<>(grants); }

    boolean allows(String tool) {
        // Ending an existing session never grants input, capture or command execution.
        if (tool.equals("station_screen_close") || tool.equals("station_terminal_close")) { return true; }
        if (tool.equals("station_operation_status")) {
            return grants.contains("shell") || grants.contains("screen") || grants.contains("controls");
        }
        String scope = TOOLS.get(tool);
        return scope != null && (scope.equals("status") || grants.contains(scope));
    }

    void require(String tool) {
        if (!allows(tool)) { throw new FileFailure(RemotePermissionInfo.denied(TOOLS.getOrDefault(tool, ""))); }
    }

    void requireJob(Json receipt) {
        Json kind = receipt.get("kind");
        if (kind == null) { return; }
        String tool;
        switch (kind.string()) {
            case "shell": case "terminal": tool = "station_shell_start"; break;
            case "capture": tool = "station_screen_capture_start"; break;
            case "screen.open": tool = "station_screen_open"; break;
            default: throw new FileFailure("此远程任务类型未授权");
        }
        require(tool);
    }
}
