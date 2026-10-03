package dev.phonestation.adbkeep;

/** 常驻通知：连接是主状态，服务开关与需要处理的事分开显示。 */
final class StationNote {
    static final int FIRST_ID = 1;
    static final int LEGACY_MCP_ID = 4;
    static final int MAX_ID = 500;

    final String title;
    final String text;
    final String connection;
    final String wirelessState;
    final String mcpState;
    final String keeperState;
    final String expandedDetail;

    private StationNote(boolean linked, boolean wirelessOn, boolean mcpOn, boolean keeperOn,
            String connectionType, String attention, boolean checking) {
        title = linked ? "电脑已连接" : checking ? "确认电脑连接中" : (wirelessOn || mcpOn ? "等待电脑连接" : "电脑未连接");
        connection = linked ? compactTransport(connectionType) : "";
        wirelessState = wirelessOn ? "开" : "关";
        mcpState = mcpOn ? "开" : "关";
        keeperState = keeperOn ? "开" : "关";
        expandedDetail = attention;
        text = linked ? (connection.isEmpty() ? "连接正常" : connection)
                : checking ? "正在等待远程应答"
                : !attention.isEmpty() ? attention
                : wirelessOn ? "无线调试已就绪"
                : mcpOn ? "MCP 服务已就绪"
                : keeperOn ? "自动保持已开启" : "打开应用查看设置";
    }

    static StationNote present(boolean linked, boolean wirelessOn, boolean mcpOn, boolean keeperOn,
            String keeperHeadline, String connectionType) {
        return present(linked, wirelessOn, mcpOn, keeperOn, keeperHeadline, connectionType, false);
    }

    static StationNote present(boolean linked, boolean wirelessOn, boolean mcpOn, boolean keeperOn,
            String keeperHeadline, String connectionType, boolean checking) {
        // 调试的恢复问题不能覆盖真实的 USB 或远程连接状态。
        String attention = keeperOn ? attention(keeperHeadline) : "";
        return new StationNote(linked, wirelessOn, mcpOn, keeperOn, connectionType, attention, checking);
    }

    String wakeKey() {
        return title + "\n" + text + "\n" + wirelessState + "\n" + mcpState
                + "\n" + keeperState + "\n" + expandedDetail;
    }

    String fullKey() { return wakeKey(); }

    /** 荣耀划掉同一个 id 后不会再显示，轮换时避开提醒和旧 MCP 通知。 */
    static int nextId(int current) {
        int id = current < FIRST_ID ? FIRST_ID : current + 1;
        while (id == AlertNote.REPLACE_ID || id == AlertNote.STACK_ID || id == LEGACY_MCP_ID) {
            id++;
        }
        return id > MAX_ID ? FIRST_ID : id;
    }

    private static String compactTransport(String connectionType) {
        return connectionType == null ? "" : connectionType.replace("连接", "").trim();
    }

    private static String attention(String headline) {
        if (headline == null) { return ""; }
        switch (headline) {
            case "还不能写这个开关": return "自动保持缺少权限，打开应用处理";
            case "USB 调试关着": return "USB 调试未开启，自动保持暂停";
            case "等 Wi-Fi": return "等待 Wi-Fi，连接后恢复无线调试";
            case "正在打开": return "正在恢复无线调试";
            case "先等一会儿": return "无线调试暂未恢复，稍后重试";
            default: return "";
        }
    }
}
