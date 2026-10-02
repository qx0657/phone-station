package dev.phonestation.adbkeep;

final class StationNoteTest {
    public static void main(String[] args) {
        StationNote ready = note(true, true, true, true, "无线调试开着", "无线连接 · 远程连接");
        expect("电脑已连接", "无线 · 远程", "", ready);
        states("开", "开", "开", ready);
        expect("等待电脑连接", "无线调试已就绪", "",
                note(false, true, false, false, "保持已停下", ""));
        expect("等待电脑连接", "MCP 服务已就绪", "",
                note(false, false, true, false, "保持已停下", ""));

        // USB / 远程已连接时，调试问题只放在展开提示，不能误报断线。
        for (String problem : new String[] {
                "等 Wi-Fi", "USB 调试关着", "还不能写这个开关", "先等一会儿", "正在打开"}) {
            StationNote connected = note(true, false, true, true, problem, "远程连接");
            if (!"电脑已连接".equals(connected.title) || !"远程".equals(connected.text)
                    || connected.expandedDetail.isEmpty()) {
                throw new AssertionError("connected transport hidden by: " + problem);
            }
            StationNote stopped = note(true, false, true, false, problem, "USB 连接");
            expect("电脑已连接", "USB", "", stopped);
        }
        expect("电脑未连接", "等待 Wi-Fi，连接后恢复无线调试",
                "等待 Wi-Fi，连接后恢复无线调试", note(false, false, false, true, "等 Wi-Fi", ""));
        expect("电脑已连接", "连接正常", "", note(true, false, true, false, null, null));

        StationNote keeperOff = note(true, true, true, false, "无线调试开着", "无线连接 · 远程连接");
        if (ready.wakeKey().equals(keeperOff.wakeKey())) {
            throw new AssertionError("keeper switch must update visible notification");
        }
        StationNote retry = note(true, false, true, true, "先等一会儿", "远程连接");
        StationNote opening = note(true, false, true, true, "正在打开", "远程连接");
        if (retry.fullKey().equals(opening.fullKey())) {
            throw new AssertionError("visible attention must update even while connected");
        }
        // 退避倒计时不进模型，不因时间流逝把通知重新叫回。
        KeeperCopy soon = KeeperCopy.present(true, true, true, false, true, 1, 0L);
        KeeperCopy later = KeeperCopy.present(true, true, true, false, true, 3, 0L);
        if (!note(true, false, true, true, soon.headline, "远程连接").wakeKey()
                .equals(note(true, false, true, true, later.headline, "远程连接").wakeKey())) {
            throw new AssertionError("backoff countdown changed the notification");
        }

        if (StationNote.nextId(0) != StationNote.FIRST_ID
                || StationNote.nextId(1) != 5 || StationNote.nextId(4) != 5
                || StationNote.nextId(5) != 6
                || StationNote.nextId(StationNote.MAX_ID) != StationNote.FIRST_ID) {
            throw new AssertionError("next id");
        }
        int id = StationNote.FIRST_ID;
        for (int step = 0; step < 30; step++) {
            id = StationNote.nextId(id);
            if (id == AlertNote.REPLACE_ID || id == AlertNote.STACK_ID || id == StationNote.LEGACY_MCP_ID) {
                throw new AssertionError("id collided: " + id);
            }
        }
        System.out.println("StationNoteTest ok");
    }

    private static StationNote note(boolean linked, boolean wireless, boolean mcp, boolean keeper,
            String headline, String transport) {
        return StationNote.present(linked, wireless, mcp, keeper, headline, transport);
    }

    private static void expect(String title, String text, String attention, StationNote got) {
        if (!title.equals(got.title) || !text.equals(got.text)
                || !attention.equals(got.expandedDetail) || !got.fullKey().equals(got.wakeKey())) {
            throw new AssertionError("want " + title + " / " + text + " / " + attention
                    + " got " + got.title + " / " + got.text + " / " + got.expandedDetail);
        }
    }

    private static void states(String wireless, String mcp, String keeper, StationNote got) {
        if (!wireless.equals(got.wirelessState) || !mcp.equals(got.mcpState)
                || !keeper.equals(got.keeperState)) {
            throw new AssertionError("incorrect service states");
        }
    }
}
