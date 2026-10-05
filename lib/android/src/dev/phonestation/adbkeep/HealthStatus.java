package dev.phonestation.adbkeep;

/** Shared, content-free home diagnostics for Android and Mac. */
final class HealthStatus {
    /** One home entry covers two independently selected notification directions. */
    static Json notificationOverview(boolean master, boolean sync, boolean syncReady, boolean syncProblem,
                                     boolean alerts, boolean alertsReady) {
        boolean attention = master && (sync && syncProblem || alerts && !alertsReady);
        boolean ready = master && !attention && (sync && syncReady || alerts && alertsReady);
        String label = !master ? "已暂停" : !sync && !alerts ? "已关闭" : attention ? "需处理"
                : sync && syncReady ? alerts ? "均已就绪" : "同步已就绪"
                : alerts && alertsReady ? "提醒已开启" : "待 Mac 接收";
        return Json.obj().put("label", label).put("ready", ready).put("attention", attention);
    }

    /** Keep a feature's setup page focused; unrelated missing permissions stay on the full check page. */
    static Json forFeature(Json issues, String feature) {
        Json result = Json.arr();
        for (Json issue : attention(issues).array()) {
            String id = issue.get("id").string();
            if ("notifications".equals(feature) && id.startsWith("notification-")
                    || "clipboard".equals(feature) && "clipboard".equals(id)
                    || ("notifications".equals(feature) || "clipboard".equals(feature)) && "mcp".equals(id)) {
                result.add(issue);
            }
        }
        return result;
    }
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
        boolean needsShizuku = java.util.Arrays.stream(board.rows).anyMatch(row -> "Shizuku".equals(row.title));
        if (needsShizuku && !shell.get("available").boolValue()) {
            Json label = ClipboardAvailability.present(true, true, true, true, shell, false, "");
            add(issues, "shizuku", label.get("status").string(),
                    "需要 Shizuku 的已开启功能尚未就绪。" + shell.get("reason").string(), "permissions");
        }
        for (PermissionCopy.Row row : board.rows) {
            if (row.tone != PermissionCopy.Tone.WAITING || "Shizuku".equals(row.title)) { continue; }
            String impact = PermissionCopy.impact(row);
            add(issues, "permission-" + row.title, row.title + " · " + row.value, impact + row.hint, "permissions");
        }
        boolean wantClipboard = clipboard.get("shared").boolValue();
        boolean wantNotifications = notifications.get("enabled").boolValue();
        if (!mcpRunning && (mcpWanted || wantClipboard || wantNotifications || remoteEnabled)) {
            add(issues, "mcp", "MCP 服务未运行", "请到「MCP 服务」启用本地接入，或配置远程连接。", "mcp");
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
            } else if (mcpRunning && notifications.get("remoteBlocked") != null && notifications.get("remoteBlocked").boolValue()) {
                if (!wantClipboard || !"remotePermissions".equals(clipboard.get("action").string())) {
                    add(issues, "notification-remote", "远程通知接收未授权", RemotePermissionInfo.denied("personal"), "remotePermissions");
                }
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
