package dev.phonestation.adbkeep;

final class RelayConnectionTest {
    public static void main(String[] args) {
        RelayConnection state = new RelayConnection();
        if (state.connected(1L) || !"连接中".equals(state.label(1L))) { throw new AssertionError("initial"); }
        state.confirmed(1000L);
        if (!state.connected(7000L) || state.expiresIn(7000L) != 1L) { throw new AssertionError("fresh boundary"); }
        if (state.connected(7001L) || !state.checking(7001L)
                || !"确认连接中".equals(state.label(7001L))) { throw new AssertionError("stale response is not online"); }
        state.confirmed(7100L);
        if (!state.connected(7101L)) { throw new AssertionError("result delivery confirms recovery"); }
        state.waiting("连接失败 · 重试中");
        if (state.connected(7102L) || state.checking(7102L)) { throw new AssertionError("failure invalidates immediately"); }
        if (!"连接失败 · 重试中".equals(state.label(7102L))) { throw new AssertionError("failure copy"); }
        state.waiting("认证失败 · 请检查令牌");
        if (!state.label(99999L).contains("认证失败")) { throw new AssertionError("auth failure persists until response"); }
        System.out.println("RelayConnectionTest ok");
    }
}
