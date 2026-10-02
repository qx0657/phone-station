package dev.phonestation.adbkeep;

/** 手机上「MCP说明」那一页的字。没有 Android 依赖，构建时在电脑上跑测试。 */
final class McpHelp {
    static final class Section {
        final String title;
        final String body;

        Section(String title, String body) {
            this.title = title;
            this.body = body;
        }
    }

    private McpHelp() {}

    static Section[] sections() {
        return new Section[] {
            new Section(
                    "怎么连上",
                    "打开「MCP服务」之后，在电脑菜单栏的「手机工位」里打开同一项。\n"
                            + "也可以在仓库里运行\n"
                            + "./scripts/mcp.sh\n"
                            + "界面上的地址只在手机本机。电脑上的地址和令牌在菜单栏，或脚本打印的那一行。"
                            + "开着的话，手机会记住，重启后还会再打开。"
                            + "若需通过互联网继续访问，在「远程中继设置」填写服务器资料。"
                            + "Mac 填相同地址、指纹和另一枚电脑令牌，两端分别保存即可；也可由 Mac 通过 adb 同时配置。"),
            new Section(
                    "能做的",
                    "读改下载、文档和相册里的普通文件。看电量和剩余空间。"
                            + "打开一个文件，保持亮屏，发一条提醒，或把一段文字放进剪贴板。"
                            + "手机上启动 Shizuku，并在权限页允许使用后，还能通过本地或远程通道执行 shell 命令。"),
            new Section(
                    "做不到的",
                    "普通文件工具不开放别的应用的私有目录，短信和通讯录也没有专用工具。"
                            + "通过无线调试启动的 Shizuku 仍不能读取别的应用的私有数据。"
                            + "删掉的文件不进回收站。重启手机后，需要重新启动 Shizuku。"),
            new Section(
                    "交接",
                    "把要处理的文件放进 Download/手机工位/inbox，"
                            + "处理完的放进 Download/手机工位/outbox。"
                            + "这两个目录不会自动创建。")
        };
    }
}
