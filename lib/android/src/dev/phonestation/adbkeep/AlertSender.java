package dev.phonestation.adbkeep;

/**
 * 谁可以让手机工位发会话提醒，或开关 MCP。没有 Android 依赖，构建时在电脑上跑测试。
 *
 * <p>shell 的 uid 是 2000。Android 14 起，发送方不公开身份时 {@code getSentFromUid()} 是 -1，
 * {@code am broadcast} 就是这样。它会带上 {@link #FROM_SHELL}，系统会把别人加上的这一位去掉。
 * 应用自己发 {@code station_notify} 时会公开身份，于是 uid 对得上。
 */
final class AlertSender {
    static final int SHELL_UID = 2000;
    /** {@code Intent.FLAG_RECEIVER_FROM_SHELL}。公开 SDK 里没有这个常量。 */
    static final int FROM_SHELL = 0x00400000;

    private AlertSender() {}

    static boolean allowed(int sentFromUid, int appUid, int intentFlags) {
        if (sentFromUid == SHELL_UID) {
            return true;
        }
        if (appUid > 0 && sentFromUid == appUid) {
            return true;
        }
        return (intentFlags & FROM_SHELL) != 0;
    }
}
