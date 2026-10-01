package dev.phonestation.adbkeep;

final class StationNoteTest {
    public static void main(String[] args) {
        KeeperCopy held = KeeperCopy.present(true, true, true, true, true, 0, Long.MAX_VALUE);
        expect("已连接", "", KeeperCopy.Tone.HELD, "开", "开", "",
                StationNote.present(true, true, true, held.headline, held.tone));
        expect("等电脑", "无线调试开着", KeeperCopy.Tone.NEUTRAL, "开", "关", "",
                StationNote.present(false, true, false, held.headline, held.tone));

        KeeperCopy wifiDown = KeeperCopy.present(true, true, false, true, false, 0, Long.MAX_VALUE);
        expect("已连接", "", KeeperCopy.Tone.HELD, "开", "开", "",
                StationNote.present(true, true, true, wifiDown.headline, wifiDown.tone));

        KeeperCopy stopped = KeeperCopy.present(true, false, true, true, true, 0, Long.MAX_VALUE);
        expect("等电脑", "无线调试开着", KeeperCopy.Tone.NEUTRAL, "开", "关", "",
                StationNote.present(false, true, false, stopped.headline, stopped.tone));
        expect("已连接", "MCP 关着", KeeperCopy.Tone.HELD, "开", "关", "",
                StationNote.present(true, true, false, stopped.headline, stopped.tone));
        expect("已连接", "都关着", KeeperCopy.Tone.HELD, "关", "关", "",
                StationNote.present(true, false, false, stopped.headline, stopped.tone));

        KeeperCopy usbOff = KeeperCopy.present(true, true, false, false, true, 0, Long.MAX_VALUE);
        expect("USB 调试关着", "电脑还连着", KeeperCopy.Tone.NEUTRAL, "关", "开", "电脑还连着",
                StationNote.present(true, false, true, usbOff.headline, usbOff.tone));
        expect("USB 调试关着", "MCP 开着", KeeperCopy.Tone.NEUTRAL, "关", "开", "",
                StationNote.present(false, false, true, usbOff.headline, usbOff.tone));
        expect("USB 调试关着", "电脑还连着", KeeperCopy.Tone.NEUTRAL, "关", "关", "电脑还连着",
                StationNote.present(true, false, false, usbOff.headline, usbOff.tone));

        KeeperCopy waitWifi = KeeperCopy.present(true, true, true, false, false, 0, Long.MAX_VALUE);
        expect("等 Wi-Fi", "", KeeperCopy.Tone.NEUTRAL, "关", "关", "",
                StationNote.present(false, false, false, waitWifi.headline, waitWifi.tone));

        KeeperCopy denied = KeeperCopy.present(false, true, true, false, true, 0, Long.MAX_VALUE);
        expect("还不能写这个开关", "在电脑上运行一次安装", KeeperCopy.Tone.NEUTRAL,
                "关", "关", "在电脑上运行一次安装",
                StationNote.present(true, false, false, denied.headline, denied.tone));

        KeeperCopy opening = KeeperCopy.present(true, true, true, false, true, 0, Long.MAX_VALUE);
        expect("正在打开", "电脑还连着", KeeperCopy.Tone.NEUTRAL, "关", "开", "电脑还连着",
                StationNote.present(true, false, true, opening.headline, opening.tone));
        expect("正在打开", "", KeeperCopy.Tone.NEUTRAL, "关", "关", "",
                StationNote.present(false, false, false, opening.headline, opening.tone));

        KeeperCopy soon = KeeperCopy.present(true, true, true, false, true, 1, 0L);
        KeeperCopy later = KeeperCopy.present(true, true, true, false, true, 3, 0L);
        StationNote soonNote = StationNote.present(true, false, true, soon.headline, soon.tone);
        StationNote laterNote = StationNote.present(true, false, true, later.headline, later.tone);
        if (!"先等一会儿".equals(soonNote.title)
                || !"电脑还连着".equals(soonNote.text)
                || !"电脑还连着".equals(soonNote.expandedDetail)
                || soonNote.tone != KeeperCopy.Tone.WAITING
                || !soonNote.wakeKey().equals(laterNote.wakeKey())
                || !soonNote.fullKey().equals(laterNote.fullKey())) {
            throw new AssertionError("backoff must not change the note");
        }

        StationNote blank = StationNote.present(false, false, false, null, KeeperCopy.Tone.NEUTRAL);
        if (!"未连接".equals(blank.title)
                || !blank.text.isEmpty()
                || !"关".equals(blank.wirelessState)
                || !"关".equals(blank.mcpState)
                || !blank.expandedDetail.isEmpty()
                || blank.tone != KeeperCopy.Tone.NEUTRAL
                || !blank.fullKey().equals(blank.wakeKey())) {
            throw new AssertionError("blank keeper");
        }

        StationNote mcpOnly = StationNote.present(false, false, true, "保持已停下", KeeperCopy.Tone.NEUTRAL);
        expect("MCP 开着", "电脑还没连", KeeperCopy.Tone.NEUTRAL, "关", "开", "电脑还没连", mcpOnly);

        if (StationNote.nextId(0) != StationNote.FIRST_ID
                || StationNote.nextId(1) != 5
                || StationNote.nextId(4) != 5
                || StationNote.nextId(5) != 6
                || StationNote.nextId(StationNote.MAX_ID) != StationNote.FIRST_ID) {
            throw new AssertionError("next id");
        }
        int id = StationNote.FIRST_ID;
        for (int step = 0; step < 30; step++) {
            id = StationNote.nextId(id);
            if (id == AlertNote.REPLACE_ID
                    || id == AlertNote.STACK_ID
                    || id == StationNote.LEGACY_MCP_ID
                    || id == 2
                    || id == 3) {
                throw new AssertionError("id collided: " + id);
            }
        }
        System.out.println("StationNoteTest ok");
    }

    private static void expect(
            String title,
            String text,
            KeeperCopy.Tone tone,
            String wireless,
            String mcp,
            String expandedDetail,
            StationNote got) {
        String key = title + "\n" + text + "\n" + wireless + "\n" + mcp;
        if (!title.equals(got.title)
                || !text.equals(got.text)
                || tone != got.tone
                || !wireless.equals(got.wirelessState)
                || !mcp.equals(got.mcpState)
                || !expandedDetail.equals(got.expandedDetail)
                || !got.wakeKey().equals(key)
                || !got.fullKey().equals(got.wakeKey())) {
            throw new AssertionError(
                    "want " + title + " / " + text + " / " + tone
                            + " / " + wireless + " / " + mcp + " / " + expandedDetail
                            + " got " + got.title + " / " + got.text + " / " + got.tone
                            + " / " + got.wirelessState + " / " + got.mcpState
                            + " / " + got.expandedDetail);
        }
    }
}
