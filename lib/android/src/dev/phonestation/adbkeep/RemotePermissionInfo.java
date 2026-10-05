package dev.phonestation.adbkeep;

/** User-facing remote access vocabulary, shared by UI, diagnostics and failures. */
final class RemotePermissionInfo {
    static String title(String scope) {
        switch (scope) {
            case "files.read": return "文件读取";
            case "files.write": return "文件修改";
            case "personal": return "剪贴板与通知";
            case "controls": return "手机控件与提醒";
            case "screen": return "投屏与操控";
            case "shell": return "Shell 与安装";
            default: return "远程能力";
        }
    }
    static String detail(String scope) {
        switch (scope) {
            case "files.read": return "读取、搜索普通文件，其中可能包含私人资料。下载远程截图也需要此权限。";
            case "files.write": return "创建、覆盖、复制、移动和永久删除普通文件。";
            case "personal": return "读取、修改和同步剪贴板，接收手机上已选应用的通知。";
            case "controls": return "截屏、打开文件、保持亮屏、手电筒，以及电脑发到手机的提醒。";
            case "screen": return "查看屏幕、收听手机音频、录屏，以及鼠标键盘操控手机。";
            case "shell": return "执行 Shizuku shell、交互终端和 APK 安装。可修改系统并绕过普通文件范围，只授予可信电脑和中继。安装还需要文件读取与文件修改。";
            default: return "";
        }
    }
    static String summary(int allowed) { return allowed == 0 ? "仅状态查询" : "已允许 " + allowed + "/6 项"; }
    static String denied(String scope) {
        return "远程「" + title(scope) + "」未授权，请在手机「权限与检查 → 远程访问范围」中允许。";
    }
    private RemotePermissionInfo() {}
}
