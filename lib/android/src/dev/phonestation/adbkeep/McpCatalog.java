package dev.phonestation.adbkeep;

/** AI-facing tools. App sessions are not in this list. */
final class McpCatalog {
    private McpCatalog() {}

    static Json tools() {
        Json tools = Json.arr();
        tools.add(tool("device_list", "List paired phones.", true, false, schema(new String[] {}, props())));
        tools.add(tool("device_status", "Read battery, storage, and control state.", true, false, schema(new String[] {}, props())));
        tools.add(tool("files_list", "List a directory or search file names.", true, false,
                schema(new String[] {"path"}, props().put("path", text("Directory path.")).put("action", text("list or search.")).put("query", text("Name query when action is search.")))));
        tools.add(tool("files_stat", "Read file metadata.", true, false,
                schema(new String[] {"path"}, props().put("path", text("File path.")))));
        tools.add(tool("files_read", "Read a file as text or bytes.", true, false,
                schema(new String[] {"path"}, props().put("path", text("File path.")).put("action", text("text or bytes.")))));
        tools.add(tool("files_write", "Write or append text.", false, true,
                schema(new String[] {"path"}, props().put("path", text("File path.")).put("text", text("Text to write.")).put("action", text("write or append.")))));
        tools.add(tool("files_edit", "Replace text in a file.", false, true,
                schema(new String[] {"path"}, props().put("path", text("File path.")).put("find", text("Text to find.")).put("replace", text("Replacement.")))));
        tools.add(tool("files_manage", "Move, copy, or delete a file or directory.", false, true,
                schema(new String[] {"path", "action"}, props().put("path", text("Source path.")).put("action", text("delete, move, copy, mkdir, or rmdir.")).put("to", text("Destination for move or copy.")))));
        tools.add(tool("files_grep", "Search text inside files.", true, false,
                schema(new String[] {"path", "query"}, props().put("path", text("Directory path.")).put("query", text("Text to find.")))));
        tools.add(tool("files_transfer", "Read or write file bytes.", false, true,
                schema(new String[] {"path"}, props().put("path", text("File path.")).put("action", text("pull or push.")))));
        tools.add(tool("app_install", "Install an APK that is already on the phone.", false, true,
                schema(new String[] {"path"}, props().put("path", text("APK path.")))));
        tools.add(tool("screenshot", "Capture the current screen.", false, false,
                schema(new String[] {}, props().put("action", text("now or start.")))));
        tools.add(tool("shell_run", "Run a Shizuku shell command.", false, true,
                schema(new String[] {"command"}, props().put("command", text("Command.")).put("action", text("run or start.")))));
        tools.add(tool("clipboard_read", "Read the phone text clipboard.", true, false, schema(new String[] {}, props())));
        tools.add(tool("clipboard_write", "Replace the phone text clipboard.", false, true,
                schema(new String[] {"text"}, props().put("text", text("Clipboard text.")))));
        tools.add(tool("phone_alert", "Show a notification on the phone.", false, false,
                schema(new String[] {"text"}, props().put("text", text("Notification text.")).put("title", text("Title.")).put("agent", text("Sender.")).put("sound", text("Sound path.")))));
        tools.add(tool("device_control", "Change stay-awake, torch, find-phone ringing, or open a file.", false, false,
                schema(new String[] {"action"}, props().put("action", text("awake, torch, find_phone, or open.")).put("on", bool("Desired state.")).put("path", text("File path when action is open.")))));
        tools.add(tool("task_status", "Read one existing task receipt.", true, false,
                schema(new String[] {"id"}, props().put("id", text("Receipt id.")))));
        return tools;
    }

    static void authorizeVisible(StationHost host, String name) {
        if (!"device_control".equals(name)) { host.authorize(gate(name)); return; }
        FileFailure denied = null;
        for (String tool : new String[] {"station_stay_awake", "station_torch", "station_find_phone", "station_file_open"}) {
            try { host.authorize(tool); return; } catch (FileFailure error) { denied = error; }
        }
        throw denied;
    }

    /** Permission name used to decide whether the tool appears. */
    static String gate(String name) {
        switch (name) {
            case "device_list": case "device_status": return "station_device_status";
            case "files_list": return "station_file_list";
            case "files_stat": return "station_file_stat";
            case "files_read": return "station_file_read_text";
            case "files_write": return "station_file_write_text";
            case "files_edit": return "station_file_replace_text";
            case "files_manage": return "station_file_delete";
            case "files_grep": return "station_file_search_text";
            case "files_transfer": return "station_file_read_bytes";
            case "app_install": case "shell_run": return "station_shell_exec";
            case "screenshot": return "station_screen_capture";
            case "clipboard_read": return "station_clipboard_get";
            case "clipboard_write": return "station_clipboard_set";
            case "phone_alert": return "station_notify";
            case "device_control": return "station_stay_awake";
            case "task_status": return "station_operation_status";
            default: return null;
        }
    }

    /** Existing handler for one call. Null when the name is not in this catalog. */
    static String legacy(String name, Json args) {
        String action = "";
        if (args != null && args.get("action") != null && args.get("action").isString()) {
            action = args.get("action").string();
        }
        switch (name) {
            case "device_list": case "device_status": return "station_device_status";
            case "files_list": return "search".equals(action) ? "station_file_search" : "station_file_list";
            case "files_stat": return "station_file_stat";
            case "files_read": return "bytes".equals(action) ? "station_file_read_bytes" : "station_file_read_text";
            case "files_write":
                switch (action) {
                    case "append": return "station_file_append_text";
                    case "bytes": return "station_file_write_bytes";
                    case "append-bytes": return "station_file_append_bytes";
                    case "truncate": return "station_file_truncate_bytes";
                    default: return "station_file_write_text";
                }
            case "files_edit": return "patch".equals(action) ? "station_file_patch_bytes" : "station_file_replace_text";
            case "files_manage":
                switch (action) {
                    case "move": return "station_file_move";
                    case "copy": return "station_file_copy";
                    case "mkdir": return "station_file_create_directory";
                    case "rmdir": return "station_file_delete_directory";
                    default: return "station_file_delete";
                }
            case "files_grep": return "station_file_search_text";
            case "files_transfer": return "push".equals(action) ? "station_file_write_bytes" : "station_file_read_bytes";
            case "app_install": return "station_shell_exec";
            case "screenshot":
                switch (action) {
                    case "start": return "station_screen_capture_start";
                    case "status": return "station_screen_capture_status";
                    case "release": return "station_screen_capture_release";
                    default: return "station_screen_capture";
                }
            case "shell_run":
                switch (action) {
                    case "start": return "station_shell_start";
                    case "status": return "station_shell_status";
                    default: return "station_shell_exec";
                }
            case "clipboard_read": return "station_clipboard_get";
            case "clipboard_write": return "station_clipboard_set";
            case "phone_alert": return "station_notify";
            case "device_control":
                switch (action) {
                    case "find_phone": return "station_find_phone";
                    case "torch": return "station_torch";
                    case "open": return "station_file_open";
                    default: return "station_stay_awake";
                }
            case "task_status": return "station_operation_status";
            default: return null;
        }
    }

    /** Names removed from the AI catalog. App-only methods are not in this set. */
    static boolean retired(String name) {
        switch (name) {
            case "station_device_status": case "station_controls_status": case "station_storage_summary":
            case "station_file_access_policy": case "station_file_list": case "station_file_search":
            case "station_file_stat": case "station_file_read_text": case "station_file_read_bytes":
            case "station_file_write_text": case "station_file_write_bytes": case "station_file_append_text":
            case "station_file_append_bytes": case "station_file_truncate_bytes": case "station_file_replace_text":
            case "station_file_patch_bytes": case "station_file_copy": case "station_file_move":
            case "station_file_delete": case "station_file_delete_directory": case "station_file_create_directory":
            case "station_file_search_text": case "station_screen_capture": case "station_screen_capture_status":
            case "station_screen_capture_release": case "station_shell_exec": case "station_shell_start":
            case "station_shell_status": case "station_clipboard_get": case "station_clipboard_set":
            case "station_find_phone": case "station_notify": case "station_stay_awake": case "station_torch": case "station_file_open":
            case "station_operation_status":
                return true;
            default:
                return false;
        }
    }

    static boolean fileTool(String name) {
        return name.startsWith("station_file_") || "station_storage_summary".equals(name) || name.startsWith("files_");
    }

    private static Json tool(String name, String description, boolean readOnly, boolean destructive, Json inputSchema) {
        return Json.obj().put("name", name).put("description", description).put("inputSchema", inputSchema)
                .put("annotations", Json.obj().put("readOnlyHint", readOnly).put("destructiveHint", destructive));
    }

    private static Json schema(String[] required, Json properties) {
        Json schema = Json.obj().put("type", "object").put("properties", properties);
        if (required.length > 0) {
            Json names = Json.arr();
            for (String name : required) { names.add(Json.str(name)); }
            schema.put("required", names);
        }
        return schema;
    }

    private static Json props() { return Json.obj(); }
    private static Json text(String description) { return Json.obj().put("type", "string").put("description", description); }
    private static Json bool(String description) { return Json.obj().put("type", "boolean").put("description", description); }
}
