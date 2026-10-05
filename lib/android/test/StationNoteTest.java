package dev.phonestation.adbkeep;

final class StationNoteTest {
    public static void main(String[] args) {
        Json both = service(true, true, true, true, true, true, false, "已连接");
        StationNote ready = note(true, true, "wireless", true, true, both, true, "无线调试开着");
        expect("电脑已连接", "无线 · 远程", "", ready);
        states("已连接", "已连接", "运行中", "已开启", ready);
        check(ready.localTone == StationNote.Tone.READY && ready.remoteTone == StationNote.Tone.READY
                && ready.mcpTone == StationNote.Tone.READY && ready.keeperTone == StationNote.Tone.QUIET,
                "a saved keeper choice is not a live connection");
        check(!ready.mcpAction && ready.keeperAction == StationNote.KeeperAction.NONE,
                "healthy notifications need no settings actions");

        Json localOnly = service(true, true, true, false, false, false, false, "未配置");
        StationNote local = note(true, true, "usb", false, false, localOnly, false, null);
        expect("电脑已连接", "USB", "", local);
        states("已连接", "未使用", "运行中", "已关闭", local);
        check("USB".equals(local.localName) && local.remoteTone == StationNote.Tone.QUIET,
                "USB is a real transport and unused remote is not a failure");
        StationNote unknown = note(true, true, null, false, false, localOnly, false, null);
        check("本地".equals(unknown.localName), "unknown transport must not claim wireless");

        Json remoteOnly = service(true, false, false, true, true, true, false, "已连接");
        StationNote remote = note(true, false, "wireless", true, true, remoteOnly, false, null);
        expect("电脑已连接", "远程", "", remote);
        states("未连接", "已连接", "运行中", "已关闭", remote);
        check(remote.localTone == StationNote.Tone.QUIET, "wireless switch on is not a connection");

        Json disabled = service(true, false, true, false, true, true, false, "已连接");
        StationNote off = note(true, false, null, false, false, disabled, false, "等 Wi-Fi");
        expect("电脑未连接", "打开应用查看设置", "", off);
        states("未连接", "未使用", "未开启", "已关闭", off);
        check(!off.mcpAction && off.keeperAction == StationNote.KeeperAction.NONE,
                "off selections override stale listener and relay facts");
        expect("等待电脑连接", "无线调试已开启，等待电脑接入", "",
                note(true, false, "wireless", true, false, disabled, false, null));
        expect("等待电脑连接", "MCP 运行中，等待电脑接入", "",
                note(true, false, null, false, false, localOnly, false, null));

        Json localFailure = service(true, true, false, true, true, true, false, "已连接");
        StationNote partial = note(true, false, null, false, true, localFailure, false, null);
        expect("电脑已连接", "本地 MCP 未运行", "本地 MCP 未运行", partial);
        states("未连接", "已连接", "部分可用", "已关闭", partial);
        check(partial.mcpAction && partial.mcpTone == StationNote.Tone.WAITING,
                "remote availability must not hide failed local listener");
        Json remoteFailure = service(true, true, true, true, true, false, false, "连接失败");
        StationNote failed = note(true, true, "wireless", true, true, remoteFailure, false, null);
        expect("电脑已连接", "远程连接失败", "远程连接失败", failed);
        states("已连接", "连接失败", "部分可用", "已关闭", failed);
        for (String phase : new String[] {"连接失败 · 重试中", "认证失败 · 请检查令牌"}) {
            Json relayIssue = service(true, true, true, true, true, false, false, phase);
            StationNote issue = note(true, true, "wireless", true, true, relayIssue, false, null);
            check(!issue.remoteState.contains(" · ") && !issue.text.contains(" · ")
                    && issue.expandedDetail.equals("远程" + phase) && issue.mcpAction,
                    "short state retains full relay cause and recovery action: " + phase);
        }
        Json bothFailed = service(true, true, false, true, true, false, false, "连接失败 · 重试中");
        StationNote noAccess = note(true, false, null, false, true, bothFailed, false, null);
        check("本地与远程接入需检查".equals(noAccess.text)
                && noAccess.expandedDetail.contains("本地 MCP 未运行")
                && noAccess.expandedDetail.contains("远程连接失败"), "collapse names both broken access paths");
        Json unconfigured = service(true, false, false, true, false, true, false, "已连接");
        expect("电脑未连接", "远程接入未配置", "远程接入未配置",
                note(true, false, null, false, true, unconfigured, false, null));

        for (String phase : new String[] {"连接中", "确认连接中", "连接待确认"}) {
            boolean checking = !"连接中".equals(phase);
            Json pending = service(true, false, false, true, true, false, checking, phase);
            StationNote waiting = note(true, false, null, false, true, pending, false, null);
            check((checking ? "确认电脑连接中" : "等待电脑连接").equals(waiting.title),
                    "pending title: " + phase);
            states("未连接", checking ? "待确认" : "连接中", "连接中", "已关闭", waiting);
            check(waiting.expandedDetail.isEmpty() && !waiting.mcpAction,
                    "normal confirmation is not a configuration failure");
            Json withLocal = service(true, true, true, true, true, false, checking, phase);
            StationNote oneConnected = note(true, true, "wireless", true, true, withLocal, false, null);
            check("电脑已连接".equals(oneConnected.title) && oneConnected.text.startsWith("远程")
                    && oneConnected.text.contains("无线已连接"), "collapse retains the pending second path");
        }

        for (String problem : new String[] {
                "等 Wi-Fi", "USB 调试关着", "还不能写这个开关", "先等一会儿", "正在打开"}) {
            StationNote keeper = note(true, false, null, false, true, remoteOnly, true, problem);
            check("电脑已连接".equals(keeper.title) && !keeper.expandedDetail.isEmpty()
                    && keeper.text.equals(keeper.expandedDetail),
                    "connected title and collapsed keeper problem: " + problem);
            check("已开启".equals(keeper.keeperState) && keeper.keeperTone == StationNote.Tone.WAITING,
                    "missing conditions must preserve the keeper choice");
            StationNote stopped = note(true, true, "usb", false, false, localOnly, false, problem);
            expect("电脑已连接", "USB", "", stopped);
            check(stopped.keeperAction == StationNote.KeeperAction.NONE, "off keeper must have no repair action");
        }
        check(note(true, false, null, false, true, remoteOnly, true, "还不能写这个开关").keeperAction
                == StationNote.KeeperAction.PERMISSION, "write permission routes to permissions");
        check(note(true, false, null, false, true, remoteOnly, true, "USB 调试关着").keeperAction
                == StationNote.KeeperAction.CONNECTION, "USB condition routes to connection settings");
        check(note(true, false, null, false, true, remoteOnly, true, "等 Wi-Fi").keeperAction
                == StationNote.KeeperAction.WIFI, "Wi-Fi condition routes to system Wi-Fi settings");
        StationNote twoProblems = note(true, true, "wireless", true, true, remoteFailure, true, "还不能写这个开关");
        check(twoProblems.expandedDetail.contains("远程连接失败")
                && twoProblems.expandedDetail.contains("自动保持缺少写入权限")
                && "接入与自动保持需检查".equals(twoProblems.text)
                && twoProblems.mcpAction && twoProblems.keeperAction == StationNote.KeeperAction.PERMISSION,
                "both independent problems retain their recovery actions");

        for (boolean listening : new boolean[] {false, true}) {
            for (boolean connected : new boolean[] {false, true}) {
                // 即使通知刷新收到旧运行事实，总开关也优先；不依赖服务销毁时序。
                Json paused = service(false, true, listening, true, true, connected, true, "已连接");
                StationNote stopped = note(false, connected, "usb", true, true, paused, true, "还不能写这个开关");
                expect("手机工位已暂停", "接入与功能设置已保留", "", stopped);
                states("已暂停", "已暂停", "已暂停", "已暂停", stopped);
                check(!stopped.mcpAction && stopped.keeperAction == StationNote.KeeperAction.NONE
                        && stopped.localTone == StationNote.Tone.QUIET
                        && stopped.remoteTone == StationNote.Tone.QUIET,
                        "pause overrides stale facts and attention");
            }
        }

        StationNote keeperOff = note(true, true, "wireless", true, true, both, false, "无线调试开着");
        check(!ready.wakeKey().equals(keeperOff.wakeKey()), "keeper choice change updates notification");
        check(!partial.fullKey().equals(remote.fullKey()), "local MCP recovery updates remote-only notification");
        StationNote retry = note(true, false, null, false, true, remoteOnly, true, "先等一会儿");
        StationNote opening = note(true, false, null, false, true, remoteOnly, true, "正在打开");
        check(!retry.fullKey().equals(opening.fullKey()), "visible recovery progress must refresh");
        KeeperCopy soon = KeeperCopy.present(true, true, true, false, true, 1, 0L);
        KeeperCopy later = KeeperCopy.present(true, true, true, false, true, 3, 0L);
        check(note(true, false, null, false, true, remoteOnly, true, soon.headline).wakeKey()
                .equals(note(true, false, null, false, true, remoteOnly, true, later.headline).wakeKey()),
                "backoff countdown must not bring a dismissed notification back");

        check(StationNote.nextId(0) == StationNote.FIRST_ID && StationNote.nextId(1) == 5
                && StationNote.nextId(4) == 5 && StationNote.nextId(5) == 6
                && StationNote.nextId(StationNote.MAX_ID) == StationNote.FIRST_ID, "next notification id");
        int id = StationNote.FIRST_ID;
        for (int step = 0; step < 30; step++) {
            id = StationNote.nextId(id);
            check(id != AlertNote.REPLACE_ID && id != AlertNote.STACK_ID && id != StationNote.LEGACY_MCP_ID,
                    "notification id collided: " + id);
        }
        System.out.println("StationNoteTest ok");
    }

    private static Json service(boolean master, boolean localWanted, boolean listening, boolean remoteWanted,
            boolean configured, boolean connected, boolean checking, String phase) {
        return McpStatus.present(master, localWanted, listening, remoteWanted, configured, connected, checking, phase);
    }

    private static StationNote note(boolean master, boolean local, String transport, boolean wireless,
            boolean remoteWanted, Json mcp, boolean keeper, String headline) {
        return StationNote.present(master, local, transport, wireless, remoteWanted, mcp, keeper, headline);
    }

    private static void expect(String title, String text, String attention, StationNote got) {
        check(title.equals(got.title) && text.equals(got.text) && attention.equals(got.expandedDetail)
                && got.fullKey().equals(got.wakeKey()),
                "want " + title + " / " + text + " / " + attention
                + " got " + got.title + " / " + got.text + " / " + got.expandedDetail);
    }

    private static void states(String local, String remote, String mcp, String keeper, StationNote got) {
        check(local.equals(got.localState) && remote.equals(got.remoteState)
                && mcp.equals(got.mcpState) && keeper.equals(got.keeperState), "incorrect notification states");
    }

    private static void check(boolean value, String message) {
        if (!value) { throw new AssertionError(message); }
    }
}
