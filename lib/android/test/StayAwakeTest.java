package dev.phonestation.adbkeep;

final class StayAwakeTest {
    public static void main(String[] args) {
        expect(true, StayAwake.held(StayAwake.ON_TIMEOUT, StayAwake.ON_PLUGGED));
        expect(false, StayAwake.held(StayAwake.OFF_TIMEOUT, StayAwake.ON_PLUGGED));
        expect(false, StayAwake.held(StayAwake.ON_TIMEOUT, StayAwake.OFF_PLUGGED));
        expect(StayAwake.ON_TIMEOUT, StayAwake.timeout(true));
        expect(StayAwake.OFF_TIMEOUT, StayAwake.timeout(false));
        expect(StayAwake.ON_PLUGGED, StayAwake.plugged(true));
        expect(StayAwake.OFF_PLUGGED, StayAwake.plugged(false));
        expect("image/jpeg", FileTypes.mime("Shot.JPG"));
        expect("text/plain", FileTypes.mime("note.txt"));
        expect("application/octet-stream", FileTypes.mime("note"));
        System.out.println("StayAwakeTest ok");
    }

    private static void expect(boolean want, boolean got) {
        if (want != got) {
            throw new AssertionError("want " + want + " got " + got);
        }
    }

    private static void expect(int want, int got) {
        if (want != got) {
            throw new AssertionError("want " + want + " got " + got);
        }
    }

    private static void expect(String want, String got) {
        if (!want.equals(got)) {
            throw new AssertionError("want " + want + " got " + got);
        }
    }
}
