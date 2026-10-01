# CLAUDE.md

本文件是 Claude Code 在本仓库工作时的指引。标记区内的主体内容与 `AGENTS.md` 互为镜像，修改任意一方都必须同步另一方。

<!-- agent-docs-sync:start -->

## 项目

本仓库是手机工位：电脑通过 adb 操作一台 Android 手机。给人看的用法在 `README.md`，从脚本表面看不出来的做法在 `docs/`。脚本按自己的目录找 `lib/`，不依赖检出路径。作者机器上的检出目录是 `~/dev/phone`。公开仓库名是 `phone-station`。

通道：

- 动手：内部存储里的普通文件走手机上「手机工位」的 MCP。长说明在 `docs/mcp.md`，步骤在 `.agents/skills/phone-mcp/SKILL.md`。入口是 `scripts/mcp.sh`。手机上的 MCP 只有这个应用里的服务，以后要加的工具也做在这里。
- 信号：只有用户或上游任务明确要求这一次提醒时才调用 `scripts/notify.sh` 或 `scripts/vibrate.sh`，用法在 `README.md` 的「会话提醒」。一轮完成、权限确认和中途询问由已安装的全局 hook 负责，不要在每个任务末尾再跑一遍。安装和卸下用 `scripts/install-agent-notify.sh`，步骤在 `.agents/skills/agent-notify/SKILL.md`，事件在 `docs/notify.md`。
- 看：`scripts/mirror.sh`、`scripts/record.sh`、`scripts/screenshot.sh` 给人看屏幕，依赖本机的 scrcpy。截取当前画面用 `screenshot.sh`。相册里已经存在的截图走图库这一条。
- 图库截图：判断哪些可以删。标准在 `docs/screenshot-cleanup.md`，步骤在 `.agents/skills/screenshot-cleanup/SKILL.md`。

`.grok/skills/` 和 `.claude/skills/` 里的同名目录是指到 `.agents/skills/` 的软链接，不要在那两处另写一份。

目前只在荣耀 PGT-AN20（Android 15）上验证过。下面「已验证设备上的命令差异」都来自这台手机。换机或认不出型号时先跑 `scripts/status.sh`。型号仍是 PGT-AN20 就按本文做。不是这台机器时停下来，说明震动、铃声、闪光灯、截屏和 MCP服务这些差异还没在这台上验证过，不要套用。

Android CLI 是隔壁工具，安装见 `docs/android-cli.md`，不要把它当成这些通道的依赖。`~/dev/skills/reverse-skill` 是作者机器上的另一份仓库，不放进本仓库，也不跑它的总控。

## 沉淀

工作和维护里验证过、下次还会用到的结论，写回仓库。文档或 skill 和刚看到的结果不一致，改那一处。只留在对话里，下次会重新摸索。

用户这次说先别改文件，或这次只是审阅、提问时，把结论写在回复里，不动仓库。要写回时只改和这次结论直接相关的那一处，并同步 `AGENTS.md` 与 `CLAUDE.md` 的镜像段。

按内容已经在的位置补：

- 人会反复运行的步骤，收成 `scripts/` 里的一个脚本，用法写进 `README.md`。
- 命令表面看不出来的做法、机型上的差异、失败时实际碰到的情况，写进对应的 `docs/<name>.md`。这一篇还不存在、以后又会遇到，就新写一篇，并在 `docs/README.md` 登记。
- 以后会凭一两句话再做一遍的流程，写成 `.agents/skills/<name>/SKILL.md`。`description` 写明什么时候用。长说明和事实留在 `docs/`，skill 写步骤并指向那篇。`.grok/skills/` 和 `.claude/skills/` 用同名软链接指到 `.agents/skills/<name>`，不要在这两处再写一份。

只解释原因、或只给某条命令补一个已经有文档的坑，更新那篇文档，不新建 skill。跟这台手机无关的个人 Skill 不放进本仓库。

写入的是这次验证过的结论。会变的端口和地址写怎么查，不写当天的数。某台机器上的差异写明机型。原有说法不对就改原句。手机界面上的地址是本机 `http://127.0.0.1:8765/mcp`。电脑上的转发地址和 `Authorization` 在菜单栏「MCP服务」，也以 `./scripts/mcp.sh` 当次打印为准。令牌随进程重起更换，不写进仓库，也不写进手机界面。

## 布局

- `scripts/` 放用户会运行的脚本：`mcp.sh`、`status.sh`、`connect.sh`、`disconnect.sh`、`host-state.sh`、`pair-qr.sh`、`pair-code.sh`、`android.sh`、`mirror.sh`、`record.sh`、`screenshot.sh`、`stay-awake.sh`、`vibrate.sh`、`notify.sh`、`install-agent-notify.sh`、`agent-notify-hook.sh`、`torch.sh`。
- `lib/` 是这些脚本的内部实现，不要让用户直接运行。新脚本用 `source "$DIR/../lib/common.sh"`，`DIR` 取脚本自己的目录。无线地址发现走 `lib/adb_mdns.py`；USB 序列号直接来自 `adb devices`。
- 脚本是 zsh，`set -euo pipefail`。不要用变量名 `path`，zsh 里它和 `PATH` 绑在一起，赋空值会把后续命令全部变成找不到。
- 每个用户脚本支持 `-h`。需要设备时先跑 `scripts/connect.sh`，再用 `online_serial` 拿到唯一的在线设备。已经有在线设备（USB 或无线）时，`connect.sh` 只去掉重复连接。

## 无线调试

不要把手机页面上的 `172.19.0.1` 传给 `adb pair` 或 `adb connect`。那是 VPN 地址，本机 Clash 的 utun 会吃掉 `172.19.0.0/16`，表现为 `protocol fault (couldn't read status message)`。地址以 mDNS 为准，并且 `route get` 的接口不能是 `utun*`。

这台电脑上的 PGT-AN20 已经配对过。配对端口和连接端口不是同一个，重启无线调试后连接端口会变。已有在线设备时不要再 `adb connect` 出第二条。USB 已在线时不必再配或再连。

PGT-AN20 会在 Wi-Fi 断开或闲置后把无线调试关掉。电脑没有 USB 时写不回这个设置。手机上的应用是「手机工位」，包名仍是 `dev.phonestation.adbkeep`。无线调试保持是其中一项：Wi-Fi 连着、且 `adb_enabled` 仍是 1 时，把 `adb_wifi_enabled` 写回 1。`scripts/android.sh` 安装这个应用并授写设置的权限，同时授所有文件访问。细节在 `docs/adb-keep.md` 和 `docs/mcp.md`。不要为了验证把 `adb_wifi_enabled` 写成 0：当时只走无线的话，会话会立刻断。

## 文件

内部存储里的普通文件走手机上「手机工位」的 MCP，工具名是 `station_file_*`。同一服务里还有只读的 `station_device_status` 和 `station_storage_summary`，以及 `station_file_open`、`station_stay_awake`、`station_notify`、`station_clipboard_set`。长说明在 `docs/mcp.md`，步骤在 `.agents/skills/phone-mcp/SKILL.md`。`scripts/mcp.sh` 打开服务并打印本机地址和 `Authorization` 头。服务只听手机的 `127.0.0.1`。没有明确要求时只用读取类工具。打开文件、保持亮屏、发提醒、写剪贴板和改文件都要有明确要求。`Android/data`、`Android/obb` 和别的应用的私有目录不在这个服务里。删除不进回收站。

## 已验证设备上的命令差异

- 震动服务是 `cmd vibrator_manager`，没有 `cmd vibrator`。间隔用 `waveform -a -r 0`，停顿那一步幅度为 0。
- `cmd notification post` 的通知对象是 `sound=null`，不能当提示音。PGT-AN20 上它的 `shell_cmd` 通道仍挂着系统通知音。`notify.sh` 默认让手机上的「手机工位」发一条无声的下拉通知，同一条原地更新；`--shell` 仍用原来的 shell 通知。铃声仍走媒体音量。`tinyplay` 打不开声卡，也不能播 ogg。
- 震动模式下通知音量流被静音。`notify.sh` 默认让手机上的「手机工位」用 `USAGE_MEDIA` 播放「通知」里选的铃声；没选过就用系统通知铃声，选了静音则不播。下拉通知本身不发声。`--shell` 仍把铃声转成 PCM。改壳上的播放时编辑 `lib/notify-sound/` 里的源码，再跑 `lib/notify-sound/build.sh`。不要手改 `lib/notify-sound.dex`，也不要把 R8 的 jar 放进仓库。
- 系统提示不允许截屏的界面，`screencap` 和 scrcpy 都是黑的。不要加绕过 `FLAG_SECURE` 的脚本，也不要改系统框架。
- 闪光灯的灯节点 shell 写不了，`settings put secure flashlight_enabled` 也点不亮。`torch.sh` 用 `CameraManager`，调用进程退出后灯会灭，所以开着要留一个 `app_process`。档数读 `FLASH_INFO_STRENGTH_MAXIMUM_LEVEL`，这台后置是 4 档；`torch.sh` 不要把 4 写死成唯一合法范围。跟随声音时不要按拍 `setTorchMode(false)`：这会拆掉闪光灯会话，高通相机服务连续开关大约一分钟后会自己退出。短暂停顿保持上一档，安静大约 0.8 秒才关灯。`turnOnTorchWithStrengthLevel` 失败，或相机编号暂时失效时，不要退出监听进程，重新找闪光灯再试。见 `docs/torch.md`。

## 验证

`scripts/status.sh` 只读，可以随时跑。`scripts/notify.sh`、`scripts/agent-notify-hook.sh`、`scripts/vibrate.sh`、`scripts/screenshot.sh`、`scripts/mirror.sh`、`scripts/record.sh`、`scripts/stay-awake.sh`、`scripts/torch.sh` 会让手机出声、震动、截屏、投屏、改息屏设置或开关闪光灯。用户或当前任务明确要求其中一件时，直接跑对应的那一个。没有这件要求时不要跑，包括不要为了看看环境而跑。改完某个脚本、要确认它还能用时，可以跑那一个。

`scripts/mcp.sh` 会打开 MCP服务。下拉栏里手机工位那条常驻通知改记 MCP 开着，不另起一条。用户或当前任务要读、改手机上的普通文件，要用上面的手机侧工具，或改完 MCP服务要确认它还能用时，才跑这一个。没有这件要求时不要跑。`station_notify`、`station_stay_awake`、`station_clipboard_set` 和 `station_file_open` 会出声、改息屏、盖掉剪贴板或打开界面，没有明确要求时不要调用。

`scripts/android.sh` 会在手机上安装「手机工位」、授予 `WRITE_SECURE_SETTINGS` 和所有文件访问（`MANAGE_EXTERNAL_STORAGE`），并可能把无线调试打开。没有明确要求时不要跑。改了 `lib/android/` 里的 Java 时跑 `lib/android/build.sh`：它在电脑上编 APK，并跑打开时机的测试，不碰手机。

shell 脚本用 `zsh -n`。改了 `lib/adb_mdns.py`、`lib/pair_qr.py`、`lib/torch_beat.py`、`lib/agent_notify_hook.py` 或 `lib/install_agent_notify.py` 时用 `python3 -m py_compile`。改了 `PlayPcm.java` 时跑 `lib/notify-sound/build.sh`。改了 `lib/torch/Torch.java` 时跑 `lib/torch/build.sh`。`lib/torch-audio/main.swift` 比已编译的程序新时，`torch.sh beat` 会自己重编。

<!-- agent-docs-sync:end -->
