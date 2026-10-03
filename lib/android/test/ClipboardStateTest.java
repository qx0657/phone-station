package dev.phonestation.adbkeep;

final class ClipboardStateTest {
    private static final class Access implements ClipboardState.Access {
        ClipboardState.Clip clip = new ClipboardState.Clip("text", "phone-original");
        int writes;
        int images;
        boolean failImage;
        public ClipboardState.Clip read() { return clip; }
        public void write(String text) { writes++; clip = new ClipboardState.Clip("text", text); }
        public void saveImage(byte[] png) {
            images++;
            if (failImage) { throw new FileFailure("相册保存失败"); }
        }
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
        ClipboardState resumed = new ClipboardState();
        Access resumedAccess = new Access();
        Json anchor = resumed.exchange(resumedAccess, args("1", "mac-original", null), true, 100);
        Json fresh = resumed.refresh(resumedAccess, 12_000);
        expect(resumedAccess.writes == 0, "fresh state is read-only");
        expect(fresh.get("mac").isNull(), "a state read must not renew the Mac heartbeat");
        Json resumeArgs = args("4", "latest-of-several-copies", fresh.get("phoneVersion").string()).put("resume", true);
        Json recovered = resumed.exchange(resumedAccess, resumeArgs, true, 12_100);
        expect(recovered.get("appliedMac").boolValue() && resumedAccess.writes == 1,
                "read-only probe must not consume the pending Mac version; short handover resumes it");
        resumed.exchange(resumedAccess, resumeArgs, true, 12_200);
        expect(resumedAccess.writes == 1, "same resumed event never writes twice");
        fresh = resumed.refresh(resumedAccess, 12_300);
        resumedAccess.clip = new ClipboardState.Clip("text", "phone-copied-after-probe", 500);
        resumed.exchange(resumedAccess, args("5", "mac-new", fresh.get("phoneVersion").string()).put("resume", true), true, 12_400);
        expect(resumedAccess.writes == 1, "a copy after the recovery probe must still win the conflict");
        fresh = resumed.refresh(resumedAccess, 43_000);
        resumed.exchange(resumedAccess, args("6", "stale-offline-copy", fresh.get("phoneVersion").string()).put("resume", true), true, 43_100);
        expect(resumedAccess.writes == 1, "resume must expire after a long outage");
        String session = anchor.get("sessionId").string();
        resumed.clear();
        fresh = resumed.refresh(resumedAccess, 43_200);
        expect(!session.equals(fresh.get("sessionId").string()), "restarted state has a separate session identity");
        resumed.exchange(resumedAccess, args("7", "restart-copy", fresh.get("phoneVersion").string()).put("resume", true), true, 43_300);
        expect(resumedAccess.writes == 1, "a new service must not resume the old client");
        imageTests();
        System.out.println("ClipboardStateTest passed");
    }
    private static Json imageArgs(String copy, String phone) {
        Json args = Json.obj().put("clientId", "mac").put("macVersion", copy).put("macKind", "image")
                .put("macImage", "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aF6sAAAAASUVORK5CYII=");
        if (phone != null) { args.put("phoneVersion", phone); }
        return args;
    }
    private static void imageTests() {
        ClipboardState state = new ClipboardState();
        Access access = new Access();
        Json base = state.exchange(access, imageArgs("1", null), true, true, 100);
        expect(access.images == 0, "initial images only establish a baseline");
        String version = base.get("phoneVersion").string();
        state.exchange(access, imageArgs("2", version), true, 200);
        expect(access.images == 0, "images are disabled by default");
        state.exchange(access, imageArgs("3", version), false, true, 300);
        expect(access.images == 0, "manual mode cannot save images");
        Json saved = state.exchange(access, imageArgs("4", version), true, true, 400);
        expect(access.images == 1 && access.writes == 0 && saved.get("savedImage").boolValue(),
                "enabled new images save to the album without writing clipboard text");
        expect(!saved.emit().contains("iVBOR") && !state.snapshot(400).emit().contains("macImage"),
                "image bytes are not stored in snapshots");
        state.exchange(access, imageArgs("4", version), true, true, 500);
        expect(access.images == 1, "a repeated image event never creates duplicates");
        access.clip = new ClipboardState.Clip("text", "phone-newer", 10);
        Json changed = state.exchange(access, imageArgs("5", version), true, true, 600);
        expect(access.images == 1, "a concurrent phone copy wins");
        version = changed.get("phoneVersion").string();
        state.exchange(access, imageArgs("6", version), true, true, 11_000);
        expect(access.images == 1, "stale reconnect never imports the old image");
        access.clip = new ClipboardState.Clip("locked", null);
        Json locked = state.refresh(access, 11_100);
        state.exchange(access, imageArgs("7", locked.get("phoneVersion").string()), true, true, 11_200);
        access.clip = new ClipboardState.Clip("sensitive", null);
        Json sensitive = state.refresh(access, 11_300);
        state.exchange(access, imageArgs("8", sensitive.get("phoneVersion").string()), true, true, 11_400);
        expect(access.images == 1, "lockscreen and sensitive content block album imports");
        access.clip = new ClipboardState.Clip("unsupported", null);
        Json ready = state.refresh(access, 11_500);
        version = ready.get("phoneVersion").string();
        access.failImage = true;
        Json failure = state.exchange(access, imageArgs("9", version), true, true, 11_600);
        expect(failure.get("imageError") != null && !failure.get("savedImage").boolValue(),
                "album failure is reported without disabling clipboard synchronization");
        access.failImage = false;
        state.exchange(access, imageArgs("9", version), true, true, 11_700);
        expect(access.images == 2, "a failed album import is not automatically replayed");
        state.exchange(access, imageArgs("10", version), true, true, 11_800);
        expect(access.images == 3, "a fresh copy can retry after an album failure");
        try {
            state.exchange(access, imageArgs("11", version).put("macImage", "invalid"), true, true, 11_900);
            throw new AssertionError("invalid PNG accepted");
        } catch (IllegalArgumentException expected) {}
        try {
            state.exchange(access, imageArgs("12", version).put("macImage", "A".repeat(6_000_000)), true, true, 12_000);
            throw new AssertionError("oversized image accepted");
        } catch (IllegalArgumentException expected) {}
        state.clear();
        state.exchange(access, imageArgs("13", version), true, true, 12_100);
        expect(access.images == 3, "service restart never replays an image");
    }
    private static void expect(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
}
