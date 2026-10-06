package dev.phonestation.adbkeep;

/** Only a recent successful relay response confirms connectivity; use monotonic time. */
final class RelayConnection {
    static final long FRESH_MS = 6_000L;
    private long freshness = FRESH_MS;
    private boolean online;
    private long confirmedAt;
    private String phase = "连接中";

    synchronized void confirmed(long now) {
        confirmed(now, FRESH_MS);
    }

    synchronized void confirmed(long now, long freshness) {
        this.freshness = freshness;
        online = true;
        confirmedAt = now;
        phase = "已连接";
    }

    synchronized void waiting(String reason) {
        online = false;
        phase = reason;
    }

    synchronized boolean connected(long now) {
        return online && now >= confirmedAt && now - confirmedAt <= freshness;
    }

    synchronized boolean checking(long now) { return online && !connected(now); }

    synchronized String label(long now) {
        return checking(now) ? "确认连接中" : phase;
    }

    synchronized long expiresIn(long now) {
        return connected(now) ? Math.max(1L, confirmedAt + freshness + 1L - now) : -1L;
    }
}
