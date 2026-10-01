package dev.phonestation.adbkeep;

/**
 * 和 {@code stay-awake.sh} 写的是同一对值。
 * 开着：息屏 2147483647 毫秒，充电时常亮的掩码是 7（交流电、USB、无线充）。
 * 关掉：息屏 60 秒，充电掩码 0。
 */
final class StayAwake {
    static final int ON_TIMEOUT = 2147483647;
    static final int OFF_TIMEOUT = 60000;
    static final int ON_PLUGGED = 7;
    static final int OFF_PLUGGED = 0;

    private StayAwake() {}

    static boolean held(int timeout, int plugged) {
        return timeout == ON_TIMEOUT && plugged == ON_PLUGGED;
    }

    static int timeout(boolean on) {
        return on ? ON_TIMEOUT : OFF_TIMEOUT;
    }

    static int plugged(boolean on) {
        return on ? ON_PLUGGED : OFF_PLUGGED;
    }
}
