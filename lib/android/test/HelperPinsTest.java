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

        expect(HelperPins.TERMINAL.equals(pins.acquire(HelperPins.TERMINAL)), "terminal starts when nothing else is up");
        pins.acquired();
        pins.acquire(HelperPins.TORCH);
        expect(pins.release(HelperPins.TERMINAL) == null && pins.holds(HelperPins.TORCH) && pins.up(),
                "ending terminal leaves the light held");
        expect(HelperPins.TERMINAL.equals(pins.release(HelperPins.TORCH)), "stop name stays with the terminal that started it");

        expect(HelperPins.SCREEN.equals(pins.acquire(HelperPins.SCREEN)), "screen can start the process");
        pins.acquired();
        pins.acquire(HelperPins.CLIPBOARD);
        pins.acquire(HelperPins.SHELL);
        expect(!pins.shellAlone(), "screen and clipboard keep shell from idling alone");
        expect(pins.release(HelperPins.SHELL) == null, "shell leaves while screen remains");
        expect(pins.release(HelperPins.SCREEN) == null && pins.holds(HelperPins.CLIPBOARD),
                "clipboard keeps the process after screen ends");
        expect(HelperPins.SCREEN.equals(pins.release(HelperPins.CLIPBOARD)), "stop name stays with the screen that started it");

        expect(HelperPins.TORCH.equals(pins.acquire(HelperPins.TORCH)), "torch starts the next process");
        pins.acquired();
        pins.acquire(HelperPins.TERMINAL);
        pins.abort(HelperPins.TERMINAL);
        expect(pins.up() && pins.holds(HelperPins.TORCH) && !pins.holds(HelperPins.TERMINAL),
                "a failed terminal bind leaves the light");
        expect(HelperPins.TORCH.equals(pins.release(HelperPins.TORCH)), "torch still stops the process it started");
        System.out.println("helper pins ok");
    }

    private static void expect(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
