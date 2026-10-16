package dev.phonestation.adbkeep;

final class HelperPinsTest {
    public static void main(String[] args) {
        HelperPins pins = new HelperPins();
        expect(HelperPins.CLIPBOARD.equals(pins.acquire(HelperPins.CLIPBOARD)), "first hold starts the process");
        expect(pins.acquire(HelperPins.CLIPBOARD) == null, "the same hold is not counted twice");
        pins.acquired();
        expect(pins.acquire(HelperPins.SHELL) == null, "a live process absorbs another hold");
        expect(pins.release(HelperPins.SHELL) == null, "one remaining hold keeps the process");
        expect(HelperPins.CLIPBOARD.equals(pins.release(HelperPins.CLIPBOARD)), "the last hold stops with the starter");
        expect(!pins.up(), "process is down");

        expect(HelperPins.CLIPBOARD.equals(pins.acquire(HelperPins.CLIPBOARD)), "a released start can be replaced");
        expect(pins.release(HelperPins.CLIPBOARD) == null && !pins.up(), "release before start leaves the process down");
        expect(HelperPins.SHELL.equals(pins.acquire(HelperPins.SHELL)), "shell can start the next process");
        pins.abort(HelperPins.SHELL);
        expect(!pins.up() && pins.release(HelperPins.SHELL) == null, "a failed bind leaves nothing to stop");

        expect(HelperPins.TORCH.equals(pins.acquire(HelperPins.TORCH)), "torch starts when nothing else is up");
        pins.acquired();
        pins.acquire(HelperPins.SHELL);
        expect(!pins.shellAlone() && pins.heldByOthers(HelperPins.SHELL), "torch keeps the process beside shell");
        expect(pins.release(HelperPins.TORCH) == null, "shell remains");
        expect(pins.shellAlone(), "shell is alone after torch leaves");
        expect(HelperPins.TORCH.equals(pins.release(HelperPins.SHELL)), "stop name stays with whoever started it");
        System.out.println("helper pins ok");
    }

    private static void expect(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
