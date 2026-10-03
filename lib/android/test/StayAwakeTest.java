package dev.phonestation.adbkeep;

final class StayAwakeTest {
    public static void main(String[] args) {
        roundTrips();
        manualChanges();
        failures();
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

    static final class Memory implements StayAwake.Settings, StayAwake.Store {
        String timeout, plugged;
        StayAwake.Snapshot snapshot;
        boolean failSave, failEnable, failRestore;
        int writes;
        Memory(String timeout, String plugged) { this.timeout = timeout; this.plugged = plugged; }
        public String timeout() { return timeout; }
        public String plugged() { return plugged; }
        public boolean timeout(String value) {
            writes++;
            if (failRestore && !"2147483647".equals(value)) { return false; }
            timeout = value; return true;
        }
        public boolean plugged(String value) {
            writes++;
            if (failEnable && "7".equals(value)) { return false; }
            plugged = value; return true;
        }
        public StayAwake.Snapshot load() { return snapshot; }
        public void save(StayAwake.Snapshot value) {
            if (failSave) { throw new IllegalStateException("disk full"); }
            snapshot = value;
        }
        public void clear() { snapshot = null; }
        void set(boolean on) { StayAwake.apply(this, this, on); }
    }

    private static void roundTrips() {
        for (String timeout : new String[] { "30000", "1800000", null }) {
            Memory m = new Memory(timeout, "3");
            m.set(true); m.set(true);
            // A new controller/process uses the same durable snapshot.
            Memory restarted = new Memory(m.timeout, m.plugged);
            restarted.snapshot = m.snapshot;
            restarted.set(false); restarted.set(false);
            expect(timeout, restarted.timeout); expect("3", restarted.plugged);
            expect(true, restarted.snapshot == null);
        }
        Memory absent = new Memory(null, null);
        absent.set(true); absent.set(false);
        expect(null, absent.timeout); expect(null, absent.plugged);
        Memory legacy = new Memory("2147483647", "7");
        legacy.set(false);
        expect("60000", legacy.timeout); expect("0", legacy.plugged);
        Memory untouched = new Memory("3600000", "3");
        untouched.set(false);
        expect(0, untouched.writes);
    }

    private static void manualChanges() {
        Memory m = new Memory("30000", "1");
        m.set(true);
        m.timeout = "120000";
        m.set(false);
        expect("120000", m.timeout); expect("1", m.plugged);
        m.set(true); m.plugged = "3"; m.set(true); m.set(false);
        expect("120000", m.timeout); expect("3", m.plugged);
    }

    private static void failures() {
        Memory m = new Memory("30000", "1");
        m.failSave = true;
        fails(() -> m.set(true)); expect(0, m.writes);
        m.failSave = false; m.failEnable = true;
        fails(() -> m.set(true));
        expect("30000", m.timeout); expect("1", m.plugged);
        expect(true, m.snapshot == null);
        m.failRestore = true;
        fails(() -> m.set(true)); expect(true, m.snapshot != null);
        m.failRestore = false; m.set(false);
        expect("30000", m.timeout); expect("1", m.plugged);
        expect(true, m.snapshot == null);
        m.failEnable = false; m.set(true); m.failRestore = true;
        fails(() -> m.set(false)); expect(true, m.snapshot != null);
        m.failRestore = false; m.set(false);
        expect("30000", m.timeout); expect("1", m.plugged);
    }

    private static void fails(Runnable action) {
        try { action.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError("expected failure");
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
        if (!java.util.Objects.equals(want, got)) {
            throw new AssertionError("want " + want + " got " + got);
        }
    }
}
