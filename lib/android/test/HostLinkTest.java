package dev.phonestation.adbkeep;

final class HostLinkTest {
    public static void main(String[] args) {
        long now = 1_700_000_000_000L;
        if (!HostLink.linked(now, now)
                || !HostLink.linked(now - HostLink.FRESH_MS, now)
                || HostLink.linked(now - HostLink.FRESH_MS - 1L, now)
                || !HostLink.linked(now + 2_000L, now)
                || HostLink.linked(now + 2_001L, now)
                || HostLink.linked(0L, now)
                || HostLink.linked(-1L, now)) {
            throw new AssertionError("freshness");
        }
        if (!"已连接".equals(HostLink.headline(true))
                || !"未连接".equals(HostLink.headline(false))) {
            throw new AssertionError("copy");
        }
        if (!HostLink.linked(0L, now, true)
                || !HostLink.linked(now - HostLink.FRESH_MS - 1L, now, true)
                || !HostLink.linked(now, now, false)
                || !HostLink.linked(now, now, true)
                || HostLink.linked(0L, now, false)
                || HostLink.linked(now - HostLink.FRESH_MS - 1L, now, false)) {
            throw new AssertionError("adb and remote fallback");
        }
        if (!"远程连接".equals(HostLink.connectionType(0L, now, "wireless", true))
                || !"远程连接".equals(HostLink.connectionType(now - HostLink.FRESH_MS - 1L, now, "usb", true))
                || !"无线连接 · 远程连接".equals(HostLink.connectionType(now, now, "wireless", true))
                || !"USB 连接".equals(HostLink.connectionType(now, now, "usb", false))
                || !"adb 连接".equals(HostLink.connectionType(now, now, null, false))
                || !"".equals(HostLink.connectionType(0L, now, "usb", false))) {
            throw new AssertionError("connection type follows live transports");
        }
        System.out.println("HostLinkTest ok");
        if (HostLink.localLinked(now, now, "wireless", false, true)
                || HostLink.localLinked(now, now, "wireless", true, false)
                || !HostLink.localLinked(now, now, "wireless", true, true)
                || !HostLink.localLinked(now, now, "usb", false, false)) {
            throw new AssertionError("Wi-Fi loss invalidates wireless heartbeat immediately, preserving USB");
        }
    }
}
