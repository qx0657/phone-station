package dev.phonestation.adbkeep;

/**
 * 何时把无线调试写回打开。没有 Android 依赖，构建时在电脑上跑测试。
 *
 * <p>系统会在网络不可信时立刻把开关拨回关。失败次数对应的等待用来避开来回写。
 */
final class KeeperPolicy {
    static final long[] BACKOFF_MS = {0L, 3_000L, 15_000L, 60_000L, 300_000L};
    static final long CONFIRM_MS = 1_500L;
    static final long INTERVAL_MS = 15 * 60 * 1000L;

    private KeeperPolicy() {}

    /**
     * @return 0 表示现在就写；正数表示还要等这么久；-1 表示这次不写
     */
    static long enableDelayMs(
            boolean keeperOn,
            boolean adbEnabled,
            boolean wifiAdbOn,
            boolean wifiConnected,
            int failures,
            long sinceLastAttemptMs) {
        if (!keeperOn || !adbEnabled || wifiAdbOn || !wifiConnected) {
            return -1L;
        }
        if (sinceLastAttemptMs < 0L) {
            sinceLastAttemptMs = 0L;
        }
        int index = failures;
        if (index < 0) {
            index = 0;
        }
        if (index >= BACKOFF_MS.length) {
            index = BACKOFF_MS.length - 1;
        }
        long wait = BACKOFF_MS[index];
        if (sinceLastAttemptMs >= wait) {
            return 0L;
        }
        return wait - sinceLastAttemptMs;
    }
}
