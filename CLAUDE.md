# CLAUDE.md

本文件是 Claude Code 在本仓库工作时的指引。标记区内的主体内容与 `AGENTS.md` 互为镜像，修改任意一方都必须同步另一方。

<!-- agent-docs-sync:start -->

## 项目

本仓库是手机工位：电脑通过 adb 操作一台 Android 手机。给人看的用法在 `README.md`，从脚本表面看不出来的做法在 `docs/`。脚本按自己的目录找 `lib/`，不依赖检出路径。作者机器上的检出目录是 `~/dev/phone`。公开仓库名是 `phone-station`。

通道：

- 动手：内部存储里的普通文件走手机上「手机工位」的 MCP。长说明在 `docs/mcp.md`，步骤在 `.agents/skills/phone-mcp/SKILL.md`。入口是 `scripts/mcp.sh`。手机上的 MCP 只有这个应用里的服务，以后要加的工具也做在这里。
- 信号：只有用户或上游任务明确要求这一次提醒时才调用 `scripts/notify.sh` 或 `scripts/vibrate.sh`，用法在 `README.md` 的「会话提醒」。一轮完成、权限确认和中途询问由已安装的全局 hook 负责，不要在每个任务末尾再跑一遍。安装和卸下用 `scripts/install-agent-notify.sh`，步骤在 `.agents/skills/agent-notify/SKILL.md`，事件在 `docs/notify.md`。
- 看：`scripts/mirror.sh`、`scripts/record.sh` 给人看屏幕，依赖本机的 scrcpy；`scripts/screenshot.sh` 用本机 adb 截屏。Mac App 或 MCP 可经已授权的 Shizuku 远程截屏，步骤在 phone-mcp skill，详情在 `docs/remote-controls.md`。相册里已经存在的截图走图库这一条。
- 图库截图：判断哪些可以删。标准在 `docs/screenshot-cleanup.md`，步骤在 `.agents/skills/screenshot-cleanup/SKILL.md`。

`.grok/skills/` 和 `.claude/skills/` 里的同名目录是指到 `.agents/skills/` 的软链接，不要在那两处另写一份。

目前只在荣耀 PGT-AN20（Android 15）上验证过。下面「已验证设备上的命令差异」都来自这台手机。需要操作设备而型号未知时，本地通道用 `scripts/status.sh`，远程通道用会返回 `model` 的只读 `station_controls_status` 或对应入口的机型检查，不为了确认机型强行连接 adb。不是这台机器时暂停依赖机型的设备操作，说明哪些差异尚未验证；源码检查、本机构建和测试仍可继续。

Android CLI 是隔壁工具，安装见 `docs/android-cli.md`，不要把它当成这些通道的依赖。`~/dev/skills/reverse-skill` 是作者机器上的另一份仓库，不放进本仓库，也不跑它的总控。

## 执行与完成

- 结合当前任务和已有授权判断下一步。用户已要求修复、验证或安装时，不为同一范围内的必要步骤反复确认；只要求审阅时保留只读范围。代码检查本身不包含手机通知、截屏、剪贴板交换或安装。
- 中断后先核对工作区、提交和原任务回执，再继续未完成部分。聊天标题和旧计划不能代替实际完成记录；有副作用的操作应答丢失时查询原编号，不重新提交。
- 报告时区分源码修复、本机测试、构建、推送和设备部署。测试通过不代表已部署；安装内容一致也不代表后台服务和远程通道已恢复。只把已核实的层次写成完成。

## 沉淀

工作和维护里验证过、下次还会用到的结论，写回仓库。文档或 skill 和刚看到的结果不一致，改那一处。只留在对话里，下次会重新摸索。

用户这次说先别改文件，或这次只是审阅、提问时，把结论写在回复里，不动仓库。要写回时只改和这次结论直接相关的那一处，并同步 `AGENTS.md` 与 `CLAUDE.md` 的镜像段。

按内容已经在的位置补：

- 人会反复运行的步骤，收成 `scripts/` 里的一个脚本，用法写进 `README.md`。
- 命令表面看不出来的做法、机型上的差异、失败时实际碰到的情况，写进对应的 `docs/<name>.md`。这一篇还不存在、以后又会遇到，就新写一篇，并在 `docs/README.md` 登记。
- 以后会凭一两句话再做一遍的流程，写成 `.agents/skills/<name>/SKILL.md`。`description` 写明什么时候用。长说明和事实留在 `docs/`，skill 写步骤并指向那篇。`.grok/skills/` 和 `.claude/skills/` 用同名软链接指到 `.agents/skills/<name>`，不要在这两处再写一份。

只解释原因、或只给某条命令补一个已经有文档的坑，更新那篇文档，不新建 skill。跟这台手机无关的个人 Skill 不放进本仓库。

写入的是这次验证过的结论。会变的端口和地址写怎么查，不写当天的数。某台机器上的差异写明机型。原有说法不对就改原句。手机界面上的地址是本机 `http://127.0.0.1:8765/mcp`。电脑固定访问本机网关 `http://127.0.0.1:18765/mcp`；adb 转发用 `18766`，用户配置的远程中继只切换 MCP 请求，不替代无线 adb。菜单栏「MCP 服务」显示接入地址、当前通道并提供复制授权头；`./scripts/mcp.sh` 打印地址与授权头。网关令牌及临时手机本地令牌保存在用户配置目录，权限为 `0600`；手机服务自己的令牌随进程重起更换，仅通过 adb 读取。远程通道默认未配置。手机「远程中继设置」与 Mac「MCP 服务 → 远程中继」可分别填写 HTTPS 地址、SPKI 指纹和各自令牌，无需 adb；Mac「同时配置手机」和 `mcp.sh pair` 经 adb 一次配置两端。升级保留已有配对。远程中继令牌在 Mac 钥匙串和 Android Keystore。令牌不写进仓库、日志或界面明文；服务启动不再把本地令牌写入 logcat，仅通过受 DUMP 权限保护的诊断入口读取。远程中继令牌经标准输入或隐藏字段传入，已有令牌不回填。

## 布局

- `scripts/` 放用户会运行的脚本：`mcp.sh`、`status.sh`、`connect.sh`、`disconnect.sh`、`host-state.sh`、`pair-qr.sh`、`pair-code.sh`、`android.sh`、`shell.sh`、`install-apk.sh`、`mirror.sh`、`record.sh`、`screenshot.sh`、`stay-awake.sh`、`vibrate.sh`、`notify.sh`、`install-agent-notify.sh`、`agent-notify-hook.sh`、`torch.sh`。
- `lib/` 是这些脚本的内部实现，不要让用户直接运行。新脚本用 `source "$DIR/../lib/common.sh"`，`DIR` 取脚本自己的目录。无线地址发现走 `lib/adb_mdns.py`；USB 序列号直接来自 `adb devices`。
- `lib/remote-gateway/` 是 Mac 本机 MCP 网关源码，构建时打包网关和钥匙串助手。统一 MCP 优先走本地 adb；`remote-call` 子命令经本机网关共享队列固定调用中继，网关未运行时自动启动，不连接 adb。完整 adb、投屏、录屏和灯光跟随声音仍要求本机 adb。Mac App 的截屏、保持亮屏和手电筒可用远程 MCP；`station_controls_status` 只读核实机型、权限与状态，`station_screen_capture`/`station_screen_capture_release` 负责单次 PNG 与清理，`station_torch` 通过持续的 Shizuku UserService 持灯。开关与截图丢失应答后只核实，不重放。
- `station_shell_status` 检查 Shizuku，`station_shell_start` / `station_operation_status` 提交和查询后台任务，旧的 `station_shell_exec` 同步接口通过已启动并授权的 Shizuku 13+ UserService 执行命令，本地与远程共用。需要用户要求 shell 或系统操作才调用执行工具。普通文件仍走 `station_file_*`。命令超时或断线不自动重做，先核实结果；非 root 手机重启后要重新启动 Shizuku。执行端不回退到应用 UID，详情在 `docs/mcp.md`。
- 用户要求安装、升级 APK 或改需求后更新手机工位时，优先 `scripts/install-apk.sh <电脑APK>`、`scripts/android.sh --remote`；远程 shell 用 `scripts/shell.sh '<手机命令>'`，这些入口不依赖 adb 连接。安装支持 base + split、SHA-256 核对和保留数据升级，不自动卸载解决签名冲突。自身更新用最长 180 秒的独立安装助手，重连后查询原任务。结果未知时 `install-apk.sh --status <任务编号>`，不重新安装。默认 `android.sh` 仅在上传前检查远程不可用时才走 adb；`--adb` 强制本地。见 `docs/remote-ops.md`。
- 脚本是 zsh，`set -euo pipefail`。不要用变量名 `path`，zsh 里它和 `PATH` 绑在一起，赋空值会把后续命令全部变成找不到。
- 每个用户脚本支持 `-h`。走 adb 的脚本需要设备时先跑 `scripts/connect.sh`，再用 `online_serial` 拿到唯一的在线设备。已经有在线设备（USB 或无线）时，`connect.sh` 只去掉重复连接。远程 shell 与安装不跑 `connect.sh`，直接核实 Shizuku 与机型。

## 无线调试

不要把手机页面上的 `172.19.0.1` 传给 `adb pair` 或 `adb connect`。那是 VPN 地址，本机 Clash 的 utun 会吃掉 `172.19.0.0/16`，表现为 `protocol fault (couldn't read status message)`。地址以 mDNS 为准，并且 `route get` 的接口不能是 `utun*`。

这台电脑上的 PGT-AN20 已经配对过。配对端口和连接端口不是同一个，重启无线调试后连接端口会变。已有在线设备时不要再 `adb connect` 出第二条。USB 已在线时不必再配或再连。

PGT-AN20 会在 Wi-Fi 断开或闲置后把无线调试关掉。电脑没有 USB 时写不回这个设置。手机上的应用是「手机工位」，包名仍是 `dev.phonestation.adbkeep`。无线调试保持是其中一项：Wi-Fi 连着、且 `adb_enabled` 仍是 1 时，把 `adb_wifi_enabled` 写回 1。`scripts/android.sh` 优先远程更新并保留已有权限；首次安装或补权限用 `scripts/android.sh --adb`，授写设置与所有文件访问。细节在 `docs/adb-keep.md` 和 `docs/mcp.md`。不要为了验证把 `adb_wifi_enabled` 写成 0：当时只走无线的话，会话会立刻断。

## 文件

内部存储里的普通文件走手机上「手机工位」的 MCP，工具名是 `station_file_*`。同一服务里还有只读的 `station_device_status` 和 `station_storage_summary`，以及只读的 `station_clipboard_get`、`station_clipboard_state`，写入的 `station_clipboard_set`、共享配置与交换工具。后台读取和共享使用 Shizuku，步骤见 `docs/clipboard.md`。还有 `station_file_open`、`station_stay_awake`、`station_notify`。长说明在 `docs/mcp.md`，步骤在 `.agents/skills/phone-mcp/SKILL.md`。`scripts/mcp.sh` 打开服务并打印统一网关地址、`Authorization` 头和当前通道。手机服务只听 `127.0.0.1`；用户配置的中继仅承载同一批 MCP 工具。没有明确要求时只用读取类工具。打开文件、保持亮屏、发提醒、写剪贴板和改文件都要有明确要求。`Android/data`、`Android/obb` 和别的应用的私有目录不在这个服务里。删除不进回收站。

## 已验证设备上的命令差异

手机通知同步与电脑会话提醒分开：手机「首页 → 通知」的「手机通知 → Mac」使用用户授予的系统通知使用权，按手机端勾选的包名白名单过滤，默认关闭且不选应用。不要自动授权或替用户选应用。`station_notification_status` 只读且不返回正文；`station_notification_configure` 改开关；`station_notification_poll` 是 Mac 接收会话，会读取白名单内的新通知并更新心跳，不当只读探测、不自动重放。首次、断线或配置变化后不补发旧通知。电脑提醒的显示和铃声在同页「电脑提醒 → 手机」中。长说明与验证在 `docs/notify.md`。

- 震动服务是 `cmd vibrator_manager`，没有 `cmd vibrator`。间隔用 `waveform -a -r 0`，停顿那一步幅度为 0。
- `cmd notification post` 的通知对象是 `sound=null`，不能当提示音。PGT-AN20 上它的 `shell_cmd` 通道仍挂着系统通知音。`notify.sh` 默认让手机上的「手机工位」发无声的下拉通知，覆盖最新一条或逐条保留由手机「首页 → 通知 → 电脑提醒 → 手机 → 显示方式」统一决定，旧的 `mode` 或 `--stack` 不再覆盖手机设置；`--shell` 仍用原来的 shell 通知与 `--stack` 参数。铃声仍走媒体音量。`tinyplay` 打不开声卡，也不能播 ogg。
- 震动模式下通知音量流被静音。`notify.sh` 默认让手机上的「手机工位」用 `USAGE_MEDIA` 播放「通知」里选的铃声；没选过就用系统通知铃声，选了静音则不播。下拉通知本身不发声。`--shell` 仍把铃声转成 PCM。改壳上的播放时编辑 `lib/notify-sound/` 里的源码，再跑 `lib/notify-sound/build.sh`。不要手改 `lib/notify-sound.dex`，也不要把 R8 的 jar 放进仓库。
- 系统提示不允许截屏的界面，`screencap` 和 scrcpy 都是黑的。不要加绕过 `FLAG_SECURE` 的脚本，也不要改系统框架。
- 闪光灯的灯节点 shell 写不了，`settings put secure flashlight_enabled` 也点不亮。`torch.sh` 用 `CameraManager`，调用进程退出后灯会灭，所以开着要留一个 `app_process`。档数读 `FLASH_INFO_STRENGTH_MAXIMUM_LEVEL`，这台后置是 4 档；`torch.sh` 不要把 4 写死成唯一合法范围。跟随声音时不要按拍 `setTorchMode(false)`：这会拆掉闪光灯会话，高通相机服务连续开关大约一分钟后会自己退出。短暂停顿保持上一档，安静大约 0.8 秒才关灯。`turnOnTorchWithStrengthLevel` 失败，或相机编号暂时失效时，不要退出监听进程，重新找闪光灯再试。见 `docs/torch.md`。

## 验证

统一本机检查用 `scripts/check.sh`：文档镜像、项目 Skill 软链接、shell/Python 语法、Python/Java/Go race/Swift 回归和 Mac 全量源码编译。它不连接手机、不启动真实网关、不使用发布签名；非 Mac 可用 `--core`，结果会明确排除 Swift。新增 Swift 测试要登记到 `lib/check.py`，漏登会失败。Android 资源和完整 APK 仍用下面的构建入口验证。CI 使用同一检查命令。

AI 调用 MCP 优先用已连接的工具，或 `scripts/mcp.sh call` 从标准输入提交 JSON-RPC，凭据由进程内部读取。`scripts/mcp.sh status --safe` 只读已有网关状态且不输出令牌；需要启动 MCP 时用 `scripts/mcp.sh >/dev/null`。默认启动、普通 `status` 及内部 `snapshot/watch` 的输出可能含授权头，不能直接贴到聊天、日志或仓库。

`scripts/status.sh` 只读，可以随时跑。`scripts/notify.sh`、`scripts/agent-notify-hook.sh`、`scripts/vibrate.sh`、`scripts/screenshot.sh`、`scripts/mirror.sh`、`scripts/record.sh`、`scripts/stay-awake.sh`、`scripts/torch.sh` 会让手机出声、震动、截屏、投屏、改息屏设置或开关闪光灯。用户或当前任务明确要求其中一件时，直接跑对应的那一个。没有这件要求时不要跑，包括不要为了看看环境而跑。改完某个脚本、要确认它还能用时，可以跑那一个。

`scripts/mcp.sh` 默认会打开手机 MCP 与本机网关；`status` 不打开服务，但会读取开关、刷新 adb 转发并更新用户配置里的本地令牌。`pair` 和 `unpair` 通过 adb 修改手机与 Mac 的远程配对资料；`desktop` 和 `forget-desktop` 只配置或清除 Mac 端，不需要 adb。需要用户要求读取/修改手机文件、使用 MCP 工具、配对远程通道或验证 MCP 服务时才运行相应命令。`stop` 需要 adb 在线，会关闭手机 MCP 与远程客户端但保留配对。`station_notify`、`station_stay_awake`、`station_clipboard_set`、`station_clipboard_configure`、`station_clipboard_exchange` 和 `station_file_open` 会出声、改设置、盖掉剪贴板或打开界面，没有明确要求时不要调用。剪贴板交换不当只读探测，也不自动重放。

`scripts/android.sh` 会在手机上安装「手机工位」；本地 adb 流程授予 `WRITE_SECURE_SETTINGS` 和所有文件访问（`MANAGE_EXTERNAL_STORAGE`），并可能把无线调试打开。没有明确要求时不要跑。改了 `lib/android/` 里的 Java 时跑 `lib/android/build.sh`：它在电脑上编 APK，并跑打开时机的测试，不碰手机。

shell 脚本用 `zsh -n`。改了 `lib/adb_mdns.py`、`lib/pair_qr.py`、`lib/torch_beat.py`、`lib/agent_notify_hook.py` 或 `lib/install_agent_notify.py` 时用 `python3 -m py_compile`。改了 `PlayPcm.java` 时跑 `lib/notify-sound/build.sh`。改了 `lib/torch/Torch.java` 时跑 `lib/torch/build.sh`。`lib/torch-audio/main.swift` 比已编译的程序新时，`torch.sh beat` 会自己重编。

<!-- agent-docs-sync:end -->
