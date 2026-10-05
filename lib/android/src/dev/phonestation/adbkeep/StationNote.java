package dev.phonestation.adbkeep;

/** 常驻通知：连接事实、MCP 可用性和自动保持选择分别呈现。 */
final class StationNote {
    static final int FIRST_ID = 1;
    static final int LEGACY_MCP_ID = 4;
    static final int MAX_ID = 500;

    enum Tone { QUIET, READY, WAITING }
    enum KeeperAction { NONE, PERMISSION, CONNECTION, WIFI }

    final String title;
    final String text;
    final String localName;
    final String localState;
    final Tone localTone;
    final String remoteState;
    final Tone remoteTone;
    final String mcpState;
    final Tone mcpTone;
    final String keeperState;
    final Tone keeperTone;
    final String expandedDetail;
    final boolean mcpAction;
    final KeeperAction keeperAction;

    private StationNote(boolean master, boolean localConnected, String localTransport,
            boolean wirelessOn, boolean remoteWanted, Json mcp, boolean keeperOn, String keeperHeadline) {
        localName = "usb".equals(localTransport) ? "USB"
                : localConnected && !"wireless".equals(localTransport) ? "本地" : "无线";
        boolean local = master && localConnected;
        boolean remote = master && remoteWanted && mcp.get("remoteReady").boolValue();
        localState = !master ? "已暂停" : local ? "已连接" : "未连接";
        localTone = local ? Tone.READY : Tone.QUIET;
        remoteState = !master ? "已暂停" : !remoteWanted ? "未使用"
                : remote ? "已连接" : compactRemote(mcp.get("remoteLabel").string());
        remoteTone = remote ? Tone.READY : remoteWanted && master ? Tone.WAITING : Tone.QUIET;
        mcpState = !master ? "已暂停" : mcp.get("label").string();
        mcpAction = master && mcp.get("attention").boolValue();
        mcpTone = mcpAction || "连接中".equals(mcpState) ? Tone.WAITING
                : master && mcp.get("available").boolValue() ? Tone.READY : Tone.QUIET;
        String keeperProblem = master && keeperOn ? attention(keeperHeadline) : "";
        keeperState = !master ? "已暂停" : keeperOn ? "已开启" : "已关闭";
        // 自动保持是用户选择；即使开启，也不涂成实际连接成功的绿色。
        keeperTone = keeperProblem.isEmpty() ? Tone.QUIET : Tone.WAITING;
        keeperAction = keeperProblem.isEmpty() ? KeeperAction.NONE : keeperAction(keeperHeadline);
        String mcpProblem = mcpAction ? mcpProblem(mcp, remoteWanted) : "";
        expandedDetail = join(mcpProblem, keeperProblem);
        title = !master ? "手机工位已暂停" : local || remote ? "电脑已连接"
                : "待确认".equals(remoteState) ? "确认电脑连接中"
                : wirelessOn || mcp.get("available").boolValue() || "连接中".equals(mcpState)
                ? "等待电脑连接" : "电脑未连接";
        String transports = join(local ? localName : "", remote ? "远程" : "");
        // 收起后也保留选中通道的问题；另一路在线不能把它遮住。
        text = !master ? "接入与功能设置已保留"
                : !mcpProblem.isEmpty() && !keeperProblem.isEmpty() ? "接入与自动保持需检查"
                : !mcpProblem.isEmpty() ? compactMcpProblem(mcpProblem)
                : !keeperProblem.isEmpty() ? keeperProblem
                : remoteWanted && !remote ? join("远程" + remoteState, local ? localName + "已连接" : "")
                : !transports.isEmpty() ? transports
                : mcp.get("available").boolValue() ? "MCP 运行中，等待电脑接入"
                : wirelessOn ? "无线调试已开启，等待电脑接入"
                : keeperOn ? "自动保持已开启" : "打开应用查看设置";
    }

    static StationNote present(boolean master, boolean localConnected, String localTransport,
            boolean wirelessOn, boolean remoteWanted, Json mcp, boolean keeperOn, String keeperHeadline) {
        return new StationNote(master, localConnected, localTransport, wirelessOn,
                remoteWanted, mcp, keeperOn, keeperHeadline);
    }

    String wakeKey() {
        return title + "\n" + text + "\n" + localName + "\n" + localState + "\n" + localTone
                + "\n" + remoteState + "\n" + remoteTone + "\n" + mcpState + "\n" + mcpTone
                + "\n" + keeperState + "\n" + keeperTone + "\n" + expandedDetail
                + "\n" + mcpAction + "\n" + keeperAction;
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

    private static String compactRemote(String label) {
        if ("确认连接中".equals(label) || "连接待确认".equals(label)) { return "待确认"; }
        return "未配置".equals(label) ? "待配置" : label.split(" · ", 2)[0];
    }

    private static String compactMcpProblem(String problem) {
        return problem.startsWith("本地 MCP 未运行 · ") ? "本地与远程接入需检查"
                : problem.split(" · ", 2)[0];
    }

    private static String mcpProblem(Json mcp, boolean remoteWanted) {
        String local = "未运行".equals(mcp.get("localLabel").string()) ? "本地 MCP 未运行" : "";
        String remote = mcp.get("remoteLabel").string();
        String problem = !remoteWanted || mcp.get("remoteReady").boolValue()
                || "连接中".equals(remote) || "确认连接中".equals(remote) || "连接待确认".equals(remote)
                ? "" : "未配置".equals(remote) ? "远程接入未配置" : "远程" + remote;
        return join(local, problem);
    }

    private static String join(String first, String second) {
        return first.isEmpty() ? second : second.isEmpty() ? first : first + " · " + second;
    }

    private static String attention(String headline) {
        if (headline == null) { return ""; }
        switch (headline) {
            case "还不能写这个开关": return "自动保持缺少写入权限";
            case "USB 调试关着": return "自动保持等待 USB 调试开启";
            case "等 Wi-Fi": return "自动保持等待 Wi-Fi";
            case "正在打开": return "正在恢复无线调试";
            case "先等一会儿": return "无线调试暂未恢复，稍后重试";
            default: return "";
        }
    }

    private static KeeperAction keeperAction(String headline) {
        if ("还不能写这个开关".equals(headline)) { return KeeperAction.PERMISSION; }
        if ("USB 调试关着".equals(headline)) { return KeeperAction.CONNECTION; }
        if ("等 Wi-Fi".equals(headline)) { return KeeperAction.WIFI; }
        return KeeperAction.NONE;
    }
}
