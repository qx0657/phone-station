package dev.phonestation.adbkeep;

public final class InstallRecoveryTest {
    public static void main(String[] args) {
        String first = "a".repeat(32);
        String second = "b".repeat(32);
        check(!InstallRecovery.serviceFor(first) && !InstallRecovery.activityFor(first));
        InstallRecovery.service(first);
        check(InstallRecovery.serviceFor(first) && !InstallRecovery.activityFor(first));
        InstallRecovery.activity(second);
        check(!InstallRecovery.activityFor(first) && InstallRecovery.activityFor(second));
        for (String invalid : new String[] {null, "", "../install", "A".repeat(32), "a".repeat(31), "g".repeat(32)}) {
            InstallRecovery.service(invalid);
            InstallRecovery.activity(invalid);
            check(!InstallRecovery.serviceFor(invalid) && !InstallRecovery.activityFor(invalid));
        }
        check(InstallRecovery.serviceFor(first) && InstallRecovery.activityFor(second));
        InstallRecovery.service(second);
        check(!InstallRecovery.serviceFor(first) && InstallRecovery.serviceFor(second));
        System.out.println("InstallRecoveryTest ok");
    }

    private static void check(boolean value) { if (!value) { throw new AssertionError(); } }
}
