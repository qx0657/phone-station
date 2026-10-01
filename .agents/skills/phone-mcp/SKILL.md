---
name: phone-mcp
description: 连接已用 adb 连上的 Android 手机上「手机工位」的 MCP。读取或修改内部存储里的普通文件，查看剩余空间和手机状态，用系统查看器打开文件，开关保持亮屏，发一条会话提醒，或把文字放进剪贴板。用户要读、改、搜、复制、移动或删除手机存储里的文件，或要这些手机侧动作时使用。截取当前画面用 scripts/screenshot.sh，图库里已有的截图走 screenshot-cleanup。
---

# 手机工位文件 MCP

工具名和参数以当次 `tools/list` 为准。路径和范围以 `station_file_access_policy` 的返回为准。`docs/mcp.md` 是上次连通时的记录。

## 连接

在仓库根目录运行 `./scripts/mcp.sh`。它打开手机上的 MCP服务，并打印两行：电脑上的转发地址，以及 `Authorization: Bearer …`。用这两行。令牌随进程重起更换。手机界面上的地址是本机 `http://127.0.0.1:8765/mcp`，电脑访问不到。

传输是 Streamable HTTP，只接受 POST `/mcp`。请求头带 `Content-Type: application/json`、`Accept: application/json, text/event-stream`，以及打印出来的 Authorization。先 `initialize`，再 `tools/list`、`tools/call`。协议版本用 `docs/mcp.md` 里记下的那次。

当前会话的 `search_tool` 看不到这些工具时，用 `curl` 向打印出来的地址发 POST。`$MCP` 和 `$TOKEN` 换成 `mcp.sh` 打印的两行。不要把这项登记进 Grok：令牌会换，转发也跟着这次 adb 连接消失。

```bash
curl -sS -X POST "$MCP" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"phone","version":"0"}}}'
```

`./scripts/mcp.sh stop` 停掉服务并去掉转发。没有这次读或改文件的要求时，不要为了看看环境而启动它。

## 调用

第一次动文件先调 `station_file_access_policy`。没有明确要求时，只用 `docs/mcp.md` 里「读取」的工具。打开文件、保持亮屏、发提醒、写剪贴板和改文件都要有明确要求。当次 `tools/list` 把某个工具标成破坏性时，以那个为准。

`places` 是截图、相机、下载、文档、电影、录音。`handoff` 的 inbox 和 outbox 在 `Download/手机工位/` 下，不会自动创建。要交接文件时用这两个目录，隔一会儿列一次。

已有文件的写入要带上一次读到的 `targetVersion`。新建文件不带。删除不进回收站。写入、删除、移动和复制的结果里 `mediaScan` 为 true 时，媒体库扫描已经提交。

`Android/data`、`Android/obb` 和 `/data/user/0/<其他包>` 不在这个服务里。应用自己的外部目录返回「这条路径不开放」。别的应用的 `Android/data/<包名>` 可能返回「没有这个文件」，shell 仍能列出时，目录还在，只是这个应用看不到。

电脑上的路径不能当成手机路径。
