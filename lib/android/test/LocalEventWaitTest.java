package dev.phonestation.adbkeep;

public final class LocalEventWaitTest {
    public static void main(String[] args) {
        LocalEventWait.note("clipboard");
        Json changed = LocalEventWait.await(0, 0, 1_000);
        expect(changed.get("clipboard").longValue() == 1, "clipboard change is visible");
        expect(changed.get("notifications").longValue() == 0, "notifications stay at zero");
        long started = System.nanoTime();
        Json quiet = LocalEventWait.await(1, 0, 40);
        long elapsed = System.nanoTime() - started;
        expect(quiet.get("clipboard").longValue() == 1 && quiet.get("notifications").longValue() == 0, "timeout keeps the counters");
        expect(elapsed >= 20_000_000L, "unchanged wait blocks");
        LocalEventWait.note("notifications");
        Json notes = LocalEventWait.await(1, 0, 1_000);
        expect(notes.get("notifications").longValue() == 1, "notification change wakes the wait");
        LocalEventWait.note("other");
        Json ignored = LocalEventWait.await(notes.get("clipboard").longValue(), notes.get("notifications").longValue(), 20);
        expect(ignored.get("clipboard").longValue() == notes.get("clipboard").longValue()
                && ignored.get("notifications").longValue() == notes.get("notifications").longValue(),
                "unknown topics do not count");
        System.out.println("local event wait ok");
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
