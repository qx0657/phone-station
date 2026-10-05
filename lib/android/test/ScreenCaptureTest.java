package dev.phonestation.adbkeep;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

final class ScreenCaptureTest {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("station-capture-test");
        FileOps files = new FileOps(home);
        byte[] png = FileOps.parseHex("89504e470d0a1a0a00");
        String id = UUID.randomUUID().toString();
        int[] calls = {0};
        ScreenCapture.Shell shell = request -> {
            calls[0]++;
            try { Files.write(files.openable(ScreenCapture.path(id)), png); }
            catch (Exception error) { throw new IllegalStateException(error); }
            return Json.obj().put("timedOut", false).put("exitCode", 0);
        };
        // A revoked queued capture must not even reserve or create ordinary files.
        try {
            ScreenCapture.capture(files, shell, id, () -> { throw new FileFailure("远程权限已撤销"); });
            throw new AssertionError("revoked capture dispatched");
        } catch (FileFailure expected) {
            expect(calls[0] == 0 && !Files.exists(home.resolve("Download")));
        }
        Json result = ScreenCapture.capture(files, shell, id);
        expect(result.get("size").longValue() == png.length);
        expect(result.get("sha256").string().length() == 64);
        try { ScreenCapture.capture(files, shell, id); throw new AssertionError("repeated capture"); }
        catch (FileFailure expected) { expect(calls[0] == 1); }
        expect(ScreenCapture.status(files, id).get("available").boolValue());
        expect(calls[0] == 1);
        ScreenCapture.release(files, id);
        ScreenCapture.release(files, id);
        expect(!ScreenCapture.status(files, id).get("available").boolValue());
        try { ScreenCapture.capture(files, shell, id); throw new AssertionError("reused released id"); }
        catch (FileFailure expected) { expect(calls[0] == 1); }
        expect(!Files.exists(home.resolve(ScreenCapture.path(id))));
        for (String invalid : new String[] {"../test", "x' ; touch /x", "", "123"}) {
            try { ScreenCapture.path(invalid); throw new AssertionError("invalid id"); }
            catch (IllegalArgumentException expected) {}
        }
        String failed = UUID.randomUUID().toString();
        try {
            ScreenCapture.capture(files, request -> Json.obj().put("timedOut", true).put("exitCode", 1), failed);
            throw new AssertionError("failed command");
        } catch (FileFailure expected) {}
        expect(!Files.exists(home.resolve(ScreenCapture.path(failed))));
        String expired = UUID.randomUUID().toString();
        Path expiredFile = home.resolve(ScreenCapture.path(expired));
        Files.write(expiredFile, png);
        Files.setLastModifiedTime(expiredFile, java.nio.file.attribute.FileTime.fromMillis(1));
        Files.writeString(expiredFile.getParent().resolve("keep.txt"), "user data");
        Path symlink = expiredFile.getParent().resolve(UUID.randomUUID() + ".png");
        Files.createSymbolicLink(symlink, home.resolve("keep-target"));
        ScreenCapture.cleanup(files, ScreenCapture.TTL_MS + 1);
        expect(!Files.exists(expiredFile));
        expect(Files.exists(expiredFile.getParent().resolve("keep.txt")));
        expect(Files.isSymbolicLink(symlink));
        for (int i = 0; i < 8; i++) { Files.write(expiredFile.getParent().resolve(UUID.randomUUID() + ".png"), png); }
        try { ScreenCapture.capture(files, shell, UUID.randomUUID().toString()); throw new AssertionError("quota"); }
        catch (FileFailure expected) { expect(expected.getMessage().contains("空间已满")); }
        Path linkHome = Files.createTempDirectory("station-capture-links");
        Files.createSymbolicLink(linkHome.resolve("Download"), home);
        try { ScreenCapture.capture(new FileOps(linkHome), shell, UUID.randomUUID().toString()); throw new AssertionError("symlink"); }
        catch (FileFailure expected) { expect(calls[0] == 1); }
        System.out.println("ScreenCaptureTest ok");
    }
    private static void expect(boolean value) { if (!value) { throw new AssertionError(); } }
}
