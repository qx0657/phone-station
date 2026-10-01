package dev.phonestation.adbkeep;

final class KeeperCopyTest {
    public static void main(String[] args) {
        expect(
                "还不能写这个开关",
                "在电脑上运行一次安装。",
                "还不能写这个开关\n在电脑上运行一次安装。",
                KeeperCopy.Tone.NEUTRAL,
                KeeperCopy.Emphasis.NONE,
                KeeperCopy.present(false, false, true, true, true, 0, Long.MAX_VALUE));
        expect(
                "保持已停下",
                "",
                "",
                KeeperCopy.Tone.NEUTRAL,
                KeeperCopy.Emphasis.NONE,
                KeeperCopy.present(true, false, true, true, true, 0, Long.MAX_VALUE));
        expect(
                "无线调试开着",
                "",
                "",
                KeeperCopy.Tone.HELD,
                KeeperCopy.Emphasis.WIRELESS,
                KeeperCopy.present(true, true, true, true, true, 0, Long.MAX_VALUE));
        expect(
                "无线调试开着",
                "",
                "",
                KeeperCopy.Tone.HELD,
                KeeperCopy.Emphasis.WIRELESS,
                KeeperCopy.present(true, true, false, true, false, 0, Long.MAX_VALUE));
        expect(
                "USB 调试关着",
                "",
                "",
                KeeperCopy.Tone.NEUTRAL,
                KeeperCopy.Emphasis.USB,
                KeeperCopy.present(true, true, false, false, true, 0, Long.MAX_VALUE));
        expect(
                "等 Wi-Fi",
                "",
                "",
                KeeperCopy.Tone.NEUTRAL,
                KeeperCopy.Emphasis.WIFI,
                KeeperCopy.present(true, true, true, false, false, 0, Long.MAX_VALUE));
        expect(
                "先等一会儿",
                "马上再试。",
                "先等一会儿\n马上再试。",
                KeeperCopy.Tone.WAITING,
                KeeperCopy.Emphasis.WIRELESS,
                KeeperCopy.present(true, true, true, false, true, 1, 0L));
        expect(
                "先等一会儿",
                "不到一分钟后再试。",
                "先等一会儿\n不到一分钟后再试。",
                KeeperCopy.Tone.WAITING,
                KeeperCopy.Emphasis.WIRELESS,
                KeeperCopy.present(true, true, true, false, true, 2, 0L));
        expect(
                "先等一会儿",
                "1 分钟后再试。",
                "先等一会儿\n1 分钟后再试。",
                KeeperCopy.Tone.WAITING,
                KeeperCopy.Emphasis.WIRELESS,
                KeeperCopy.present(true, true, true, false, true, 3, 0L));
        expect(
                "先等一会儿",
                "4 分钟后再试。",
                "先等一会儿\n4 分钟后再试。",
                KeeperCopy.Tone.WAITING,
                KeeperCopy.Emphasis.WIRELESS,
                KeeperCopy.present(true, true, true, false, true, 4, 60_000L));
        expect(
                "正在打开",
                "",
                "正在打开",
                KeeperCopy.Tone.NEUTRAL,
                KeeperCopy.Emphasis.WIRELESS,
                KeeperCopy.present(true, true, true, false, true, 0, Long.MAX_VALUE));
        KeeperCopy held = KeeperCopy.present(true, true, false, true, false, 0, Long.MAX_VALUE);
        if (!"开".equals(held.wireless) || !"关".equals(held.usb) || !"未连接".equals(held.wifi)) {
            throw new AssertionError("held facts");
        }
        KeeperCopy offline = KeeperCopy.present(true, true, true, false, false, 0, Long.MAX_VALUE);
        if (!"关".equals(offline.wireless) || !"开".equals(offline.usb) || !"未连接".equals(offline.wifi)) {
            throw new AssertionError("offline facts");
        }
        if (!"马上再试。".equals(KeeperCopy.retryPhrase(4_999L))
                || !"不到一分钟后再试。".equals(KeeperCopy.retryPhrase(5_000L))
                || !"1 分钟后再试。".equals(KeeperCopy.retryPhrase(60_000L))
                || !"5 分钟后再试。".equals(KeeperCopy.retryPhrase(300_000L))) {
            throw new AssertionError("retry phrase");
        }
        System.out.println("KeeperCopyTest ok");
    }

    private static void expect(
            String headline,
            String detail,
            String notice,
            KeeperCopy.Tone tone,
            KeeperCopy.Emphasis emphasis,
            KeeperCopy got) {
        if (got.wireless == null || got.usb == null || got.wifi == null
                || (!"开".equals(got.wireless) && !"关".equals(got.wireless))
                || (!"开".equals(got.usb) && !"关".equals(got.usb))
                || (!"已连接".equals(got.wifi) && !"未连接".equals(got.wifi))) {
            throw new AssertionError("fact text");
        }
        if (!headline.equals(got.headline)
                || !detail.equals(got.detail)
                || !notice.equals(got.notice())
                || tone != got.tone
                || emphasis != got.emphasis) {
            throw new AssertionError(
                    "want " + headline + " / " + detail + " / " + notice + " / " + tone
                            + " / " + emphasis
                            + " got " + got.headline + " / " + got.detail + " / " + got.notice()
                            + " / " + got.tone + " / " + got.emphasis);
        }
    }
}
