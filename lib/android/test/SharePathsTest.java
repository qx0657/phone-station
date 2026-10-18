package dev.phonestation.adbkeep;

final class SharePathsTest {
    public static void main(String[] args) {
        expect("/storage/emulated/0/Download/photo.png".equals(SharePaths.phoneDownload("photo.png")), "download");
        try { SharePaths.phoneDownload(".."); throw new AssertionError("dotdot"); }
        catch (IllegalArgumentException expected) { }
        try { SharePaths.phoneDownload("a/b"); throw new AssertionError("slash"); }
        catch (IllegalArgumentException expected) { }
        expect("photo.png".equals(SharePaths.macName("/storage/emulated/0/Download/photo.png")), "mac name");
        try { SharePaths.macName("/storage/emulated/0/Android/data/secret"); throw new AssertionError("android"); }
        catch (IllegalArgumentException expected) { }
        try { SharePaths.macName("/tmp/a"); throw new AssertionError("outside"); }
        catch (IllegalArgumentException expected) { }
        System.out.println("SharePathsTest ok");
    }

    private static void expect(boolean value, String message) {
        if (!value) { throw new AssertionError(message); }
    }
}
