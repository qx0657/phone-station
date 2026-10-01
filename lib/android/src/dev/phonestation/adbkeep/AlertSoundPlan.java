package dev.phonestation.adbkeep;

/**
 * 会话提醒播哪一段铃声。没有 Android 依赖，构建时在电脑上跑测试。
 *
 * <p>广播结果 {@link #PLAYED} 是通知已发出并且播完。{@link #SOUND_FAILED} 是通知已发出但没播成。
 * {@link #MISSING} 是没有铃声，这次不发通知。{@link #POSTED_SILENT} 是用户把铃声设成静音，通知照发，不播。
 */
final class AlertSoundPlan {
    static final int PLAYED = 1;
    static final int SOUND_FAILED = 2;
    static final int MISSING = 3;
    static final int POSTED_SILENT = 4;
    static final int FILE = 7;
    static final int URI = 8;
    static final int SILENT = 9;
    /** 存在偏好里，表示用户选了静音。不是文件路径。 */
    static final String SILENT_TOKEN = "phonestation:silent";

    final int kind;
    final String value;

    private AlertSoundPlan(int kind, String value) {
        this.kind = kind;
        this.value = value;
    }

    static AlertSoundPlan missing() {
        return new AlertSoundPlan(MISSING, "");
    }

    static AlertSoundPlan file(String path) {
        return new AlertSoundPlan(FILE, path);
    }

    static AlertSoundPlan silent() {
        return new AlertSoundPlan(SILENT, "");
    }

    /**
     * {@code explicit} 非空时用它，这是电脑这一次指定的文件。
     * 否则用应用里存的 {@code stored}：{@code null} 表示跟随 {@code system}，
     * {@link #SILENT_TOKEN} 表示静音。{@code content://} 是媒体库地址，{@code file://} 去掉前缀。
     * 系统铃声空着或 {@code null} 算没有铃声。
     */
    static AlertSoundPlan choose(String explicit, String stored, String system) {
        String given = explicit == null ? "" : explicit.trim();
        if (!given.isEmpty()) {
            return fromText(given);
        }
        if (stored != null) {
            if (SILENT_TOKEN.equals(stored)) {
                return silent();
            }
            String chosen = stored.trim();
            if (!chosen.isEmpty()) {
                return fromText(chosen);
            }
        }
        String sound = system == null ? "" : system.trim();
        if (sound.isEmpty() || "null".equals(sound)) {
            return missing();
        }
        return fromText(sound);
    }

    /** 同一条媒体库铃声。系统设置常在地址后带 {@code ?title=}，列表里的地址没有这段。 */
    static boolean sameTone(String stored, String candidate) {
        if (stored == null || candidate == null) {
            return stored == null && candidate == null;
        }
        return stripQuery(stored).equals(stripQuery(candidate));
    }

    private static String stripQuery(String value) {
        int cut = value.length();
        int query = value.indexOf('?');
        int fragment = value.indexOf('#');
        if (query >= 0) {
            cut = Math.min(cut, query);
        }
        if (fragment >= 0) {
            cut = Math.min(cut, fragment);
        }
        return value.substring(0, cut);
    }

    private static AlertSoundPlan fromText(String text) {
        if (text.startsWith("content://")) {
            return new AlertSoundPlan(URI, text);
        }
        if (text.startsWith("file://")) {
            return file(text.substring("file://".length()));
        }
        return file(text);
    }
}
