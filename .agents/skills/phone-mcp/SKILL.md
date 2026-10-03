---
name: phone-mcp
description: 连接 Android 手机上「手机工位」的 MCP，使用 adb 本地通道或自行配置并配对的远程通道。读取或修改内部存储里的普通文件，查看剩余空间和手机状态，用系统查看器打开文件，开关保持亮屏，发一条会话提醒，读取、写入或双向共享剪贴板，通过已授权的 Shizuku 执行远程 shell，或上传 APK 安装升级、在 AI 修改需求后远程更新手机工位。用户要操作手机文件、远程执行命令、安装更新 APK 或要这些手机侧动作时使用。截取当前画面用 scripts/screenshot.sh，图库里已有的截图走 screenshot-cleanup。
---

# 手机工位 MCP

工具名和参数以当次 `tools/list` 为准。路径和范围以 `station_file_access_policy` 的返回为准。`docs/mcp.md` 是上次连通时的记录。

## 连接

在仓库根目录运行 `./scripts/mcp.sh`。它打开统一网关，打印电脑上的 MCP 地址和 `Authorization: Bearer …`；`./scripts/mcp.sh status` 还显示当前通道。使用当次打印的地址和令牌。adb 不在线时，已配对且在线的远程通道仍可用。网关令牌保存在本机用户配置，手机本地令牌随服务重启更换，由脚本刷新。手机界面上的 `http://127.0.0.1:8765/mcp` 只在手机上监听。

传输是 Streamable HTTP，只接受 POST `/mcp`。请求头带 `Content-Type: application/json`、`Accept: application/json, text/event-stream`，以及打印出来的 Authorization。先 `initialize`，再 `tools/list`、`tools/call`。协议版本用 `docs/mcp.md` 里记下的那次。

当前会话的 `search_tool` 看不到这些工具时，用 `curl` 向打印出来的地址发 POST。`$MCP` 和 `$TOKEN` 换成 `mcp.sh` 打印的两行。不要把凭据写进仓库、聊天记录或命令输出；统一入口与手机本地令牌是不同的凭据。

```bash
curl -sS -X POST "$MCP" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"phone","version":"0"}}}'
```

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

用户明确要求 shell 或需要系统权限的操作时，先调 `station_shell_status`，再用 `station_shell_exec`。状态不可用时，按 `reason` 处理：安装、启动 Shizuku，或在手机工位权限页授权。应用已经获准时沿用，不重复索要授权；没有启动要求时不要擅自启动。远程请求本身不会弹授权框。

`command` 是手机上的 `/system/bin/sh -c` 命令；工作目录是 `/`。`timeoutMs` 默认 10000、最多 60000，`maxOutputBytes` 是 stdout 和 stderr 合计保留的字节数，默认 32768、最多 65536。没有交互输入，不保留后台任务。检查 `exitCode`、`timedOut`、`outputTruncated` 和 `identity`；shell UID 为 2000，root UID 为 0。`station_shell_exec` 可能修改系统或删除文件，只执行用户要求的动作，不能用它绕过普通文件工具的范围去主动探索私有数据。

命令超时、服务停止、授权撤销或网络中断后，操作可能已经生效，先只读核实，不能自动重做。普通文件仍使用 `station_file_*` 工具；通过 adb 启动的 Shizuku 也没有其他应用 `/data/user/0` 私有数据的访问权。详细边界见 `docs/mcp.md` 的「Shizuku shell」。

## 远程安装与 AI 更新

仓库内的远程入口需要先构建 Mac 网关与钥匙串助手；找不到网关时运行 `./scripts/build-mac-app.sh`，这一步不安装到手机。已安装 App 的随包入口不需要重新构建。

用户要求安装/升级 APK 或修改需求后更新手机应用时，优先 `./scripts/install-apk.sh <电脑上的APK>`。更新本仓库的手机工位用 `./scripts/android.sh --remote`；它先构建，再经已配对的中继上传、校验、安装和核对结果，不要求 adb 在线。普通命令可用 `./scripts/shell.sh '<手机shell命令>'`，不带 `adb shell` 前缀。

安装入口支持一个 APK 或完整 base + split 集合，保留数据升级，不自动卸载解决签名冲突。记录输出的安装任务编号。更新手机工位自身会短暂断线，助手会启动后台服务并默认打开主页；只恢复后台用 `install-apk.sh --no-open <APK>`。`verified` 只代表 APK 内容一致，`completed: true` 才能报告整个升级与自动恢复完成。启动失败或未确认时，即使安装成功也要单独说明，不因后来手动打开就声称自动恢复通过。脚本仅通过原任务查询等待重连；结果未知时先 `./scripts/install-apk.sh --status <任务编号>`，不得重复安装。远程不可用时 `--remote` 会停止；没有既有远程入口的首次安装才使用原 adb 流程。长说明在 `docs/remote-ops.md`。

电脑上的路径不能当成手机路径。
