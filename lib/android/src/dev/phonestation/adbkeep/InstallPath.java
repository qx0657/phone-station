package dev.phonestation.adbkeep;

/** 安装包必须是内部存储里的普通 apk。命令不含引号，路径本身不允许空格。 */
final class InstallPath {
    private InstallPath() {}

    static String command(String path) {
        if (path == null || path.contains("..") || !path.matches("/storage/emulated/0/(?:[A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+\\.apk")) {
            throw new IllegalArgumentException("install");
        }
        return "pm install -r --user 0 " + path;
    }
}
