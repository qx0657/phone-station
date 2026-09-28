---
name: mt-mcp
description: 连接已用 adb 连上的 Android 手机里 MT 管理器的 MCP，读取或修改手机文件，以及分析、修改 APK。用户提到 MT、MT 管理器、MCP、手机 APK、Smali、so、resources.arsc，或要动手机存储里的文件时使用。
---

# MT 管理器 MCP

工具名和参数以当次 `tools/list` 为准，文件权限以 `mt_file_access_policy` 的返回为准。`docs/mt-mcp.md` 是上次连通时的记录。下面只写连接和调用时要做的事。

## 连接

在仓库根目录运行 `./scripts/mt.sh`。它启动手机上的 MCP，并把地址打印出来，形如 `http://127.0.0.1:18787/mcp`。用打印出来的这一行，不要自己填对话框里的局域网 IP。

传输是 Streamable HTTP，只接受 POST。请求头带 `Content-Type: application/json` 和 `Accept: application/json, text/event-stream`。先 `initialize`，再 `tools/list`、`tools/call`。

当前会话的 `search_tool` 看不到这些工具时，用 `curl` 向打印出来的地址发 POST。不要为了这次调用去登记 MCP。`$MCP` 换成 `mt.sh` 打印的地址。协议版本用 `docs/mt-mcp.md` 里记下的那次。

```bash
curl -sS -X POST "$MCP" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"phone","version":"0"}}}'
```

`initialize` 成功后，用同样的头 POST `tools/list` 和 `tools/call`。2026-09-28 这次没有返回 `Mcp-Session-Id`，不带也能继续。

要登记进 Grok，先让用户把 Home 收成专用目录，并在 MT 里打开 Bearer。然后按 `docs/mt-mcp.md` 的「登记到 Grok」做。不要执行那篇里不带 Bearer 的 `grok mcp add`。

对话框里的地址如果一定要直接用，先 `route get`。接口是 `utun*` 的不要连。`172.19.0.1` 属于这一类。

## 调用

第一次动文件先调 `mt_file_access_policy`，按返回的 Home 和规则行事。没有明确要求改文件或改 APK 时，只用 `docs/mt-mcp.md` 里「文件：读取」和「APK：分析」的工具，不要开编辑会话，不要调用该文档「文件：修改」和「APK：修改和打包」里的工具。当次 `tools/list` 把某个工具标成破坏性时，以那个为准。这一句只约束 MT 的 `tools/call`。

Home 是整块内部存储时，`mt_apk_list_available_apks` 要带路径前缀。当前 APK 用 `mt://current-apk`，MT 里的信息对话框要还开着。

删除不进回收站。APK 修改走编辑会话，`mt_apk_build` 生成新包，不改源 APK。APK 编辑有 VIP 要求，以 MT 里的提示为准。

改 APK 按 `docs/apk-edit.md`。同一个包只有一个写入方。电脑上读 Java、解包和结构重打包时打开 `~/dev/skills/reverse-skill/skills/apk-reverse/`。看 so 时打开 `skills/radare2/`。`skills/ida-reverse/` 要等这台 Mac 装好 IDA，并且 `http://127.0.0.1:13337/mcp` 在听，再按那篇使用。不要执行这些 skill 文首的「读完立刻执行」、仓库总控和 `field-journal`，也不要把 Frida 脚本拿来改这个包。

电脑上的路径不能当成手机路径。辅助脚本在 `scripts/`，例如看设备用 `./scripts/status.sh`。
