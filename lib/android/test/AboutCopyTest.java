package dev.phonestation.adbkeep;

final class AboutCopyTest {
    public static void main(String[] args) {
        expect("28", AboutCopy.version("28"));
        expect("28", AboutCopy.version(" 28 "));
        expect("未知", AboutCopy.version(null));
        expect("未知", AboutCopy.version(""));
        expect("未知", AboutCopy.version("  "));
        expect("Glow", AboutCopy.AUTHOR);
        System.out.println("AboutCopyTest ok");
    }

    private static void expect(String want, String got) {
        if (!want.equals(got)) {
            throw new AssertionError("want " + want + " got " + got);
        }
    }
}
