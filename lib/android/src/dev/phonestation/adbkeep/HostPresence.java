package dev.phonestation.adbkeep;

import android.os.SystemClock;

/** 已认证的本地 MCP 请求记在内存里，当作电脑还连着。不写系统设置。 */
final class HostPresence {
    private static final Object LOCK = new Object();
    private static long seenElapsed = -1L;

    private HostPresence() {}

    static void noteLocal() {
        long now = SystemClock.elapsedRealtime();
        boolean rising;
        synchronized (LOCK) {
            rising = !HostLink.freshElapsed(seenElapsed, now);
            seenElapsed = now;
        }
        if (rising) {
            StationNotifications.connectionChanged();
        } else {
            StationNotifications.touchFreshness();
        }
    }

    static boolean fresh() {
        synchronized (LOCK) {
            return HostLink.freshElapsed(seenElapsed, SystemClock.elapsedRealtime());
        }
    }

    /** 还新鲜时，距离过期还要等多久。已经过期返回 -1。 */
    static long untilStale() {
        synchronized (LOCK) {
            return HostLink.untilStale(seenElapsed, SystemClock.elapsedRealtime());
        }
    }
}
