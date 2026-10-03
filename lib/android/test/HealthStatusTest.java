package dev.phonestation.adbkeep;

final class HealthStatusTest {
    static Json shell(boolean ready, String state) {
        return Json.obj().put("available", ready).put("state", state).put("reason", ready ? "" : "请处理 Shizuku");
    }
    static Json notifications(boolean on, boolean access, int count, boolean listener, boolean online) {
        return Json.obj().put("enabled", on).put("accessGranted", access).put("selectedCount", count)
                .put("listenerConnected", listener).put("macOnline", online);
    }
    static PermissionCopy.Board board(PermissionCopy.ShizukuState state) {
        return PermissionCopy.present(true, true, true, true, true, true, true, state);
    }
    static Json issues(PermissionCopy.Board board, Json shell, Json clipboard, Json notification, boolean mcp) {
        return HealthStatus.present(board, shell, clipboard, notification, true, mcp, false, false, false, "未配置").get("issues");
    }
    static void expect(boolean value, String message) { if (!value) { throw new AssertionError(message); } }
    public static void main(String[] ignored) {
        Json ready = shell(true, "granted"), stopped = shell(false, "stopped");
        Json offNotifications = notifications(false, false, 0, false, false);
        Json unavailable = ClipboardAvailability.present(true, true, true, true, stopped, false, "");
        expect("Shizuku 未启动".equals(unavailable.get("status").string()), "stopped is not denied or disconnected");
        expect(unavailable.get("transportConnected").boolValue() && !unavailable.get("ready").boolValue(), "transport online, feature blocked");
        Json denied = ClipboardAvailability.present(true, true, true, true, shell(false, "denied"), false, "");
        expect("Shizuku 未授权".equals(denied.get("status").string()), "denied has an explicit action");
        Json list = issues(board(PermissionCopy.ShizukuState.STOPPED), stopped, unavailable, offNotifications, true);
        expect(list.array().size() == 1 && "shizuku".equals(list.array().get(0).get("id").string()), "one cause, no misleading Mac offline issue");
        Json online = ClipboardAvailability.present(true, true, true, true, ready, true, "");
        Json locked = ClipboardAvailability.withContent(
                ClipboardAvailability.present(true, true, true, true, ready, true, ""), "locked", "text");
        expect(!locked.get("ready").boolValue() && locked.get("status").string().contains("锁定"),
                "home cannot claim active syncing while locked");
        Json skipped = ClipboardAvailability.withContent(
                ClipboardAvailability.present(true, true, true, true, ready, true, ""), "text", "sensitive");
        expect(!skipped.get("ready").boolValue() && skipped.get("status").string().contains("Mac 敏感"),
                "Mac content exclusions are visible without clipboard text");
        for (String kind : new String[] {"unsupported", "image"}) {
            Json ignoredImage = ClipboardAvailability.withContent(
                    ClipboardAvailability.present(true, true, true, true, ready, true, ""), "text", kind);
            expect(ignoredImage.get("ready").boolValue()
                    && "自动双向同步中".equals(ignoredImage.get("status").string()),
                    "an ignored image or file is not a paused clipboard session");
        }
        expect(ClipboardAvailability.withContent(unavailable, "locked", "text").get("status").string().equals("Shizuku 未启动"),
                "a dependency blocker outranks stale content metadata");
        expect(issues(board(PermissionCopy.ShizukuState.GRANTED), ready, online, offNotifications, true).array().isEmpty(), "recovery clears warnings");
        Json waiting = ClipboardAvailability.present(true, false, true, true, ready, false, "");
        expect(waiting.get("reason").string().contains("电脑通道已连接"), "clipboard heartbeat is distinct from transport");
        list = issues(board(PermissionCopy.ShizukuState.GRANTED), ready, waiting, offNotifications, true);
        expect("clipboard".equals(list.array().get(0).get("id").string()), "missing clipboard client remains in diagnostics");
        expect(HealthStatus.attention(list).array().isEmpty(), "a configured phone waiting for Mac is normal standby");
        list = issues(board(PermissionCopy.ShizukuState.GRANTED), ready, waiting,
                notifications(true, true, 1, true, false), true);
        expect(list.array().size() == 2 && HealthStatus.attention(list).array().isEmpty(),
                "two Mac sessions remain in diagnostics without duplicate phone warnings");
        Json failed = ClipboardAvailability.present(true, true, true, true, ready, false, "后台绑定失败");
        expect("剪贴板后台服务不可用".equals(failed.get("status").string()), "service failure is not a permission diagnosis");
        list = issues(board(PermissionCopy.ShizukuState.GRANTED), ready, online, notifications(true, false, 1, true, true), true);
        expect("notification-access".equals(list.array().get(0).get("id").string()), "notification permission is reported even with online transport");
        expect(HealthStatus.attention(list).array().size() == 1, "real notification permission blockers stay visible");
        list = issues(board(PermissionCopy.ShizukuState.GRANTED), ready, online, notifications(true, true, 0, true, true), true);
        expect("notification-apps".equals(list.array().get(0).get("id").string()), "empty whitelist blocks sync");
        list = issues(board(PermissionCopy.ShizukuState.GRANTED), ready, online, notifications(true, true, 1, false, true), true);
        expect("notification-listener".equals(list.array().get(0).get("id").string()), "listener death blocks sync");
        list = issues(board(PermissionCopy.ShizukuState.GRANTED), ready, waiting, notifications(true, true, 1, true, false), false);
        expect(list.array().size() == 1 && "mcp".equals(list.array().get(0).get("id").string()), "MCP failure replaces secondary offline warnings");
        PermissionCopy.Board missing = PermissionCopy.present(false, false, false, false, false, false, true, PermissionCopy.ShizukuState.GRANTED);
        expect(issues(missing, ready, online, offNotifications, true).array().size() == 5, "all measurable missing permissions explain impact");
        expect(HealthStatus.attention(issues(missing, ready, waiting,
                notifications(true, true, 1, true, false), true)).array().size() == 5,
                "waiting Mac sessions do not inflate the missing permission count");
        list = HealthStatus.present(board(PermissionCopy.ShizukuState.GRANTED), ready, online, offNotifications,
                true, true, true, false, false, "连接失败").get("issues");
        expect(list.array().size() == 1 && "remote".equals(list.array().get(0).get("id").string()), "configured relay failure is visible");
        expect(HealthStatus.present(board(PermissionCopy.ShizukuState.GRANTED), ready, online, offNotifications,
                true, true, true, false, false, "连接中").get("issues").array().isEmpty(), "normal startup is not a relay failure");
        expect(!list.emit().contains("macText") && !list.emit().contains("phoneVersion"), "diagnostics contain no clipboard content");
        System.out.println("HealthStatusTest passed");
    }
}
