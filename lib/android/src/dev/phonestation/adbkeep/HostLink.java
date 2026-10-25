package dev.phonestation.adbkeep;

/**
 * 电脑有没有连上。没有 Android 依赖，构建时在电脑上跑测试。
 *
 * <p>正在使用的本地心跳是 {@link #freshElapsed}：本地链路上已认证的请求记下开机后时间，
 * {@link #FRESH_MS} 内算在线。{@link #SETTING} 上的墙钟比较留在这里，供原来的边界测试；
 * 手机不再读取这两个系统设置。
 */
final class HostLink {
    /** {@code settings global} 的键。值是手机本地时间的毫秒，0 表示电脑明确写成未连接。 */
    static final String SETTING = "phonestation_host_ms";
    static final String TRANSPORT_SETTING = "phonestation_host_transport";

    /** 超过这么久没有新的已认证请求，就不再算本地在线。网关大约每秒探测一次。 */
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

    /** {@code seenElapsed < 0} 表示还没有过已认证请求。时钟往回走不算在线。 */
    static boolean freshElapsed(long seenElapsed, long nowElapsed) {
        return seenElapsed >= 0L && nowElapsed >= seenElapsed && nowElapsed - seenElapsed <= FRESH_MS;
    }

    /** 还新鲜时，距离过期还要等多久。已经过期返回 -1。多 1 毫秒，避免刚好卡在边界。 */
    static long untilStale(long seenElapsed, long nowElapsed) {
        if (!freshElapsed(seenElapsed, nowElapsed)) {
            return -1L;
        }
        return FRESH_MS - (nowElapsed - seenElapsed) + 1L;
    }

    static String headline(boolean linked) {
        return linked ? "已连接" : "未连接";
    }

    /**
     * 本地通道名称。没连上时不把空传输写成「无线」，避免电脑的 adb 无线会话
     * 被手机显示成「无线未连接」。连上之后，无线调试开着才标成无线，否则是 USB。
     */
    static String localTransport(boolean linked, String reported, boolean adbWifi) {
        if (!linked) {
            return "";
        }
        if ("wireless".equals(reported) || "usb".equals(reported)) {
            return reported;
        }
        return adbWifi ? "wireless" : "usb";
    }

    static String localName(String transport) {
        if ("usb".equals(transport)) {
            return "USB";
        }
        if ("wireless".equals(transport)) {
            return "无线";
        }
        return "本地";
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
