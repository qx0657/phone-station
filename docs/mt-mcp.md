# MT 管理器 MCP

这是手机工位的动手通道。手机上的 MT 管理器开 MCP 之后，电脑上的 AI 可以分析、修改 APK，也能读、改、删手机上的文件。官方说明在 <https://mt.cc/guide/ai/mcp.html>。下面的地址、权限和工具表，是 2026-09-28 在这台 Mac 和荣耀 PGT-AN20 上连通过的结果，换机先核对。

当时手机上的包是 `bin.mt.plus`，`versionName=2.26.9`，`versionCode=26091198`。服务自报 `MT MCP` `0.2.0`，协议 `2025-03-26`。只有 tools，没有 resources，也没有 prompts。

## 启动

在仓库根目录运行 `./scripts/mt.sh`。它先走 `connect.sh`。本机 `18787` 已经能看到 MCP 时，只保持转发。否则把 MT 调到前台，打开侧栏里的「MCP 服务」，点「启动」，再把手机的 `8787` 转到本机 `18787`，然后打印：

```text
http://127.0.0.1:18787/mcp
```

MCP 前台服务在清单里没有 `exported`。这台手机是 Android 15，shell 直接 `am start-foreground-service` 会被拒绝：`not exported from uid`。手机也没有 `su`。侧栏和对话框是能用的入口，按钮文字是「启动」和「停止」。对话框经常不在界面树里，脚本用截屏确认它已经打开，再点左下角那个按钮。`./scripts/mt.sh stop` 点「停止」，并去掉本机转发。

手机上的端口如果改过，不再是 `8787`，脚本等不到应答。改回 `8787`，或在 MT 里看当前端口。屏幕锁着时界面读不到，先解开。

## 这次怎么连上的

侧栏「工具 → MCP 服务」已经启动，端口 8787，路径 `/mcp`。浏览器打开地址会看到「MT MCP 服务正在运行」，HTTP 状态是 405，只接受 `POST`。

传输是 Streamable HTTP。请求头：

```
Content-Type: application/json
Accept: application/json, text/event-stream
```

`initialize` 成功之后就可以 `tools/list` 和 `tools/call`。这次没有返回 `Mcp-Session-Id`。`notifications/initialized` 会回 202，不发也能往下走。认证是关的：不带 `Authorization` 就能列出工具并调用。

`tools/list` 给出 49 个工具。只读调用验证过两个：`mt_file_access_policy` 返回了下面的权限；`mt_apk_list_workspaces` 返回空列表。

本会话用独立客户端完成了上面的握手。Grok 自己的 MCP 配置里还没有这项，所以对话里的 `search_tool` 还看不到这些工具。这时按 `.agents/skills/mt-mcp/SKILL.md` 用 HTTP 调用。登记进以后的会话见文末，并且要先收窄 Home、打开 Bearer。

## 地址

对话框里有四个地址。这台电脑上先看 `route get` 的接口，接口是 `utun*` 的不要用，Clash 会把包送进隧道。

| 对话框里的地址 | 2026-09-28 的结果 |
| --- | --- |
| 手机本机 `http://127.0.0.1:8787/mcp` | 电脑访问不到。无线 adb 在线时用下面的转发 |
| `http://192.168.0.107:8787/mcp` | 走 `en0`，直接通 |
| `http://172.19.0.1:8787/mcp` | 走 `utun4`。和无线调试页面上的 `172.19.0.1` 是同一类地址 |
| `http://10.53.114.142:8787/mcp` | 走 `utun4` |

手机 IP 会变。下次以对话框为准，并用 `route get <ip>` 确认接口。

无线 adb 已经在线时，不走局域网。`./scripts/mt.sh` 把手机的 `8787` 转到本机 `18787`，避开电脑自己的 `8787`。转发跟着这次 adb 连接，断开就没了。

## 这台手机现在的文件权限

官方默认是 Home 可读写，其他路径要在「设置 → 文件」里另加规则。这台手机当时的返回不是文档里的示例目录。

`mt_file_access_policy` 的结果：

- Home 是 `/storage/emulated/0`，也就是内部存储根。相对路径从这里算，APK 默认也输出到这里。
- 规则只有一条：路径 `/`，目标是目录，权限 `read_write`，状态为生效。
- MT 自己的目录永久拒绝，调用方改不了。稳定的有 `/data/data/bin.mt.plus`、`/data/user/0/bin.mt.plus`、`/data/user_de/0/bin.mt.plus`、`/storage/emulated/0/Android/data/bin.mt.plus`，另外还有一条会随安装变化的 `/data/app/.../bin.mt.plus-...`。

因此，MT 自己能够访问的路径，连上的 AI 当时也能读、改、删。规则只能在 MT 里改。Home 里面不能再加「只读」或「禁止访问」；想保护的文件放到 Home 外面，再单独授权。Home 必须在主内部共享存储里，不能设在 `Android/data` 下。

MT 已经有 root 或 Shizuku 时，允许的路径也会用到那些权限。

普通文件是直接改原文件，没有自动备份。删除不进回收站，也不能靠通配符。移动、删除或追加中途断了，文件可能已经变了一部分，重试前先看当前状态。

`mt_apk_list_available_apks` 在 Home 是整块内部存储时，空前缀会递归扫描。2026-09-28 用 30 秒超时没有返回。列 APK 时带上路径前缀，或者先在 MT 里点开 APK、停在信息对话框，让调用方使用「当前 APK」（`mt://current-apk`）。当前 APK 只代表这个对话框，不会因此放开它所在的整个目录。

## 能做什么

直接说目标和手机上的路径即可。电脑本地路径不能当成手机路径。工具名出现在调用记录里，一般不用手选。参数以当次 `tools/list` 为准，下面是这次实际返回的 49 个。

读到的文件内容、Smali 和日志会回到当前 AI 工具，并可能再送到它使用的模型服务。只开放愿意交出去的目录。

### 文件：读取

| 工具 | 作用 |
| --- | --- |
| `mt_file_access_policy` | 看 Home、规则和永久拒绝的目录。先调这个 |
| `mt_file_list` | 列目录。`path=""` 表示当前 Home |
| `mt_file_stat` | 类型、字节大小、修改时间，以及之后写入要用的 `targetVersion` |
| `mt_file_read_text` | 按页读文本，LF、CRLF、CR 原样保留 |
| `mt_file_read_bytes` | 按页读原始字节，返回大写十六进制。单页最多 65536 字节 |
| `mt_file_search` | 在允许的目录下找普通文件。条件是与关系。不进压缩包，不跟随链接 |
| `mt_file_search_text` | 在一个文本文件里按行搜索，带回行号和上下文 |

### 文件：修改

这些直接作用于手机上的原文件。表里后几项服务器标了破坏性操作。

| 工具 | 作用 |
| --- | --- |
| `mt_file_edit_text` | 按原编码改一个文本文件，或新建 |
| `mt_file_replace_text` | 在整个原文上做不重叠替换。写进去的内容不会再被搜到 |
| `mt_file_append_text` | 按文件现有编码追加，不转码，不加换行 |
| `mt_file_write_bytes` | 用十六进制整文件覆盖，或新建 |
| `mt_file_patch_bytes` | 对已有文件打 1 到 200 处等长补丁 |
| `mt_file_append_bytes` | 在原文件末尾追加字节 |
| `mt_file_truncate_bytes` | 保留开头 `[0, size)`，丢掉尾部。不会把文件撑大 |
| `mt_file_copy` | 复制一个普通文件。目标父目录要已存在 |
| `mt_file_move` | 移动或改名。目录不会并进已有目录 |
| `mt_file_create_directory` | 创建目录，缺的父目录一起建 |
| `mt_file_delete` | 永久删除一个普通文件 |
| `mt_file_delete_directory` | 永久删除目录。`recursive=true` 删掉整棵树 |
| `mt_file_copy_workspace_entry` | 在 APK 的普通 zip 条目和本地文件之间复制原始内容 |

已有文件写入前要先读，并把返回的绝对路径、编码和 `targetVersion` 原样带回去。链接、目录和特殊节点不会被读工具跟随。

### APK：分析

先 `mt_apk_open`。`path` 用 `mt://current-apk`、工作区路径 `mt://workspace/<id>`，或 APK 文件路径。`temporary=true` 适合一次性任务，用完调用 `mt_apk_close`。同一个 APK 再次打开可以复用工作区。

| 工具 | 作用 |
| --- | --- |
| `mt_apk_list_available_apks` | 列出能打开的 APK。Home 很大时加上前缀 |
| `mt_apk_list_workspaces` | 列出可以重新打开的非临时工作区。把返回的 `path` 交给 `mt_apk_open`，`sourcePath` 可能已经不存在 |
| `mt_apk_open` | 打开只读工作区，返回应用信息、Manifest 摘要、资源和 dex 概况 |
| `mt_apk_list` | 分页列出 zip 条目、dex 类或资源表条目 |
| `mt_apk_search` | 搜路径、AXML、资源、dex 名称、字符串和 Smali。so 符号和 so 字符串要单独搜 |
| `mt_apk_read_text` | 读文本 zip 条目、解码后的 AXML，或类、方法、字段的 Smali |
| `mt_apk_read_bytes` | 读一个 zip 条目的原始字节 |
| `mt_apk_dex_outline_class` | 一个类的字段和方法。locator 形如 `dex_class:Lcom/example/Foo;`，Java 点分名无效 |
| `mt_apk_dex_xref` | 查谁的指令引用了这个类、方法或字段 |
| `mt_apk_resource_read` | 按资源 locator 和具体变体读 `resources.arsc` |
| `mt_apk_resource_xref` | 查一个资源 id 在 dex、AXML、资源表里的引用。匹配的是 id，不是名字 |
| `mt_apk_read_signature` | 读原包证书的摘要、主体、颁发者、有效期和算法。不判断装不装得上 |
| `mt_apk_continue` | 翻页。只用于上一页结果里 `tool=mt_apk_continue` 的那个动作 |
| `mt_apk_native_inspect` | so 的 ELF 摘要：架构、依赖和各类条目的数量 |
| `mt_apk_native_read_items` | 读一类 ELF 明细的第一页。后面用 `mt_apk_continue` |
| `mt_apk_native_map_address` | 把虚拟地址或 so 内部的文件偏移映射到节 |
| `mt_apk_native_xref` | 某个 ELF 地址的静态直接引用。不是完整调用图 |
| `mt_apk_native_disassemble` | 反汇编一段可执行映射 |
| `mt_apk_native_function_cfg` | 一个函数内部的直接控制流 |

官方写明目前做不到的：把 dex 反编译成 Java；往资源表里加新的语言或词条（先用 MT 的 ARSC 编辑器）；so 的动态调试和完整 C/C++ 反编译。加固、动态加载、混淆或额外签名校验也会让结果不完整。静态引用不能代替装上真机跑一遍。Java 反编译、脱壳、so 的进一步阅读和结构重打包见 [apk-edit.md](apk-edit.md)。

APK 编辑有 VIP 要求，以 MT 里的提示为准。通用文件功能不需要 VIP。这次没有改 APK，没有验证会员状态。

### APK：修改和打包

修改记在编辑会话里，不改源 APK。一个工作区可以有多个会话。`mt_apk_build` 生成新 APK，默认写到 Home。可以要求签名或跳过签名；密钥在「MCP 服务 → 设置 → APK → APK 签名设置」。`mt_apk_edit_check` 只检查能不能打包，不产出文件。

构建不会创建父目录，也不允许覆盖源 APK。只给文件名时写在 Home；其他位置用绝对路径，目录和文件本身都要有读写权限。指定 `output/test.apk` 这种相对目录不行。

| 工具 | 作用 |
| --- | --- |
| `mt_apk_edit_open` | 从只读工作区开一个编辑会话 |
| `mt_apk_edit_text` | 改会话里的文本。先 `mt_apk_read_text`，带上 `targetVersion` |
| `mt_apk_edit_resource` | 用 `mt_apk_resource_read` 给出的完整 `valueXml` 改已有资源值 |
| `mt_apk_delete` | 按 locator 删除一项。服务器标为破坏性操作 |
| `mt_apk_patch_bytes` | 在一个 zip 条目里做等长字节替换 |
| `mt_apk_native_patch_instructions` | 用汇编改 so 里的逻辑。写入原来的那段地址，汇编较短时用该架构的 NOP 补满 |
| `mt_apk_native_patch_string` | 改 so 里的一处 UTF-8 字符串 |
| `mt_apk_edit_check` | 检查会话。`runBuildChecks=true` 时检查当前修改能否打包 |
| `mt_apk_build` | 把会话打成新 APK |
| `mt_apk_close` | 关掉工作区，并删掉它的分析数据和编辑会话。不删源 APK，也不删已经生成的新包 |

关掉工作区只会清 MCP 生成的数据。临时工作区用完就关。普通工作区的保留数量在「设置 → APK」里调，超出会清掉较久没用的。

Dex、资源表和二进制 XML 用上面的专用工具。不能把它们当成普通文件整段换进 APK。

## 登记到 Grok

当前会话里调用工具，走 `.agents/skills/mt-mcp/SKILL.md` 的 HTTP，不要为了这一次调用去登记。

登记会写入 `~/.grok/config.toml`，之后新会话里的 AI 都能用这套文件规则。2026-09-28 这台手机 Home 是整块内部存储，规则是 `/` 读写，认证是关的。按当时的权限登记之后，新会话可以删除手机上 MT 能碰到的文件，而且没有令牌。

不要执行下面这条。它是当时没开认证时试过的命令，不是登记步骤：

```bash
grok mcp add --transport http mt http://192.168.0.107:8787/mcp
```

登记的顺序：先在 MT 里把 Home 换成单独目录，在「设置 → 认证」里创建 Bearer 并打开认证，再登记。名称用 `mt`，传输选 HTTP。会话里的工具名会变成 `mt__mt_file_list` 这种形式。地址用当时对话框里走非 `utun*` 的局域网地址，或 `./scripts/mt.sh` 打印的本机转发地址。下面的 IP 是 2026-09-28 的例子：

```bash
grok mcp add --transport http mt http://192.168.0.107:8787/mcp \
  --header "Authorization: Bearer <令牌>"
```

只走 adb 转发时，先跑 `./scripts/mt.sh`，把 URL 换成它打印的地址，仍然带 Bearer。地址变了就 `grok mcp remove mt` 之后重新加。

令牌填在 `Authorization` 头里，值是 `Bearer` 加一个空格再加令牌。它和模型服务的 API 密钥不是同一个。认证不加密 HTTP，服务留在可信局域网里。不同令牌共用同一套文件规则。

登记之后新开一轮，或在 `/mcps` 里刷新。由云端服务器去连 MCP 的客户端，访问不到手机的局域网地址。MT 切到后台可能被系统停掉，可在「设置 → 通用」里开悬浮球。

## 和 Android CLI 的关系

`android` CLI 负责工程、模拟器、布局和官方文档，不连接 MT 的这个端口。安装和它适合做的事见 [android-cli.md](android-cli.md)。adb 仍然负责设备和端口转发。文件和 APK 内容走这里。改 APK 时电脑上补的步骤见 [apk-edit.md](apk-edit.md)。给 AI 的连接步骤在 `.agents/skills/mt-mcp/SKILL.md`。
