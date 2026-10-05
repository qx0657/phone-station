package dev.phonestation.adbkeep;

/** Service availability is independent of computer presence and individual feature permissions. */
final class McpStatus {
    static Json present(boolean master, boolean localWanted, boolean localListening,
                        boolean remoteWanted, boolean remoteConfigured, boolean remoteConnected,
                        boolean remoteChecking, String remoteLabel) {
        boolean local = master && localWanted && localListening;
        boolean remote = master && remoteWanted && remoteConfigured && remoteConnected;
        boolean available = local || remote;
        boolean waiting = remoteWanted && remoteConfigured && !remote
                && (remoteChecking || "连接中".equals(remoteLabel));
        boolean attention = master && (localWanted && !local
                || remoteWanted && (!remoteConfigured || !remote && !waiting));
        String localLabel = !master ? "已暂停" : !localWanted ? "已关闭" : local ? "运行中" : "未运行";
        String relayLabel = !master ? "已暂停" : !remoteConfigured ? "未配置"
                : !remoteWanted ? "已关闭" : remote ? "已连接" : remoteLabel;
        String label = !master ? "已暂停" : attention ? available ? "部分可用" : "需设置"
                : available ? "运行中" : waiting ? "连接中" : "未开启";
        String detail = !master ? "手机工位已暂停，接入选择和功能设置已保留。"
                : !localWanted && !remoteWanted ? "开启下方本地接入，或配置远程连接。"
                : "本地：" + localLabel + "；远程：" + relayLabel + "。";
        return Json.obj().put("label", label).put("detail", detail).put("available", available)
                .put("attention", attention).put("localReady", local).put("remoteReady", remote)
                .put("localLabel", localLabel).put("remoteLabel", relayLabel);
    }

    static String capabilities(boolean master, boolean available, int selected, int blocked) {
        if (!master) { return "已暂停"; }
        if (selected == 0) { return "未开启"; }
        if (blocked > 0) { return "需设置"; }
        return available ? selected + " 项可用" : "待接入";
    }

    private McpStatus() {}
}
