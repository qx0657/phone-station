package dev.phonestation.adbkeep;

import java.util.Collections;

final class EventLeaseTest {
    public static void main(String[] args) {
        NotificationSyncState notes = new NotificationSyncState("station");
        notes.configure(true, Collections.singleton("app")); notes.access(true); notes.listener(true);
        check(!notes.lease("mac", 1000), "subscription must not start notification reception");
        Json initial = notes.poll("mac", null, 1000);
        check(notes.lease("mac", 1001), "established receiver lease");
        check(!notes.lease("different", 1002), "different client cannot renew");
        check(notes.accepts("app", 21000), "idle receiver remains online without polling");
        notes.posted("app", "key", "App", "title", "body", false, false, 21000);
        check(notes.nextEventExpiry(21001) == 19999, "content expiry uses event time, not lease renewal");
        check(notes.poll("mac", initial.get("cursor").string(), 22000).get("events").array().size() == 1, "original cursor returns event");
        check(notes.lease("mac", 30000), "ongoing subscription renews");
        check(notes.nextEventExpiry(41000) == -1, "renewal must not extend notification retention");
        notes.lease("", 41001);
        check(!notes.accepts("app", 47000), "stale subscription expires");
        check(!notes.lease("mac", 47000), "heartbeat cannot resurrect expired receiver");
        notes.poll("mac", null, 48000); notes.lease("mac", 48001); notes.lease("", 48002);
        check(!notes.accepts("app", 64000), "unsubscribe restores polling deadline");
        notes.poll("mac", null, 65000); notes.lease("mac", 65001); notes.access(false);
        check(!notes.accepts("app", 65002), "permission revocation overrides lease");

        ClipboardState clips = new ClipboardState();
        ClipboardState.Access access = new ClipboardState.Access() {
            String value = "phone";
            public ClipboardState.Clip read() { return new ClipboardState.Clip("text", value); }
            public void write(String text) { value = text; }
        };
        check(!clips.lease("mac", 1000), "subscription cannot establish clipboard baseline");
        Json args0 = Json.obj().put("clientId", "mac").put("macVersion", "1").put("macKind", "text").put("macText", "old");
        Json baseline = clips.exchange(access, args0, true, 1000);
        check(clips.lease("mac", 1001), "clipboard receiver lease");
        check(clips.snapshot(20000).get("macOnline").boolValue(), "idle Mac remains present");
        Json edit = Json.obj().put("clientId", "mac").put("macVersion", "2").put("macKind", "text").put("macText", "new")
                .put("phoneVersion", baseline.get("phoneVersion"));
        check(clips.exchange(access, edit, true, 21000).get("appliedMac").boolValue(), "new Mac copy after idle applies once");
        check(!clips.exchange(access, edit, true, 21001).get("appliedMac").boolValue(), "duplicate copy is not reapplied");
        check(!clips.snapshot(47000).get("macOnline").boolValue(), "clipboard lease expires");
        clips.clear(); check(!clips.lease("mac", 48000), "reset does not retain subscription");

        RelayConnection connection = new RelayConnection();
        connection.confirmed(1000, 45000);
        check(connection.connected(21000) && !connection.connected(46001), "socket freshness follows heartbeat window");
        connection.confirmed(47000);
        check(!connection.connected(53001), "legacy poll keeps original freshness");
        System.out.println("EventLeaseTest ok");
    }
    private static void check(boolean value, String message) { if (!value) { throw new AssertionError(message); } }
}
