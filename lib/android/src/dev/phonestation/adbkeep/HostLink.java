package dev.phonestation.adbkeep;

/**
 * 电脑有没有连上。没有 Android 依赖，构建时在电脑上跑测试。
 *
 * <p>电脑连着时，把 {@link #SETTING} 写成手机当时的时间。手机读到这个时间还新，就显示已连接。
 * 断开之后电脑写不进去，过了 {@link #FRESH_MS} 就不再显示已连接。
 * 不看系统那条「已连接到无线调试」：它可以被划掉或全部清除，划掉之后会话还可以在。
 */
final class HostLink {
    /** {@code settings global} 的键。值是手机本地时间的毫秒，0 表示电脑明确写成未连接。 */
    static final String SETTING = "phonestation_host_ms";

    /** 超过这么久没有新的写入，就不再算连着。电脑大约每 5 秒写一次。 */
    static final long FRESH_MS = 15_000L;

    private HostLink() {}

    /**
     * {@code markedAtMs} 是配置里的时间，{@code nowMs} 是手机现在。
     * 略超前两秒仍算连着，避免刚写完的同一秒被看成未来。
     */
    static boolean linked(long markedAtMs, long nowMs) {
        if (markedAtMs <= 0L) {
            return false;
        }
        long age = nowMs - markedAtMs;
        return age >= -2_000L && age <= FRESH_MS;
    }

    static String headline(boolean linked) {
        return linked ? "已连接" : "未连接";
    }
}
