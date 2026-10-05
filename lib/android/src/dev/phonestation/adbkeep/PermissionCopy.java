package dev.phonestation.adbkeep;

/**
 * 权限页每一行的字。没有 Android 依赖，构建时在电脑上跑测试。
 *
 * <p>读得到、又该开着却没开的，算进主界面的「还有 N 项」。自启动系统不让读，单独一行，不计入。
 * Shizuku 用于 shell 和剪贴板后台读取，不可用时同样计入首页待处理项。
 */
final class PermissionCopy {
    enum Tone {
        HELD,
        WAITING,
        NEUTRAL
    }

    enum Action {
        NONE,
        NOTIFY,
        CHANNEL,
        STORAGE,
        BATTERY,
        ALARM,
        STARTUP,
        SHIZUKU
    }

    enum ShizukuState {
        ABSENT,
        STOPPED,
        DENIED,
        GRANTED
    }

    static final class Row {
        final String title;
        final String value;
        final String hint;
        final Tone tone;
        final Action action;

        private Row(String title, String value, String hint, Tone tone, Action action) {
            this.title = title;
            this.value = value;
            this.hint = hint;
            this.tone = tone;
            this.action = action;
        }
    }

    static final class Board {
        final Row[] rows;
        final int missing;
        final String summary;
        final Tone summaryTone;

        private Board(Row[] rows) {
            this.rows = rows;
            int count = 0;
            for (Row row : rows) {
                if (row.tone == Tone.WAITING) {
                    count++;
                }
            }
            this.missing = count;
            if (count == 0) {
                this.summary = rows.length == 0 ? "无需处理" : "已允许";
                this.summaryTone = Tone.HELD;
            } else {
                this.summary = "还有 " + count + " 项";
                this.summaryTone = Tone.WAITING;
            }
        }
    }

    private PermissionCopy() {}

    static Board relevant(Board board, java.util.Set<String> required) {
        java.util.List<Row> rows = new java.util.ArrayList<>();
        for (Row row : board.rows) { if (required.contains(row.title)) { rows.add(row); } }
        return new Board(rows.toArray(new Row[0]));
    }

    static String impact(Row row) {
        switch (row.title) {
            case "写入系统设置": return "无线调试控制、自动保持和保持亮屏不可用。";
            case "所有文件访问": return "手机文件访问不可用。";
            case "通知": return "不弹出".equals(row.value)
                    ? "电脑提醒无法弹出横幅。" : "手机无法显示会话提醒和运行状态通知。";
            case "电池优化": return "后台连接和同步可能被系统中断。";
            case "精确闹钟": return "无线调试的后台自动保持可能延迟。";
            case "Shizuku": return "共享剪贴板和远程 shell 不可用。";
            default: return "";
        }
    }

    static Board present(
            boolean canWrite,
            boolean notifications,
            boolean banner,
            boolean storage,
            boolean battery,
            boolean alarm,
            boolean wantPopup) {
        return present(canWrite, notifications, banner, storage, battery, alarm, wantPopup, ShizukuState.GRANTED);
    }

    static Board present(
            boolean canWrite,
            boolean notifications,
            boolean banner,
            boolean storage,
            boolean battery,
            boolean alarm,
            boolean wantPopup,
            ShizukuState shizuku) {
        return new Board(new Row[] {
                canWrite
                        ? allowed("写入系统设置")
                        : missing("写入系统设置", "未允许", "在电脑上运行一次安装。", Action.NONE),
                notifyRow(notifications, banner, wantPopup),
                storage
                        ? allowed("所有文件访问")
                        : missing("所有文件访问", "未允许", "点这一行，允许所有文件访问。", Action.STORAGE),
                battery
                        ? granted("电池优化", "不受限")
                        : missing("电池优化", "受限", "点这一行，设为不受限制。", Action.BATTERY),
                alarm
                        ? allowed("精确闹钟")
                        : missing("精确闹钟", "未允许", "点这一行，允许闹钟和提醒。", Action.ALARM),
                shizukuRow(shizuku),
                new Row(
                        "自启动",
                        "去设置",
                        "到应用启动管理里允许自启动和后台活动。强行停止之后要再打开一次。",
                        Tone.NEUTRAL,
                        Action.STARTUP)
        });
    }

    private static Row shizukuRow(ShizukuState state) {
        switch (state) {
            case GRANTED:
                return granted("Shizuku", "已授权");
            case DENIED:
                return missing("Shizuku", "未授权", "点这一行，在 Shizuku 里允许。", Action.SHIZUKU);
            case STOPPED:
                return missing("Shizuku", "未启动", "在 Shizuku 里启动。重启后要再启动一次。", Action.SHIZUKU);
            case ABSENT:
            default:
                return missing("Shizuku", "未安装", "先安装 Shizuku。", Action.NONE);
        }
    }

    private static Row notifyRow(boolean notifications, boolean banner, boolean wantPopup) {
        if (!notifications) {
            return missing("通知", "未允许", "点这一行，允许通知。", Action.NOTIFY);
        }
        if (wantPopup && !banner) {
            return missing("通知", "不弹出", "点这一行，打开「提醒」的横幅。", Action.CHANNEL);
        }
        return allowed("通知");
    }

    private static Row allowed(String title) {
        return granted(title, "已允许");
    }

    private static Row granted(String title, String value) {
        return new Row(title, value, "", Tone.HELD, Action.NONE);
    }

    private static Row missing(String title, String value, String hint, Action action) {
        return new Row(title, value, hint, Tone.WAITING, action);
    }

    private static Row noted(String title, String value, String hint, Action action) {
        return new Row(title, value, hint, Tone.NEUTRAL, action);
    }
}
