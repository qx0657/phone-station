package dev.phonestation.adbkeep;

import java.util.Set;

final class NotificationSyncStateTest {
    public static void main(String[] args) {
        NotificationSyncState state = new NotificationSyncState("station");
        state.configure(true, Set.of("chat", "station"));
        expect(state.status(100).get("selectedCount").longValue() == 1, "exclude own app");
        expect(!state.accepts("chat", 100), "no system permission");
        expect(!state.allowsIcon("chat"), "icons need notification permission");
        state.access(true);
        expect(state.allowsIcon("chat") && !state.allowsIcon("other") && !state.allowsIcon("station"), "icon export only for selected apps");
        state.listener(true);
        expect(!state.accepts("chat", 100), "no receiver");
        String cursor = state.poll("mac", null, 100).get("cursor").string();
        expect(!state.accepts("other", 110) && !state.accepts("station", 110), "whitelist before content");
        post(state, "chat", "1", "message", 110);
        post(state, "other", "2", "private", 110);
        post(state, "station", "3", "loop", 110);
        state.posted("chat", "ongoing", "Chat", "title", "ongoing", true, false, 110);
        state.posted("chat", "summary", "Chat", "title", "summary", false, true, 110);
        post(state, "chat", "1", "message", 111);
        Json next = state.poll("mac", cursor, 120);
        expect(next.get("events").array().size() == 1, "skip unknown, own, ongoing, summaries and identical updates");
        cursor = next.get("cursor").string();
        post(state, "chat", "1", "new message", 130);
        Json update = state.poll("mac", cursor, 140);
        expect(update.get("events").array().size() == 1, "changed content should forward");
        expect(update.get("events").array().get(0).get("key").string().equals("1"), "retain key for replacement");
        cursor = update.get("cursor").string();
        post(state, "chat", "pending", "discard after unselect", 150);
        state.configure(true, Set.of("other"));
        expect(!state.allowsIcon("chat"), "unselect revokes icon export");
        expect(state.poll("mac", cursor, 160).get("events").array().isEmpty(), "unselection purges pending content");
        state.configure(true, Set.of("chat"));
        cursor = state.poll("mac", null, 200).get("cursor").string();
        post(state, "chat", "secret", "discard after revoke", 210);
        state.access(false);
        expect(!state.allowsIcon("chat"), "revocation stops icon export");
        expect(!state.accepts("chat", 220), "revocation stops collection");
        expect(state.poll("mac", cursor, 220).get("events").array().isEmpty(), "revocation purges content");
        state.access(true);
        cursor = state.poll("mac", null, 300).get("cursor").string();
        post(state, "chat", "offline", "discard", 301);
        expect(!state.accepts("chat", 15_301), "offline after lease expires");
        expect(state.poll("mac", cursor, 15_302).get("events").array().isEmpty(), "reconnect never replays old notifications");
        cursor = state.poll("mac", null, 16_000).get("cursor").string();
        for (int i = 0; i < 100; i++) { post(state, "chat", "key-" + i, "message-" + i, 16_100); }
        expect(state.poll("mac", cursor, 16_200).get("events").array().size() == NotificationSyncState.MAX_EVENTS, "bounded queue");
        Json baseline = state.poll("another-mac", cursor, 16_300);
        expect(baseline.get("events").array().isEmpty(), "new receiver gets no history");
        cursor = baseline.get("cursor").string();
        post(state, "chat", "disable", "discard", 16_400);
        state.configure(false, Set.of("chat"));
        expect(state.poll("another-mac", cursor, 16_500).get("events").array().isEmpty(), "disable purges content");
        state.configure(true, Set.of());
        expect(!state.poll("mac", null, 16_600).get("macOnline").boolValue(), "empty selection collects nothing");
        state.configure(true, Set.of("chat"));
        cursor = state.poll("mac", null, 17_000).get("cursor").string();
        post(state, "chat", "key", "new", 17_100);
        expect(state.poll("mac", "wrong-cursor", 17_200).get("events").array().isEmpty(), "unknown cursor establishes baseline");
        state.listener(false);
        state.listener(true);
        expect(state.poll("mac", cursor, 17_300).get("events").array().isEmpty(), "listener restart establishes baseline");
        cursor = state.poll("mac", null, 18_000).get("cursor").string();
        state.posted("chat", "big", "Chat", "x".repeat(199) + "😀", "x".repeat(3999) + "😀", false, false, 18_100);
        Json large = state.poll("mac", cursor, 18_200).get("events").array().get(0);
        expect(large.get("title").string().length() == 199 && large.get("text").string().length() == 3999, "truncate without splitting surrogate pairs");
        try { state.poll("", null, 18_300); throw new AssertionError("empty client accepted"); }
        catch (IllegalArgumentException expected) { }
        System.out.println("手机通知白名单、权限、去重、断线与队列边界通过");
    }
    private static void post(NotificationSyncState state, String pkg, String key, String body, long now) {
        state.posted(pkg, key, "Chat", "title", body, false, false, now);
    }
    private static void expect(boolean value, String message) { if (!value) { throw new AssertionError(message); } }
}
