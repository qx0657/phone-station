package dev.phonestation.adbkeep;

/**
 * 「保持无线调试」的状态。没有 Android 依赖，构建时在电脑上跑测试。
 * 屏幕上平时只看开关和三行状态。{@link #notice()} 只在这三行说不清时有字。
 * 常驻通知用这里的短句，不把过程说明写进下拉栏。
 *
 * <p>优先级：没有写入权限，保持关着，无线调试正开着，USB 调试关着，
 * 没有 Wi-Fi，正在退避，马上写。只取命中的第一句。
 */
final class KeeperCopy {
    enum Tone {
        NEUTRAL,
        HELD,
        WAITING
    }

    enum Emphasis {
        NONE,
        WIRELESS,
        USB,
        WIFI
    }

    final String headline;
    final String detail;
    final Tone tone;
    final Emphasis emphasis;
    final String wireless;
    final String usb;
    final String wifi;

    private KeeperCopy(
            String headline,
            String detail,
            Tone tone,
            Emphasis emphasis,
            boolean wifiAdbOn,
            boolean adbEnabled,
            boolean wifiConnected) {
        this.headline = headline;
        this.detail = detail;
        this.tone = tone;
        this.emphasis = emphasis;
        this.wireless = wifiAdbOn ? "开" : "关";
        this.usb = adbEnabled ? "开" : "关";
        this.wifi = wifiConnected ? "已连接" : "未连接";
    }

    static KeeperCopy present(
            boolean canWrite,
            boolean keeperOn,
            boolean adbEnabled,
            boolean wifiAdbOn,
            boolean wifiConnected,
            int failures,
            long sinceLastAttemptMs) {
        if (!canWrite) {
            return new KeeperCopy(
                    "还不能写这个开关",
                    "在电脑上运行一次安装。",
                    Tone.NEUTRAL,
                    Emphasis.NONE,
                    wifiAdbOn,
                    adbEnabled,
                    wifiConnected);
        }
        if (!keeperOn) {
            return row("保持已停下", Tone.NEUTRAL, Emphasis.NONE, wifiAdbOn, adbEnabled, wifiConnected);
        }
        if (wifiAdbOn) {
            return row("无线调试开着", Tone.HELD, Emphasis.WIRELESS, wifiAdbOn, adbEnabled, wifiConnected);
        }
        if (!adbEnabled) {
            return row("USB 调试关着", Tone.NEUTRAL, Emphasis.USB, wifiAdbOn, adbEnabled, wifiConnected);
        }
        if (!wifiConnected) {
            return row("等 Wi-Fi", Tone.NEUTRAL, Emphasis.WIFI, wifiAdbOn, adbEnabled, wifiConnected);
        }
        long delay = KeeperPolicy.enableDelayMs(
                true, true, false, true, failures, sinceLastAttemptMs);
        if (delay > 0L) {
            return new KeeperCopy(
                    "先等一会儿",
                    retryPhrase(delay),
                    Tone.WAITING,
                    Emphasis.WIRELESS,
                    wifiAdbOn,
                    adbEnabled,
                    wifiConnected);
        }
        return row("正在打开", Tone.NEUTRAL, Emphasis.WIRELESS, wifiAdbOn, adbEnabled, wifiConnected);
    }

    /** 界面上多出来的一句。无线、USB、Wi-Fi 三行已经能看出来时是空的。 */
    String notice() {
        if ("还不能写这个开关".equals(headline) || "先等一会儿".equals(headline)) {
            return headline + "\n" + detail;
        }
        if ("正在打开".equals(headline)) {
            return headline;
        }
        return "";
    }

    private static KeeperCopy row(
            String headline,
            Tone tone,
            Emphasis emphasis,
            boolean wifiAdbOn,
            boolean adbEnabled,
            boolean wifiConnected) {
        return new KeeperCopy(headline, "", tone, emphasis, wifiAdbOn, adbEnabled, wifiConnected);
    }

    /** 退避还剩这么久时，界面上第二行怎么说。 */
    static String retryPhrase(long remainingMs) {
        if (remainingMs < 5_000L) {
            return "马上再试。";
        }
        if (remainingMs < 60_000L) {
            return "不到一分钟后再试。";
        }
        long minutes = (remainingMs + 59_999L) / 60_000L;
        return minutes + " 分钟后再试。";
    }
}
