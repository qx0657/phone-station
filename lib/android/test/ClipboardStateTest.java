package dev.phonestation.adbkeep;

final class ClipboardStateTest {
    private static final class Access implements ClipboardState.Access {
        ClipboardState.Clip clip = new ClipboardState.Clip("text", "phone-original");
        int writes;
        public ClipboardState.Clip read() { return clip; }
        public void write(String text) { writes++; clip = new ClipboardState.Clip("text", text); }
    }
    private static Json args(String version, String text, String phone) {
        Json args = Json.obj().put("clientId", "mac").put("macVersion", version).put("macKind", "text").put("macText", text);
        if (phone != null) { args.put("phoneVersion", phone); }
        return args;
    }
    public static void main(String[] ignored) {
        Class<?>[] honorRead = {String.class, String.class, int.class, int.class, boolean.class};
        Object[] readArgs = ClipboardMethods.arguments(honorRead, false, null, 10);
        expect(readArgs != null && readArgs[2].equals(10) && readArgs[3].equals(0) && readArgs[4].equals(true), "OEM userOperate signature");
        expect(ClipboardMethods.arguments(new Class<?>[] {String.class, long.class}, false, null, 0) == null, "unknown signature must be refused");
        ClipboardState state = new ClipboardState();
        Access access = new Access();
        Json first = state.exchange(access, args("1", "old-mac", null), true, 100);
        expect(access.writes == 0, "first contact must not overwrite");
        String baseline = first.get("phoneVersion").string();
        Json applied = state.exchange(access, args("2", "new-mac", baseline), true, 200);
        expect(applied.get("appliedMac").boolValue() && access.writes == 1, "new Mac text should sync");
        expect("new-mac".equals(applied.get("phone").get("text").string()), "write should be verified");
        state.exchange(access, args("2", "new-mac", baseline), true, 300);
        expect(access.writes == 1, "duplicate request must not repeat write");
        String version = applied.get("phoneVersion").string();
        access.clip = new ClipboardState.Clip("text", "phone-newer");
        Json conflict = state.exchange(access, args("3", "mac-concurrent", version), true, 400);
        expect(access.writes == 1 && "phone-newer".equals(conflict.get("phone").get("text").string()), "phone copy wins concurrent conflict");
        version = conflict.get("phoneVersion").string();
        state.exchange(access, args("4", "manual-mode", version), false, 500);
        expect(access.writes == 1, "manual preview must not change clipboard");
        expect("manual-mode".equals(state.snapshot(500).get("mac").get("text").string()), "Mac preview available");
        expect(state.snapshot(10_501).get("mac").isNull(), "offline preview must expire");
        state.exchange(access, args("5", "offline-copy", version), true, 11_000);
        expect(access.writes == 1, "old connection must not replay");
        access.clip = new ClipboardState.Clip("sensitive", "secret");
        Json sensitive = state.exchange(access, args("6", "next", null), true, 11_100);
        expect(sensitive.get("phone").get("text").isNull(), "sensitive text must never be serialized");
        state.exchange(access, args("7", "next", sensitive.get("phoneVersion").string()), true, 11_200);
        expect(access.writes == 1, "sensitive clipboard must not be overwritten automatically");
        state.clear();
        expect(state.snapshot(11_300).get("mac").isNull(), "disable should remove previews");
        state.exchange(access, args("8", "restart", version), true, 11_400);
        expect(access.writes == 1, "service restart invalidates baseline");
        state.clear();
        access.clip = new ClipboardState.Clip("text", "same-phone-text", 100);
        Json beforeRecopy = state.exchange(access, args("9", "different-mac-text", null), true, 11_500);
        access.clip = new ClipboardState.Clip("text", "same-phone-text", 101);
        Json recopy = state.exchange(access, args("9", "different-mac-text", beforeRecopy.get("phoneVersion").string()), true, 11_600);
        expect(!recopy.get("phoneVersion").string().equals(beforeRecopy.get("phoneVersion").string()), "recopying identical text must create a phone revision");
        Json unchanged = state.exchange(access, args("9", "different-mac-text", recopy.get("phoneVersion").string()), true, 11_700);
        expect(unchanged.get("phoneVersion").string().equals(recopy.get("phoneVersion").string()), "reading unchanged clipboard must not create a revision");
        expect(access.writes == 1, "a phone recopy must not be overwritten by Mac baseline");
        System.out.println("ClipboardStateTest passed");
    }
    private static void expect(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
}
