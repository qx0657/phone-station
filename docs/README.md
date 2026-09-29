# 文档

日常用法在 [../README.md](../README.md)。那篇按事情排：会话提醒、闪光灯、文件和 APK、屏幕，然后是准备和连接。这里是命令表面看不出来的做法：当时怎么验证的、机型上的差异、失败时实际碰到的情况。机型差异写在各篇里，不另开「某台手机」的目录。

| 文档 | 对应 | 这篇里有什么 |
| --- | --- | --- |
| [mt-mcp.md](mt-mcp.md) | `scripts/mt.sh` | MCP 没有 exported。脚本如何打开侧栏并转发端口。工具表，以及当时的文件权限 |
| [apk-edit.md](apk-edit.md) | 改 APK | MT 覆盖不到的阅读和重打包，以及这台 Mac 上缺哪些命令。分工以这篇为准 |
| [notify.md](notify.md) | `scripts/notify.sh` | 为什么不走通知音量。下拉通知默认只更新一条，不发声；`--stack` 另发一条。播放程序的源码在 `lib/notify-sound/`，用 `build.sh` 编成 dex。全局 hook 的事件和安装位置 |
| [torch.md](torch.md) | `scripts/torch.sh` | 为什么不能写灯节点。闪光灯要留在手机上的进程里；跟随声音采的是系统混音 |
| [screenshot-cleanup.md](screenshot-cleanup.md) | 图库截图 | 哪些截图可以删。界面类和重复张的像素阈值。张数是 2026-09-29 这台 PGT-AN20 上的 |
| [android-cli.md](android-cli.md) | `android` | 建工程、查文档、装 APK、看布局。不参与 `scripts/` 里的手机操作，安装命令写在这里 |
