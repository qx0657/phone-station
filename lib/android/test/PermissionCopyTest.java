package dev.phonestation.adbkeep;

final class PermissionCopyTest {
    public static void main(String[] args) {
        PermissionCopy.Board ready = PermissionCopy.present(true, true, true, true, true, true, true);
        expectSummary(ready, 0, "已允许", PermissionCopy.Tone.HELD);
        expectRow(ready, "写入系统设置", "已允许", "", PermissionCopy.Tone.HELD, PermissionCopy.Action.NONE);
        expectRow(ready, "通知", "已允许", "", PermissionCopy.Tone.HELD, PermissionCopy.Action.NONE);
        expectRow(ready, "所有文件访问", "已允许", "", PermissionCopy.Tone.HELD, PermissionCopy.Action.NONE);
        expectRow(ready, "电池优化", "不受限", "", PermissionCopy.Tone.HELD, PermissionCopy.Action.NONE);
        expectRow(ready, "精确闹钟", "已允许", "", PermissionCopy.Tone.HELD, PermissionCopy.Action.NONE);
        expectRow(ready, "Shizuku", "已授权", "", PermissionCopy.Tone.HELD, PermissionCopy.Action.NONE);
        expectRow(
                ready,
                "自启动",
                "去设置",
                "到应用启动管理里允许自启动和后台活动。强行停止之后要再打开一次。",
                PermissionCopy.Tone.NEUTRAL,
                PermissionCopy.Action.STARTUP);

        PermissionCopy.Board write = PermissionCopy.present(false, true, true, true, true, true, true);
        expectSummary(write, 1, "还有 1 项", PermissionCopy.Tone.WAITING);
        expectRow(
                write,
                "写入系统设置",
                "未允许",
                "在电脑上运行一次安装。",
                PermissionCopy.Tone.WAITING,
                PermissionCopy.Action.NONE);

        PermissionCopy.Board notify = PermissionCopy.present(true, false, false, true, true, true, true);
        expectRow(notify, "通知", "未允许", "点这一行，允许通知。", PermissionCopy.Tone.WAITING, PermissionCopy.Action.NOTIFY);

        PermissionCopy.Board banner = PermissionCopy.present(true, true, false, true, true, true, true);
        expectSummary(banner, 1, "还有 1 项", PermissionCopy.Tone.WAITING);
        expectRow(
                banner,
                "通知",
                "不弹出",
                "点这一行，打开「提醒」的横幅。",
                PermissionCopy.Tone.WAITING,
                PermissionCopy.Action.CHANNEL);

        PermissionCopy.Board several = PermissionCopy.present(true, true, true, false, false, false, true);
        expectSummary(several, 3, "还有 3 项", PermissionCopy.Tone.WAITING);
        expectRow(
                several,
                "所有文件访问",
                "未允许",
                "点这一行，允许所有文件访问。",
                PermissionCopy.Tone.WAITING,
                PermissionCopy.Action.STORAGE);
        expectRow(
                several,
                "电池优化",
                "受限",
                "点这一行，设为不受限制。",
                PermissionCopy.Tone.WAITING,
                PermissionCopy.Action.BATTERY);
        expectRow(
                several,
                "精确闹钟",
                "未允许",
                "点这一行，允许闹钟和提醒。",
                PermissionCopy.Tone.WAITING,
                PermissionCopy.Action.ALARM);
        if (several.rows.length != 7) {
            throw new AssertionError("row count");
        }
        PermissionCopy.Board shizukuDenied = PermissionCopy.present(
                true, true, true, true, true, true, true, PermissionCopy.ShizukuState.DENIED);
        expectSummary(shizukuDenied, 0, "已允许", PermissionCopy.Tone.HELD);
        expectRow(
                shizukuDenied,
                "Shizuku",
                "未授权",
                "点这一行，在 Shizuku 里允许。",
                PermissionCopy.Tone.NEUTRAL,
                PermissionCopy.Action.SHIZUKU);
        PermissionCopy.Board shizukuStopped = PermissionCopy.present(
                true, true, true, true, true, true, true, PermissionCopy.ShizukuState.STOPPED);
        expectRow(
                shizukuStopped,
                "Shizuku",
                "没在跑",
                "在 Shizuku 里启动。重启后要再启动一次。",
                PermissionCopy.Tone.NEUTRAL,
                PermissionCopy.Action.SHIZUKU);
        PermissionCopy.Board shizukuAbsent = PermissionCopy.present(
                true, true, true, true, true, true, true, PermissionCopy.ShizukuState.ABSENT);
        expectRow(
                shizukuAbsent,
                "Shizuku",
                "未安装",
                "先安装 Shizuku。",
                PermissionCopy.Tone.NEUTRAL,
                PermissionCopy.Action.NONE);
        PermissionCopy.Board shizukuAside = PermissionCopy.present(
                true, true, true, false, true, true, true, PermissionCopy.ShizukuState.ABSENT);
        expectSummary(shizukuAside, 1, "还有 1 项", PermissionCopy.Tone.WAITING);
        PermissionCopy.Board quiet = PermissionCopy.present(true, true, false, true, true, true, false);
        expectSummary(quiet, 0, "已允许", PermissionCopy.Tone.HELD);
        expectRow(quiet, "通知", "已允许", "", PermissionCopy.Tone.HELD, PermissionCopy.Action.NONE);
        System.out.println("PermissionCopyTest ok");
    }

    private static void expectSummary(
            PermissionCopy.Board board, int missing, String summary, PermissionCopy.Tone tone) {
        if (board.missing != missing || !summary.equals(board.summary) || tone != board.summaryTone) {
            throw new AssertionError(
                    "summary want " + missing + " / " + summary + " / " + tone
                            + " got " + board.missing + " / " + board.summary + " / " + board.summaryTone);
        }
    }

    private static void expectRow(
            PermissionCopy.Board board,
            String title,
            String value,
            String hint,
            PermissionCopy.Tone tone,
            PermissionCopy.Action action) {
        PermissionCopy.Row row = null;
        for (PermissionCopy.Row candidate : board.rows) {
            if (title.equals(candidate.title)) {
                row = candidate;
                break;
            }
        }
        if (row == null
                || !value.equals(row.value)
                || !hint.equals(row.hint)
                || tone != row.tone
                || action != row.action) {
            throw new AssertionError("row " + title);
        }
    }
}
