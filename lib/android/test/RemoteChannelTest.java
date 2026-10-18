package dev.phonestation.adbkeep;

final class RemoteChannelTest {
    public static void main(String[] args) {
        String legacy = "连接失败 · 重试中";
        if (!RemoteChannel.connected(true, true, true, false)
                || !"已连接".equals(RemoteChannel.label(true, true, true, true, true, legacy))
                || RemoteChannel.checking(true, true, true)) {
            throw new AssertionError("online link replaces the token client");
        }
        if (RemoteChannel.connected(true, true, false, true)
                || !"连接中".equals(RemoteChannel.label(true, true, true, true, false, legacy))
                || RemoteChannel.checking(true, true, true)) {
            throw new AssertionError("link dialing is not a token failure");
        }
        if (!RemoteChannel.connected(true, false, false, true)
                || !legacy.equals(RemoteChannel.label(true, true, true, false, false, legacy))
                || !RemoteChannel.checking(true, false, true)) {
            throw new AssertionError("legacy client remains when no link session started");
        }
        if (RemoteChannel.connected(false, true, true, true)
                || !"已关闭".equals(RemoteChannel.label(true, true, false, true, true, legacy))
                || !"未配置".equals(RemoteChannel.label(true, false, true, true, true, legacy))
                || !"已暂停".equals(RemoteChannel.label(false, true, true, true, true, legacy))) {
            throw new AssertionError("switch and setup still come first");
        }
        System.out.println("RemoteChannelTest ok");
    }
}
