package dev.phonestation.adbkeep;

/** Shared, content-free home diagnostics for Android and Mac. */
final class HealthStatus {
    /** A missing Mac feature session is normal standby, not a phone setup failure. */
    static Json attention(Json issues) {
        Json result = Json.arr();
        for (Json issue : issues.array()) {
            String id = issue.get("id").string();
            if ("notification-mac".equals(id)
                    || ("clipboard".equals(id) && "clipboard".equals(issue.get("destination").string()))) {
                continue;
            }
            result.add(issue);
        }
        return result;
    }

    static Json present(PermissionCopy.Board board, Json shell, Json clipboard, Json notifications,
                        boolean mcpWanted, boolean mcpRunning, boolean remoteEnabled,
                        boolean remoteConnected, boolean remoteChecking, String remoteLabel) {
        Json issues = Json.arr();
        // Put a blocking dependency first, and report its effects once.
        if (!shell.get("available").boolValue()) {
            Json label = ClipboardAvailability.present(true, true, true, true, shell, false, "");
            add(issues, "shizuku", label.get("status").string(),
                    "共享剪贴板和远程 shell 不可用。" + shell.get("reason").string(), "permissions");
        }
        for (PermissionCopy.Row row : board.rows) {
            if (row.tone != PermissionCopy.Tone.WAITING || "Shizuku".equals(row.title)) { continue; }
            String impact = PermissionCopy.impact(row);
            add(issues, "permission-" + row.title, row.title + " · " + row.value, impact + row.hint, "permissions");
        }
        boolean wantClipboard = clipboard.get("shared").boolValue();
        boolean wantNotifications = notifications.get("enabled").boolValue();
        if (!mcpRunning && (mcpWanted || wantClipboard || wantNotifications || remoteEnabled)) {
            add(issues, "mcp", "MCP 服务未运行", "剪贴板、手机通知接收和远程工具需要 MCP 服务。请在首页启用或检查服务。", "mcp");
        } else if (wantClipboard && shell.get("available").boolValue()
                && !clipboard.get("reason").string().isEmpty()) {
            add(issues, "clipboard", clipboard.get("status").string(), clipboard.get("reason").string(),
                    clipboard.get("action").string());
        }
        if (wantNotifications) {
            if (!notifications.get("accessGranted").boolValue()) {
                add(issues, "notification-access", "手机通知同步未授权", "无法读取所选应用的新通知。请在通知设置中授予系统通知使用权。", "notifications");
            } else if (notifications.get("selectedCount").longValue() == 0) {
                add(issues, "notification-apps", "手机通知尚未选择应用", "同步已开启，请在通知设置中选择要同步的应用。", "notifications");
            } else if (!notifications.get("listenerConnected").boolValue()) {
                add(issues, "notification-listener", "手机通知监听未连接", "通知同步已暂停。请在通知设置中检查系统通知使用权。", "notifications");
            } else if (mcpRunning && !notifications.get("macOnline").boolValue()) {
                add(issues, "notification-mac", "手机通知等待 Mac 接收", "请在 Mac 手机工位打开手机通知，检查接收开关和显示权限。", "notifications");
            }
        }
        if (remoteEnabled && !remoteConnected && !remoteChecking && !"连接中".equals(remoteLabel)) {
            add(issues, "remote", "远程通道未连通", remoteLabel + "。请检查网络及远程中继配置。", "remoteRelay");
        }
        return Json.obj().put("issues", issues);
    }

    private static void add(Json issues, String id, String title, String detail, String destination) {
        issues.add(Json.obj().put("id", id).put("title", title).put("detail", detail).put("destination", destination));
    }
}
