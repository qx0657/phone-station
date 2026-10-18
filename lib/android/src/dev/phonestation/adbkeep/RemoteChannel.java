package dev.phonestation.adbkeep;

/**
 * 界面上的远程状态。链路主会话已经启动时以它为准，不采用旧令牌客户端的失败文案。
 * 没有启动链路主会话时，仍用旧客户端的结论。
 */
final class RemoteChannel {
    private RemoteChannel() {}

    static boolean connected(boolean enabled, boolean linkActive, boolean linkOnline, boolean legacyConnected) {
        if (!enabled) {
            return false;
        }
        return linkActive ? linkOnline : legacyConnected;
    }

    static boolean checking(boolean enabled, boolean linkActive, boolean legacyChecking) {
        if (!enabled || linkActive) {
            return false;
        }
        return legacyChecking;
    }

    static String label(boolean master, boolean configured, boolean enabled,
            boolean linkActive, boolean linkOnline, String legacyLabel) {
        if (!master) {
            return "已暂停";
        }
        if (!configured) {
            return "未配置";
        }
        if (!enabled) {
            return "已关闭";
        }
        if (linkActive) {
            return linkOnline ? "已连接" : "连接中";
        }
        return legacyLabel;
    }
}
