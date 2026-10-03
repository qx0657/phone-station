---
name: phone-mcp
description: 通过手机工位 MCP 操作 Android 普通文件、剪贴板、远程截屏与控件，执行 Shizuku shell，安装 APK 或更新手机工位，以及配置本地/远程连接。用于这些手机侧操作；图库截图归类去重用 screenshot-cleanup，本机 adb 截屏用 scripts/screenshot.sh。
---

# 手机工位 MCP

组件与源码归属见 `docs/architecture.md`，故障恢复见 `docs/troubleshooting.md`，版本与部署状态见 `docs/releases.md`。中继源码统一在 `server/relay/`；Mac 20 先握手取得持久会话编号，远程未知结果仍不重放。

工具名和参数以当次 `tools/list` 为准。路径和范围以 `station_file_access_policy` 的返回为准。当前机制见 `docs/mcp.md`，限定日期与版本的连通验证见 `docs/history/mcp.md`。

## 连接

在仓库根目录操作。已运行时用 `./scripts/mcp.sh status --safe` 查看状态，它不输出令牌、不刷新 adb。当前任务需要启动 MCP 时运行 `./scripts/mcp.sh >/dev/null`，避免默认输出的授权头进入聊天记录。adb 不在线时，已配对且在线的远程通道仍可用。手机界面上的 `http://127.0.0.1:8765/mcp` 只在手机上监听。

有已连接的 MCP 工具时直接使用。否则用 `./scripts/mcp.sh call` 从标准输入提交一个带 `id` 的 JSON-RPC 请求；它在进程内部读取令牌、添加请求头，走已有统一网关，不启动服务，也不自动重做请求。先 `initialize`，核对返回的协议版本，再 `tools/list`、`tools/call`。

```bash
./scripts/mcp.sh call <<'JSON'
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"phone","version":"1"}}}
JSON
./scripts/mcp.sh call <<'JSON'
{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
JSON
```

Mac 19 起，可为独立 MCP 客户端签发 `status`、`files.read`、`files.write`、`shell` 权限令牌，管理入口为 `mcp.sh clients`，范围见 `docs/mcp-clients.md`。只有用户要求管理接入时才创建或撤销令牌；权限不足时说明缺少的权限，不自动改用主令牌。`mcp.sh call` 是本机主身份入口，不能用它验证受限客户端权限。

旧网关没有 `call` 时先按下文构建网关。`status`、`snapshot`、`watch`、`credentials` 的原始输出可能含令牌，不直接展示；也不要将令牌展开进 `curl -H` 等命令参数。传输、鉴权与手动接入说明见 `docs/mcp.md`。

`./scripts/mcp.sh stop` 需要 adb 在线，关闭手机 MCP 与远程通道、移除转发并保留配对。没有这次使用或验证 MCP 的要求时，不要为了看看环境而启动它。

## 调用

第一次动文件先调 `station_file_access_policy`。没有明确要求时，只用 `docs/mcp.md` 里「读取」的工具。打开文件、保持亮屏、发提醒、写剪贴板和改文件都要有明确要求。当次 `tools/list` 把某个工具标成破坏性时，以那个为准。

`places` 是截图、相机、下载、文档、电影、录音。`handoff` 的 inbox 和 outbox 在 `Download/手机工位/` 下，不会自动创建。要交接文件时用这两个目录，隔一会儿列一次。

已有文件的写入要带上一次读到的 `targetVersion`。新建文件不带。删除不进回收站。写入、删除、移动和复制的结果里 `mediaScan` 为 true 时，媒体库扫描已经提交。

普通文件工具不开放 `Android/data`、`Android/obb` 和 `/data/user/0/<其他包>`。应用自己的外部目录返回「这条路径不开放」。别的应用的 `Android/data/<包名>` 可能返回「没有这个文件」，shell 仍能列出时，目录还在，只是这个应用看不到。

## 剪贴板

读取手机当前文字用 `station_clipboard_get`；后台读取需要已经运行并授权的 Shizuku。它不返回标记为敏感的文字，锁屏时返回 `kind: locked`。`station_clipboard_state` 只读设置与内存预览。写入仍用 `station_clipboard_set`，需要用户明确要求。

用户要两端自动同步时，Mac 菜单栏与手机首页都有「共享剪贴板」页。新安装默认开启，开启共享时自动双向同步；另有默认关闭的「同步 Mac 图片到相册」，仅将新复制的 Mac 图片保存到手机相册并更新媒体库，不覆盖手机剪贴板。图片被跳过不显示暂停；旧版暂停状态可用「恢复自动同步」。界面不显示正文。MCP 可显式配置 automatic，仅开启 shared 时默认同时启用 automatic；首次连接、长时间断线和服务重启只建立基线，后续复制才同步。短暂切换先用 `station_clipboard_state` 的 `refresh: true` 只读核对版本，再恢复符合条件的新复制；结果未知的旧交换不重放。`station_clipboard_configure` 改设置，`station_clipboard_exchange` 是客户端交换并可能写手机的工具，不把它当只读探测，也不自动重放。文字最多 100000 个 UTF-16 单元；敏感或非文字内容不作为自动覆盖的目标。长说明在 `docs/clipboard.md`。

## 手机通知

手机所选应用通知同步到 Mac，入口是手机「首页 → 通知 → 手机通知 → Mac」和 Mac 首页「手机通知」。`station_notification_status` 只读状态和已选数量，不返回内容；配置工具仅改同步开关，应用白名单只能在手机上选，系统通知使用权与 Mac 显示权限由用户手动授予。`station_notification_poll` 是 Mac 专用的正文接收与心跳，非只读，不作为探测、不自动重放。默认关闭、默认不选应用，不自动授权或替用户勾选。细节见 `docs/notify.md`。

## Shell

用户明确要求 shell 或需要系统权限的操作时，优先 `./scripts/shell.sh '<手机命令>'`。脚本核实 Shizuku 与机型，协商 `station_shell_start` 后台任务，旧版才回退 `station_shell_exec`。直接调用 MCP 时先查 `station_shell_status`，生成并记录 32 位小写十六进制 `jobId` 后提交 `station_shell_start`，用 `station_operation_status` 查询。状态不可用时按 `reason` 处理；应用已经获准时沿用，没有启动要求时不擅自启动 Shizuku。

`command` 是手机上的 `/system/bin/sh -c` 命令；工作目录是 `/`。`timeoutMs` 默认 10000、最多 60000，输出默认最多 32768 字节、上限 65536。后台任务回执不代表任意后台子进程能长期存活，也不提供交互终端。只有任务 `completed` 后才检查 `result` 中的 `exitCode`、`timedOut`、`outputTruncated`、`outputIncomplete` 和 `identity`；shell UID 为 2000，root UID 为 0。只执行用户要求的动作，不能用 shell 绕过普通文件工具的范围去主动探索私有数据。

提交应答丢失、超时或重连后，只用原 `jobId` 查询（`shell.sh --job-status <编号>` 或 `station_operation_status`），不重新提交。`missing`、`result_unknown` 都不表示肯定没有执行，先核实实际结果。普通文件仍使用 `station_file_*`；通过 adb 启动的 Shizuku 也没有其他应用 `/data/user/0` 的访问权。详细边界见 `docs/mcp.md` 的「Shizuku shell」。

## 远程安装与 AI 更新

仓库内的远程入口需要先构建 Mac 网关与钥匙串助手；找不到网关时运行 `./scripts/build-mac-app.sh`，这一步不安装到手机。已安装 App 的随包入口不需要重新构建。

用户要求安装/升级 APK 或修改需求后更新手机应用时，优先 `./scripts/install-apk.sh <电脑上的APK>`。更新本仓库的手机工位用 `./scripts/android.sh --remote`；它先构建，再经已配对的中继上传、校验、安装和核对结果，不要求 adb 在线。普通命令可用 `./scripts/shell.sh '<手机shell命令>'`，不带 `adb shell` 前缀。

安装入口支持一个 APK 或完整 base + split 集合，保留数据升级，不自动卸载解决签名冲突。记录输出的安装任务编号。更新手机工位自身会短暂断线，助手会启动后台服务并默认打开主页；只恢复后台用 `install-apk.sh --no-open <APK>`。`verified` 只代表 APK 内容一致，`completed: true` 才能报告整个升级与自动恢复完成。启动失败或未确认时，即使安装成功也要单独说明，不因后来手动打开就声称自动恢复通过。脚本仅通过原任务查询等待重连；结果未知时先 `./scripts/install-apk.sh --status <任务编号>`，不得重复安装。远程不可用时 `--remote` 会停止；没有既有远程入口的首次安装才使用原 adb 流程。长说明在 `docs/remote-ops.md`。

电脑上的路径不能当成手机路径。

## 远程控件

用户要求远程截屏、保持亮屏或手电筒时，先只读调用 `station_controls_status`，核实 PGT-AN20、各项可用状态及原因。保持亮屏复用 `station_stay_awake`；截图和手电筒需要已经启动并授权的 Shizuku，不自动申请权限或启动它。

- 截屏：生成并记录 UUID `requestId`，去掉连字符作为 `jobId`，用 `station_screen_capture_start` 提交、`station_operation_status` 查询。任务完成后按结果的 path、size、targetVersion 分块读取 PNG，核对完整大小与 SHA-256 后保存到 Mac，再用 `station_screen_capture_release` 幂等清理同一编号。单次最多 32 MiB。丢失应答时先查原任务；任务记录缺失或结果未知时，用 `station_screen_capture_status` 查原 `requestId` 的现有 PNG，不重拍、不复用编号。仅旧版没有后台接口时才用 `station_screen_capture`。安全界面仍可能是黑屏。
- 手电筒：`station_torch` 的 `on` 为 true 开灯、false 关灯。手机端独立的 Shizuku UserService 持续持灯；普通远程 shell 不保留后台进程，不能照搬 `torch.sh` 的后台命令。手机工位或 Shizuku 停止、升级后灯会灭，重连不自动重开。
- 操作超时或断线时只读核实 `station_controls_status`，不自动重放开关或截图。投屏、录屏和灯光跟随声音仍要求本机 adb。

实现与验证见 `docs/remote-controls.md`。
