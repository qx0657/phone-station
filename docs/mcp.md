# 手机工位 MCP

手机上「手机工位」自己的 MCP服务。现在这一项是内部存储里的普通文件。以后要加的工具做在这个应用里。日常用法见 [../README.md](../README.md) 的「文件」。

2026-10-01 在这台 Mac 和荣耀 PGT-AN20（MagicOS 9 / Android 15）上验证。文件读写当时的应用 `versionName` 是 6，界面上的「MCP服务」是 7。服务自报「手机工位」，协议 `2025-03-26`。只有 tools，没有 resources，也没有 prompts。

## 启动

在仓库根目录运行 `./scripts/mcp.sh`。它先走 `connect.sh`，把手机的 `8765` 转到本机 `18765`，再让「手机工位」把服务打开。打开走显式广播 `dev.phonestation.adbkeep/.McpControlReceiver`，动作 `dev.phonestation.adbkeep.MCP`，extra `on` 为 true；这条广播没送到时再 `am start-foreground-service`。服务应答之后打印两行：

```text
http://127.0.0.1:18765/mcp
Authorization: Bearer <这次的令牌>
```

这两个端口写在 `scripts/mcp.sh` 和 `FileMcpService` 里。令牌每次进程起来换一次，以当次打印或菜单栏里看到的为准，不写进仓库。`./scripts/mcp.sh stop` 用同一条广播把 `on` 写成 false，没送到时再 `am stopservice`，然后去掉这次转发。`./scripts/mcp.sh status` 不把它打开：`settings global phonestation_mcp` 不是 1 就去掉转发并打印 `off`；是 1 就转发，读得到令牌就打印上面两行，还没有令牌就只打印 `on`。

手机把「要开着」记在应用自己的偏好里，默认关。明确关掉才写成关。进程没了，或者直接 `am stopservice`，这项偏好还在；再打开应用，或开机广播，会再把服务拉起来。听着的时候 `settings global phonestation_mcp` 是 1，停掉或没听成是 0。菜单栏开着时大约每 5 秒读这一项：是 1 就做转发并读令牌，不是 1 就去掉转发。这次读取不跑 `connect.sh`。关掉应用里的「保持无线调试」不会停 MCP服务。下拉栏不另起一条：和无线调试保持共用渠道 `keep` 那条，里面写 MCP服务开还是关，不写地址。会话提醒仍占用 id 2 和 3。合并前单独那条的 id 4 会在升级时清掉。

传输是 Streamable HTTP，只接受 `POST /mcp`。请求头：

```text
Content-Type: application/json
Accept: application/json, text/event-stream
Authorization: Bearer <mcp.sh 打印的令牌>
```

`initialize` 成功之后就可以 `tools/list` 和 `tools/call`。没有 `Mcp-Session-Id`。没有 `id` 或 `id` 为 null 的通知回 HTTP 202。一次只处理一个连接。请求体上限 8 MiB，要带 `Content-Length`。别的路径或方法回 405，正文是「只接受 POST /mcp」。

工具失败写在结果里，`isError` 为真，正文是 `{"error":"…"}`。JSON 解析失败才是协议错误，例如 `-32700`。

本会话用 `curl` 完成握手。不要把这项登记进 Grok：令牌会换，adb 转发也跟着这次连接消失。

## 界面

手机上的「MCP服务」和菜单栏里的「MCP服务」是同一个开关，都写那项偏好。打开就在手机上把服务拉起来，关掉就停掉，并记住关掉。开着时手机上下面一行是手机本机地址 `http://127.0.0.1:8765/mcp`。关着时不写地址。这条地址只在手机上听。电脑经 adb 转到本机 `18765`，那一行和令牌在菜单栏这一页，或由 `./scripts/mcp.sh` 打印。令牌不出现在手机界面上：屏幕和辅助功能都能读到界面，日志只有 shell 能读。菜单栏没开着、也没再跑 `status` 或 `stop` 时，电脑上已经做好的转发会留到下一次。

设置里的「MCP说明」是给站在手机前的人看的短页，四段：怎么连上、能做的、做不到的、交接。工具名、参数和下面的验证记录不写在那一页。`versionName` 21 在这台上点开过，画面记在 [adb-keep.md](adb-keep.md)。

## 监听和令牌

服务只绑定手机上的 `127.0.0.1`。电脑经 adb 转发进来，不听局域网。

同一台手机上别的应用也能访问 `127.0.0.1`，所以每个请求都要带 Bearer。令牌是进程里的 16 字节随机数，打成 32 位大写十六进制，写进 logcat 标签 `StationMcp`，一行 `mcp token …`。`mcp.sh` 每次启动都会再打一次，方便脚本重读。shell 能读这条日志。普通应用读不到别的应用的日志。

令牌不放进 `Settings.Secure`。那项设置任何应用都能读，和本机监听放在一起就会被拿走。

Android 15 上，清单里没有 `exported` 的前台服务，shell 直接 `am start-foreground-service` 会被拒绝：`not exported from uid`。所以 `FileMcpService` 是 exported，类型是 `specialUse`。别的应用能把服务拉起来，读不到令牌。返回值是 `START_NOT_STICKY`。

## 权限

`./scripts/adb-keep.sh` 在两次 `pm grant` 之后再执行：

```text
appops set --uid dev.phonestation.adbkeep MANAGE_EXTERNAL_STORAGE allow
```

`pm grant` 授不上「所有文件访问」。授上之后 `appops get` 打印 `Uid mode: MANAGE_EXTERNAL_STORAGE: allow`。清单里的 `INTERNET` 用来在本机监听，安装时就有。

短信、通讯录、位置、相机、麦克风这些运行时权限没有授。它们到不了更多文件。

`MANAGE_EXTERNAL_STORAGE` 覆盖内部共享存储。Android 11 起，它到不了别的应用的 `Android/data`、`Android/obb`，也到不了 `/data/user/0/<其他包>`。那些目录在 adb 会话还在的时候用 shell 看。

## 路径

Home 是 `/storage/emulated/0`。`path` 空着就是这里。相对路径从这里算。绝对路径要落在这下面，`..` 走出去会得到「路径不在内部存储里」。只看词法，`/tmp/store-evil` 不会算进 `/tmp/store`。

应用自己的外部目录不开放，返回「这条路径不开放」：

- `/storage/emulated/0/Android/data/dev.phonestation.adbkeep`
- `/storage/emulated/0/Android/obb/dev.phonestation.adbkeep`

路径上每一段都不跟随链接。碰到链接是「不跟随链接」。

删除不进回收站。内部存储根，以及 `Android`、`Android/data`、`Android/obb` 这三层，删的时候返回「不能删除这一层目录」。

## 写入和读取

`targetVersion` 是 `<修改时间的毫秒>:<字节大小>`，来自上一次 `stat` 或读取。已有文件的写入必须带上这一次的值。对不上就拒绝，并给出当前值：「文件已变化，先重新读取。当前 targetVersion 是 …」。新建文件不要带它。

整文件写入先写到同目录的临时名 `名字.phonestation-<纳秒>`，再换上去。追加、补丁、截断改原文件。复制和移动的目标不能已经存在。移动一个目录时，不会把它并进自己里面。

文本上限 8 MiB。认编码的顺序是：UTF-8 BOM，UTF-16 LE/BE BOM，能按 UTF-8 来回的按 UTF-8，否则能按 GB18030 来回的按 GB18030，再不行就是「这个文件不是文本，用字节工具」。字节一页最多 65536。按文件名搜索是区分大小写的子串，不进压缩包，一次最多看 20000 个访问；到上限时 `truncated` 为 true。结果条数正好等于 `maxResults` 时，也可能标成截断。

写入、追加、补丁、截断、复制、移动和删除成功之后，结果里 `mediaScan` 为 true，并把变更过的路径交给系统媒体库。建目录不扫。一次最多交 64 个路径；目录很大、扫不全时 `mediaScanTruncated` 为 true。移动时旧路径和新路径都交。扫描是异步的，返回只表示已经提交。

## 常用目录

`station_file_access_policy` 的 `places` 是这六个目录，相对内部存储根。目录不在时，占用工具里 `exists` 为 false，不会为了统计去创建。

| 名字 | 相对路径 |
| --- | --- |
| 截图 | `Pictures/Screenshots` |
| 相机 | `DCIM/Camera` |
| 下载 | `Download` |
| 文档 | `Documents` |
| 电影 | `Movies` |
| 录音 | `Sounds` |

录音用 `Sounds`。这台 PGT-AN20 上系统录音在这一层，内部存储根下没有 `Recordings`。

`handoff.inbox` 是 `Download/手机工位/inbox`，`handoff.outbox` 是 `Download/手机工位/outbox`。这两个目录不会自动创建。要交接文件时，放进 inbox，处理完的放进 outbox。服务没有推送，电脑隔一会儿列一次目录。

`station_storage_summary` 报剩余空间和总空间，以及上面六个目录的字节数和文件数。单个目录最多看 20000 项，超出时该目录的 `truncated` 为 true，字节数是已经加过的部分。不跟随链接。

## 工具

参数以当次 `tools/list` 为准。下面是 2026-10-01 这版的名字。

### 读取

| 工具 | 作用 |
| --- | --- |
| `station_file_access_policy` | Home、规则、六个常用目录、交接目录，以及不开放的目录。动文件之前先调这个 |
| `station_storage_summary` | 剩余空间，以及六个常用目录各占多少 |
| `station_device_status` | 电量、是否在充电、响铃模式、Wi-Fi 是否连着、USB 调试和无线调试开关、息屏时间和充电时常亮、剩余空间。不读 Wi-Fi 名字 |
| `station_file_list` | 列出一个目录的直接内容。`path` 空着表示内部存储根 |
| `station_file_stat` | 类型、字节大小、修改时间，以及 `targetVersion` |
| `station_file_read_text` | 按行读文本，换行原样保留。默认 200 行，最多 2000 行 |
| `station_file_read_bytes` | 按偏移读原始字节，返回大写十六进制。一页最多 65536 字节 |
| `station_file_search` | 在目录下按文件名找普通文件 |
| `station_file_search_text` | 在一个文本文件里按字面量查找，带回行号和前后文 |

### 修改

| 工具 | 作用 |
| --- | --- |
| `station_file_write_text` | 把整个文件写成 UTF-8。新建时不带 `targetVersion` |
| `station_file_replace_text` | 在整个原文上做不重叠的字面替换，按原来的编码写回 |
| `station_file_append_text` | 按文件现有编码在末尾追加，不额外加换行 |
| `station_file_write_bytes` | 用十六进制覆盖整个文件，或新建 |
| `station_file_patch_bytes` | 对已有文件打 1 到 200 处等长补丁 |
| `station_file_append_bytes` | 在文件末尾追加字节 |
| `station_file_truncate_bytes` | 保留开头的若干字节。不会把文件撑大 |
| `station_file_copy` | 复制一个普通文件。目标不能已存在 |
| `station_file_move` | 移动或改名。目标不能已存在 |
| `station_file_create_directory` | 创建目录。缺的上一级会一起建 |
| `station_file_delete` | 永久删除一个普通文件 |
| `station_file_delete_directory` | 永久删除一个目录。`recursive` 为 true 时连里面的内容一起删 |
| `station_file_open` | 用系统查看器打开一个已有的普通文件。不改文件 |
| `station_stay_awake` | 保持亮屏的开或关。`on` 为 true 或 false，数值和 `stay-awake.sh` 相同 |
| `station_notify` | 发一条会话提醒。下拉通知和铃声与 `notify.sh` 相同 |
| `station_clipboard_set` | 把一段文字放进剪贴板，盖掉原来的内容 |

`annotations` 里，读取类的 `readOnlyHint` 为 true。会改掉或删掉已有内容的工具，`destructiveHint` 为 true。`copy`、`create_directory`、`station_file_open`、`station_stay_awake` 和 `station_notify` 会写出新东西或改手机状态，但 `destructiveHint` 是 false。`station_clipboard_set` 会盖掉剪贴板，`destructiveHint` 为 true。

`station_stay_awake` 只接受布尔值 `on`。true 把 `screen_off_timeout` 写成 2147483647，把 `stay_on_while_plugged_in` 写成 7。false 写成 60000 和 0。写完再读回，对不上就拒绝。

`station_notify` 要 `title` 和 `text`，不能是空的，分别最多 200 字和 4000 字。`mode` 省略时是 `replace`，原地更新同一条；`stack` 另发一条。`agent` 只认 `Grok`、`Claude`、`Codex`。它交给和 `notify.sh` 同一个接收器，并等到铃声结果：`sound` 是 `played`、`silent` 或 `failed`。没有铃声、通知权限没开，或 15 秒内没有结果，工具失败。

`station_clipboard_set` 的 `text` 不能是空的，最多 100000 字。

`station_file_open` 用内容地址把文件交给系统查看器。没有应用能打开这种类型时失败。系统不允许从后台直接打开时，下拉栏留一条「打开文件」，点一下再开；这条用安静通道，不响，id 是 5，标记是 `open`。返回里 `opened` 表示已经打开，`notified` 表示改留下了那一条。

## 在 PGT-AN20 上验证过的

2026-10-01，应用版本 6。`./scripts/adb-keep.sh` 增量安装成功，随后 `./scripts/mcp.sh` 打印了地址并完成 `initialize`。

- `appops get` 是 `Uid mode: MANAGE_EXTERNAL_STORAGE: allow`。日志是 `StationMcp: storage true`。
- `Download` 下列目录成功。其中一张已有的 PNG，前 8 字节是 `89504E470D0A1A0A`。
- 写入 `Download/phonestation-mcp-probe.txt`，内容是 `probe` 加一个换行。按 UTF-8 读回之后删除。再 `stat` 得到「没有这个文件」。这个探测文件已经不在。
- `Android/data` 和 `Android/obb` 返回「系统不允许访问这个路径」。
- `Android/data/ai.x.grok` 返回「没有这个文件」。同一路径上，shell 的 `ls` 仍能列出这个目录和里面的 `files/`。系统把别的应用的这个目录对「手机工位」藏成不存在。
- `/data/user/0/dev.phonestation.adbkeep` 返回「路径不在内部存储里」。
- 应用自己的外部目录返回「这条路径不开放」。

这次导出的前台服务能被 shell 拉起来。电脑上的 `JsonTest`、`FilePolicyTest`、`FileOpsTest`、`McpLoopbackTest` 在打包前通过。

同一天的 `versionName` 7：界面上「MCP服务」在开着时写出 `http://127.0.0.1:8765/mcp`，关着时只有「关着」。通知内容是「MCP服务开着」。

同一天的 `versionName` 9：MCP 不再单独占一条通知。服务开着时，渠道 `keep` 那条内容是「无线调试开 · MCP服务开」；停掉之后回到「无线调试开 · MCP服务关」。记录里只有这一条。

同一天的 `versionName` 20。`./scripts/adb-keep.sh` 增量安装成功，`versionName` 是 20。随后 `./scripts/mcp.sh` 完成 `initialize`，`tools/list` 里有原来的文件工具，加上 `station_storage_summary`、`station_device_status`、`station_stay_awake`、`station_notify`、`station_clipboard_set`、`station_file_open`。

- `station_file_access_policy` 的六个目录是截图、相机、下载、文档、电影、录音。录音的路径是 `/storage/emulated/0/Sounds`。交接目录是 `Download/手机工位/inbox` 和 `outbox`，没有创建。
- `station_device_status` 读到了电量、充电、响铃、Wi-Fi、两枚调试开关、息屏时间和剩余空间。当时响铃是 `vibrate`，Wi-Fi 连着，USB 调试和无线调试都开着。息屏已经是 2147483647，充电掩码已经是 7，所以 `stayAwake` 为 true。这次没有调用 `station_stay_awake`，设置没改。
- `station_storage_summary` 里这六个目录 `exists` 都是 true，`truncated` 都是 false。
- 写入 `Download/phonestation-mcp-probe.txt`，结果 `mediaScan` 为 true。媒体库里能查到这一行，`_data` 就是这个路径。删掉之后 `mediaScan` 仍为 true，`stat` 是「没有这个文件」，媒体库里这一行也没了。

`station_file_open`、`station_notify`、`station_clipboard_set` 这次没有在手机上调用。电脑上的 `FileOpsTest`、`StayAwakeTest`、`McpLoopbackTest` 在打包前通过。核对完跑了 `./scripts/mcp.sh stop`。

同一天的 `versionName` 29。增量安装成功。当时只有无线，没有改 `adb_wifi_enabled`，也没有重启。shell 广播打开之后 `settings global phonestation_mcp` 是 1，logcat 里有一行 32 位十六进制的 `mcp token`。接着 `am stopservice` 把这项写成 0，再打开应用它回到 1。再用广播关掉，打开应用之后仍是 0。`./scripts/mcp.sh status` 在关着时打印 `off`。`./scripts/mcp.sh` 打印了本机 `18765` 的地址和 `Authorization`，`./scripts/mcp.sh stop` 之后这项是 0。开机把「要开着」再拉起来这一步没有在这台上重启验证。谁可以发这条广播，见 [notify.md](notify.md)。
