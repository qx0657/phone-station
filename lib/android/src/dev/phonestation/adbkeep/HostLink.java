package dev.phonestation.adbkeep;

/**
 * 电脑有没有连上。没有 Android 依赖，构建时在电脑上跑测试。
 *
 * <p>adb 连着时，电脑把 {@link #SETTING} 写成手机当时的时间。这个时间还新，或远程通道在线，
 * 就显示已连接。adb 断开后写不进去，过了 {@link #FRESH_MS} 就不再把 adb 算作在线。
 * 不看系统那条「已连接到无线调试」：它可以被划掉或全部清除，划掉之后会话还可以在。
 */
final class HostLink {
    /** {@code settings global} 的键。值是手机本地时间的毫秒，0 表示电脑明确写成未连接。 */
    static final String SETTING = "phonestation_host_ms";
    static final String TRANSPORT_SETTING = "phonestation_host_transport";

    /** 超过这么久没有新的写入，就不再算连着。电脑大约每 2 秒写一次。 */
    static final long FRESH_MS = 6_000L;

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

    static boolean linked(long markedAtMs, long nowMs, boolean remoteConnected) {
        return remoteConnected || linked(markedAtMs, nowMs);
    }

    static boolean localLinked(long markedAtMs, long nowMs, String transport,
                               boolean wifiConnected, boolean wirelessEnabled) {
        return linked(markedAtMs, nowMs)
                && (!"wireless".equals(transport) || (wifiConnected && wirelessEnabled));
    }

    static String headline(boolean linked) {
        return linked ? "已连接" : "未连接";
    }

    static String connectionType(long markedAtMs, long nowMs, String adbTransport, boolean remoteConnected) {
        String local = "";
        if (linked(markedAtMs, nowMs)) {
            local = "usb".equals(adbTransport) ? "USB 连接"
                    : "wireless".equals(adbTransport) ? "无线连接" : "adb 连接";
        }
        if (!remoteConnected) {
            return local;
        }
        return local.isEmpty() ? "远程连接" : local + " · 远程连接";
    }
}
