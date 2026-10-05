package dev.phonestation.adbkeep;

/** Connection transport and clipboard readiness are separate facts. Never reads clipboard text. */
final class ClipboardAvailability {
    static Json withContent(Json state, String phoneKind, String macKind) {
        if (!state.get("ready").boolValue() || !state.get("automatic").boolValue()) { return state; }
        String pause = "locked".equals(phoneKind) ? "手机已锁定 · 同步暂停" : pauseReason(phoneKind, "手机");
        if (pause.isEmpty()) { pause = pauseReason(macKind, "Mac"); }
        return pause.isEmpty() ? state : state.put("status", pause).put("ready", false);
    }
    private static String pauseReason(String kind, String name) {
        if ("sensitive".equals(kind)) { return name + " 敏感内容 · 已跳过"; }
        if ("oversize".equals(kind)) { return name + " 文字过长 · 已跳过"; }
        return "";
    }
    static Json present(boolean shared, boolean automatic, boolean mcp, boolean linked,
                        Json shell, boolean macOnline, String failure) {
        return present(shared, automatic, mcp, linked, shell, macOnline, failure, false);
    }
    static Json present(boolean shared, boolean automatic, boolean mcp, boolean linked,
                        Json shell, boolean macOnline, String failure, boolean remoteBlocked) {
        String title = "共享已关闭", reason = "", action = "none";
        if (shared) {
            if (!shell.get("available").boolValue()) {
                String state = shell.get("state").string();
                title = "stopped".equals(state) ? "Shizuku 未启动"
                        : "denied".equals(state) ? "Shizuku 未授权"
                        : "absent".equals(state) ? "Shizuku 未安装" : "Shizuku 不可用";
                reason = shell.get("reason").string();
                action = "permissions";
            } else if (!mcp) {
                title = "MCP 服务未运行";
                reason = "在「MCP 服务」中启用本地 MCP 接入或远程通道，再连接 Mac 手机工位。";
                action = "mcp";
            } else if (remoteBlocked) {
                title = "远程剪贴板未授权";
                reason = RemotePermissionInfo.denied("personal");
                action = "remotePermissions";
            } else if (failure != null && !failure.isEmpty()) {
                title = "剪贴板后台服务不可用";
                reason = failure;
                action = "permissions";
            } else if (!macOnline) {
                title = "手机端已就绪，待 Mac 接入";
                reason = linked ? "电脑通道已连接。在 Mac 手机工位开启共享剪贴板后，即可自动同步。"
                        : "在 Mac 运行手机工位，连接 MCP 服务并开启共享剪贴板。";
                action = "clipboard";
            } else {
                title = automatic ? "自动双向同步中" : "自动同步已暂停";
            }
        }
        return Json.obj().put("status", title).put("reason", reason).put("action", action)
                .put("ready", shared && reason.isEmpty()).put("shared", shared)
                .put("automatic", automatic).put("macOnline", macOnline).put("transportConnected", linked);
    }
}
