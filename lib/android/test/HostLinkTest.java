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
        System.out.println("HostLinkTest ok");
    }
}
