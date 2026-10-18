package dev.phonestation.adbkeep;

/** 分享和拖放的路径规则，与 lib/link/share.go 一致。不读文件，也不打开网络。 */
public final class SharePaths {
    static final String PHONE_HOME = "/storage/emulated/0";

    private SharePaths() {}

    public static String phoneDownload(String name) {
        if (!fileName(name)) { throw new IllegalArgumentException("share"); }
        return PHONE_HOME + "/Download/" + name;
    }

    /** 分享到 Mac 的文件名。显示名优先，不能当文件名时用路径最后一段，再不行用 shared.bin。 */
    static String shareLeaf(String display, String segment) {
        String name = acceptable(display);
        if (name == null) {
            name = acceptable(segment);
        }
        return name == null ? "shared.bin" : name;
    }

    private static String acceptable(String raw) {
        String name = single(raw);
        if (name == null) {
            return null;
        }
        try {
            phoneDownload(name);
        } catch (IllegalArgumentException error) {
            return null;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c > 0x7e || c == '"' || c == '\\') {
                return null;
            }
        }
        return name;
    }

    private static String single(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < name.length()) {
            return name.substring(slash + 1);
        }
        if (slash >= 0) {
            return null;
        }
        return name;
    }

    static String macName(String phonePath) {
        String normal = phoneFile(phonePath);
        int slash = normal.lastIndexOf('/');
        return normal.substring(slash + 1);
    }

    private static boolean fileName(String name) {
        return name != null && !name.isEmpty() && !".".equals(name) && !"..".equals(name)
                && name.indexOf('/') < 0 && name.indexOf('\\') < 0 && name.indexOf('\0') < 0;
    }

    private static String phoneFile(String input) {
        if (input == null || input.isEmpty() || input.indexOf('\0') >= 0 || input.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("share");
        }
        String normal = java.nio.file.Paths.get(input).normalize().toString();
        String prefix = PHONE_HOME + "/";
        if (!normal.startsWith(prefix) || PHONE_HOME.equals(normal)) {
            throw new IllegalArgumentException("share");
        }
        String rel = normal.substring(prefix.length());
        if ("Android".equals(rel) || rel.startsWith("Android/")) {
            throw new IllegalArgumentException("share");
        }
        return normal;
    }
}
