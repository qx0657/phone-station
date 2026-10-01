package dev.phonestation.adbkeep;

final class KeeperPolicyTest {
    public static void main(String[] args) {
        expect(-1L, KeeperPolicy.enableDelayMs(false, true, false, true, 0, 0L));
        expect(-1L, KeeperPolicy.enableDelayMs(true, false, false, true, 0, 0L));
        expect(-1L, KeeperPolicy.enableDelayMs(true, true, true, true, 0, 0L));
        expect(-1L, KeeperPolicy.enableDelayMs(true, true, false, false, 0, 0L));
        expect(0L, KeeperPolicy.enableDelayMs(true, true, false, true, 0, 0L));
        expect(0L, KeeperPolicy.enableDelayMs(true, true, false, true, 0, Long.MAX_VALUE));
        expect(2_000L, KeeperPolicy.enableDelayMs(true, true, false, true, 1, 1_000L));
        expect(0L, KeeperPolicy.enableDelayMs(true, true, false, true, 1, 3_000L));
        expect(300_000L, KeeperPolicy.enableDelayMs(true, true, false, true, 100, 0L));
        expect(0L, KeeperPolicy.enableDelayMs(true, true, false, true, 4, 300_000L));
        expect(0L, KeeperPolicy.enableDelayMs(true, true, false, true, -1, 0L));
        System.out.println("KeeperPolicyTest ok");
    }

    private static void expect(long want, long got) {
        if (want != got) {
            throw new AssertionError("want " + want + " got " + got);
        }
    }
}
