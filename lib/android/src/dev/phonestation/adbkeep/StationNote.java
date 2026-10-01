package dev.phonestation.adbkeep;

/**
 * 常驻通知上的一句话。没有 Android 依赖，构建时在电脑上跑测试。
 *
 * <p>下拉栏只回答现在能不能用。电脑连着，标题是「已连接」。没连上、无线调试开着，标题是「等电脑」。
 * 只有 MCP 开着，标题是「MCP 开着」。要处理时标题换成那一句，例如「等 Wi-Fi」「USB 调试关着」。
 * 收起时，补充只在标题没说完时出现在右边，例如「MCP 关着」「电脑还连着」。
 * 两项都开着而且电脑已连接，收起就只有标题。
 * 展开后标题跟下拉栏里其他通知一样大。下面一行左边是无线调试，右边是 MCP。
 * 这一行已经说出的补充不再重复。还没说完的，例如「电脑还连着」，留在标题和这一行之间。
 * 不写退避还要等多久，也不写过程说明。
 */
final class StationNote {
    /** 第一次显示用的 id。会话提醒占 2 和 3，旧的 MCP 单独通知占 4。 */
    static final int FIRST_ID = 1;

    /** 以前 MCP服务自己那条的 id。合并之后不再发，升级时清掉。 */
    static final int LEGACY_MCP_ID = 4;

    /**
     * 换 id 时绕回去的上限，避开提醒和旧 MCP 的 id。
     * 这台上同一个 id 被划掉后不会再出现，划掉一次就用掉一个。
     */
    static final int MAX_ID = 500;

    /** 下拉栏里的那句。展开后跟旁边的通知一样大。也是系统通知的标题。 */
    final String title;
    /** 收起时右侧的补充。没有补充时是空的。也是系统通知的内容。 */
    final String text;
    final KeeperCopy.Tone tone;
    /** 展开后「无线调试」旁边。开或关。 */
    final String wirelessState;
    /** 展开后「MCP」旁边。开或关。 */
    final String mcpState;
    /**
     * 展开后夹在标题和状态行之间的一句。
     * 状态行已经说出的补充是空的。
     */
    final String expandedDetail;

    private StationNote(
            String title,
            String text,
            KeeperCopy.Tone tone,
            boolean wirelessOn,
            boolean mcpOn) {
        this.title = title;
        this.text = text;
        this.tone = tone;
        this.wirelessState = wirelessOn ? "开" : "关";
        this.mcpState = mcpOn ? "开" : "关";
        this.expandedDetail = toldByRow(text) ? "" : text;
    }

    static StationNote present(
            boolean linked,
            boolean wirelessOn,
            boolean mcpOn,
            String keeperHeadline,
            KeeperCopy.Tone tone) {
        boolean blocked = keeperHeadline != null && !keeperHeadline.isEmpty() && !calm(keeperHeadline);
        if (blocked) {
            KeeperCopy.Tone shown = tone == KeeperCopy.Tone.WAITING
                    ? KeeperCopy.Tone.WAITING
                    : KeeperCopy.Tone.NEUTRAL;
            return new StationNote(
                    keeperHeadline, blockedDetail(keeperHeadline, linked, mcpOn), shown, wirelessOn, mcpOn);
        }
        if (linked) {
            return new StationNote(
                    "已连接", readyDetail(wirelessOn, mcpOn), KeeperCopy.Tone.HELD, wirelessOn, mcpOn);
        }
        if (mcpOn && !wirelessOn) {
            return new StationNote("MCP 开着", "电脑还没连", KeeperCopy.Tone.NEUTRAL, wirelessOn, mcpOn);
        }
        if (wirelessOn) {
            String detail = mcpOn ? "无线调试和 MCP 都开着" : "无线调试开着";
            return new StationNote("等电脑", detail, KeeperCopy.Tone.NEUTRAL, wirelessOn, mcpOn);
        }
        return new StationNote("未连接", "", KeeperCopy.Tone.NEUTRAL, wirelessOn, mcpOn);
    }

    /** 划掉之后会自己再出现。再出现之前，能看见的字变了，也会把它叫回来。 */
    String wakeKey() {
        return title + "\n" + text + "\n" + wirelessState + "\n" + mcpState;
    }

    /** 还在下拉栏里时，能看见的字变了就原地改。 */
    String fullKey() {
        return wakeKey();
    }

    /**
     * 划掉之后换 id 再发。这台上同一个 id 被划掉后，再 {@code startForeground} 不会回到下拉栏。
     * 2、3 是会话提醒，4 是合并前 MCP 那条。
     */
    static int nextId(int current) {
        int id = current < FIRST_ID ? FIRST_ID : current + 1;
        while (reserved(id)) {
            id++;
        }
        if (id > MAX_ID) {
            return FIRST_ID;
        }
        return id;
    }

    /** 展开后那一行已经把这项说完，标题下面就不再重复。 */
    private static boolean toldByRow(String text) {
        return "MCP 关着".equals(text)
                || "MCP 开着".equals(text)
                || "无线调试关着".equals(text)
                || "无线调试开着".equals(text)
                || "无线调试和 MCP 都开着".equals(text)
                || "都关着".equals(text);
    }

    /** 电脑已经连上、没有要处理的事时，只补充没开的那一项。都开着就不多说。 */
    private static String readyDetail(boolean wirelessOn, boolean mcpOn) {
        if (wirelessOn && mcpOn) {
            return "";
        }
        if (wirelessOn) {
            return "MCP 关着";
        }
        if (mcpOn) {
            return "无线调试关着";
        }
        return "都关着";
    }

    private static String blockedDetail(String headline, boolean linked, boolean mcpOn) {
        if ("还不能写这个开关".equals(headline)) {
            return "在电脑上运行一次安装";
        }
        if (linked) {
            return "电脑还连着";
        }
        if (mcpOn) {
            return "MCP 开着";
        }
        return "";
    }

    private static boolean calm(String headline) {
        return "无线调试开着".equals(headline) || "保持已停下".equals(headline);
    }

    private static boolean reserved(int id) {
        return id == AlertNote.REPLACE_ID
                || id == AlertNote.STACK_ID
                || id == LEGACY_MCP_ID;
    }
}
