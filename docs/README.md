# 文档

日常用法在 [../README.md](../README.md)。那篇按事情排：Mac 菜单栏、Android 应用，然后是共享剪贴板、会话提醒、闪光灯、远程命令与安装、文件、屏幕，最后是准备和连接。无线调试保持写在 Android 应用里。这里是命令表面看不出来的做法：当时怎么验证的、机型上的差异、失败时实际碰到的情况。机型差异写在各篇里，不另开「某台手机」的目录。

两端原生界面的配色、字体、共用组件与配置布局记录在 [DESIGN.md](../DESIGN.md)，其原生元数据在 [.impeccable/design.json](../.impeccable/design.json)。

| 文档 | 对应 | 这篇里有什么 |
| --- | --- | --- |
| [mac-app.md](mac-app.md) | `scripts/build-mac-app.sh` | 菜单栏 App 的构建、脚本打包、面板上的亮屏和闪光灯，以及录屏、跟随声音的结束方式。图标不在菜单栏上时，看 Hidden Bar 记下的位置，以及控制中心是否把它算进已禁用的 App |
| [mcp.md](mcp.md) | `scripts/mcp.sh` | 手机工位 MCP、Mac 统一网关、可配置远程中继、两端配置界面、鉴权与路径边界，以及 PGT-AN20 上读到和读不到的地方 |
| [remote-ops.md](remote-ops.md) | `scripts/shell.sh`、`scripts/install-apk.sh`、`scripts/android.sh` | 无需 adb 连接的 Shizuku shell、APK 上传校验、单包与 split 安装、手机工位自身更新及断线后查询 |
| [clipboard.md](clipboard.md) | 两端「共享剪贴板」页与 `station_clipboard_*` | Shizuku 后台读取、双向交换、回传和冲突处理、重连基线、敏感内容及文字范围 |
| [notify.md](notify.md) | 两端通知设置与 `scripts/notify.sh` | 手机所选应用的新通知同步到 Mac：系统通知使用权、白名单、短期接收队列与断线基线。电脑提醒发到手机：下拉通知与铃声、媒体音量、`--agent` 图标、`--shell` PCM 及全局 hook |
| [torch.md](torch.md) | `scripts/torch.sh` | 为什么不能写灯节点。闪光灯要留在手机上的进程里；跟随声音采的是系统混音 |
| [screenshot-cleanup.md](screenshot-cleanup.md) | 图库截图 | 哪些截图可以删。界面类和重复张的像素阈值。张数是 2026-09-29 这台 PGT-AN20 上的 |
| [adb-keep.md](adb-keep.md) | `lib/android/`、`scripts/android.sh` | 手机上的「手机工位」。为什么无线调试会自己关，以及何时写回去。界面上的已连接合并 adb 心跳和远程通道；adb 断开后靠写成 0 或 15 秒过期复位，远程在线时仍显示已连接。权限页列出读得到的授权，其中 Shizuku 要服务在跑才能授权；自启动读不到。Shizuku 启动后活到重启，重启后要无线调试开着才能再拉起。开机自启还要在荣耀的应用启动管理里允许 |
| [android-cli.md](android-cli.md) | `android` | 建工程、查文档、装 APK、看布局。不参与 `scripts/` 里的手机操作，安装命令写在这里 |
