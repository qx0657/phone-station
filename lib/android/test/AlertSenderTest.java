package dev.phonestation.adbkeep;

final class AlertSenderTest {
    public static void main(String[] args) {
        int app = 10234;
        expect(true, AlertSender.allowed(AlertSender.SHELL_UID, app, 0));
        expect(true, AlertSender.allowed(app, app, 0));
        expect(false, AlertSender.allowed(10111, app, 0));
        expect(false, AlertSender.allowed(-1, app, 0));
        expect(false, AlertSender.allowed(0, app, 0));
        expect(false, AlertSender.allowed(app, 0, 0));
        expect(true, AlertSender.allowed(AlertSender.SHELL_UID, 0, 0));
        expect(true, AlertSender.allowed(-1, app, AlertSender.FROM_SHELL));
        expect(false, AlertSender.allowed(10111, app, AlertSender.FROM_SHELL << 1));
        System.out.println("AlertSenderTest ok");
    }

    private static void expect(boolean want, boolean got) {
        if (want != got) {
            throw new AssertionError("want " + want + " got " + got);
        }
    }
}
