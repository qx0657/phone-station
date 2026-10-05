package dev.phonestation.adbkeep;

final class McpStatusTest {
    private static void expect(boolean value, String message) {
        if (!value) { throw new AssertionError(message); }
    }
    private static void label(Json state, String expected) {
        expect(expected.equals(state.get("label").string()), "wanted " + expected + ": " + state.emit());
    }
    public static void main(String[] args) {
        Json remoteOnly = McpStatus.present(true, false, false, true, true, true, false, "已连接");
        label(remoteOnly, "运行中");
        expect(remoteOnly.get("available").boolValue() && !remoteOnly.get("localReady").boolValue(),
                "remote-only service is available without a local listener");
        Json localOnly = McpStatus.present(true, true, true, false, false, false, false, "未配置");
        label(localOnly, "运行中");
        expect(!localOnly.get("attention").boolValue(), "unused remote configuration is not an error");
        label(McpStatus.present(true, true, true, true, true, true, false, "已连接"), "运行中");
        for (boolean listening : new boolean[] {false, true}) {
            for (boolean connected : new boolean[] {false, true}) {
                Json paused = McpStatus.present(false, true, listening, true, true, connected, true, "连接中");
                label(paused, "已暂停");
                expect(!paused.get("available").boolValue() && !paused.get("attention").boolValue(),
                        "pause overrides stale listeners and connection events");
                expect("已暂停".equals(paused.get("localLabel").string())
                        && "已暂停".equals(paused.get("remoteLabel").string()), "both paths respect pause");
            }
        }
        Json disabled = McpStatus.present(true, false, true, false, true, true, false, "已连接");
        label(disabled, "未开启");
        expect(!disabled.get("available").boolValue(), "saved off selections override stale ready facts");
        Json pending = McpStatus.present(true, false, false, true, true, false, true, "连接待确认");
        label(pending, "连接中");
        expect(!pending.get("attention").boolValue(), "normal remote confirmation is not a settings failure");
        label(McpStatus.present(true, false, false, true, true, false, false, "连接中"), "连接中");
        Json failed = McpStatus.present(true, false, false, true, true, false, false, "连接失败");
        label(failed, "需设置");
        expect(!failed.get("available").boolValue(), "configured is not running");
        label(McpStatus.present(true, false, false, true, false, true, false, "已连接"), "需设置");
        label(McpStatus.present(true, true, false, false, false, false, false, "未配置"), "需设置");
        Json partial = McpStatus.present(true, true, true, true, true, false, false, "连接失败");
        label(partial, "部分可用");
        expect(partial.get("available").boolValue() && partial.get("attention").boolValue(),
                "one broken path must not conceal the working path or the failure");
        label(McpStatus.present(true, true, false, true, true, true, false, "已连接"), "部分可用");
        expect("未开启".equals(McpStatus.capabilities(true, true, 0, 0)), "service does not enable any feature");
        expect("已暂停".equals(McpStatus.capabilities(false, true, 3, 0)), "paused capabilities are unavailable");
        expect("需设置".equals(McpStatus.capabilities(true, true, 3, 1)), "service does not grant permission");
        expect("待接入".equals(McpStatus.capabilities(true, false, 2, 0)), "permissions do not start a service");
        expect("2 项可用".equals(McpStatus.capabilities(true, true, 2, 0)), "count only selected capabilities");
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (String group : FeaturePolicy.MCP_GROUPS) {
            for (String key : FeaturePolicy.groupKeys(group)) {
                expect(keys.add(key), "capability groups must not duplicate switches");
            }
        }
        expect(keys.equals(java.util.Set.of("files.read", "files.write", "open", "shell", "screen", "capture", "awake", "torch")),
                "every core MCP feature remains reachable from its category");
        System.out.println("McpStatusTest passed");
    }
}
