package dev.phonestation.adbkeep;

/** 关于页的字。没有 Android 依赖，构建时在电脑上跑测试。 */
final class AboutCopy {
    static final String AUTHOR = "Glow";

    private AboutCopy() {}

    /** 清单里的 versionName。读不到时写「未知」。 */
    static String version(String versionName) {
        if (versionName == null) {
            return "未知";
        }
        String trimmed = versionName.trim();
        if (trimmed.isEmpty()) {
            return "未知";
        }
        return trimmed;
    }
}
