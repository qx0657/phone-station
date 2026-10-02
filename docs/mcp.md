# 手机工位 MCP

手机上「手机工位」自己的 MCP 服务，提供普通文件、手机状态、亮屏、提醒、剪贴板，以及通过已授权 Shizuku 执行 shell 的工具。以后要加的工具做在这个应用里。日常用法见 [../README.md](../README.md) 的「文件」。

既有本地 MCP 在 2026-10-01 于这台 Mac 和荣耀 PGT-AN20（MagicOS 9 / Android 15）上验证。2026-10-02 已在深圳阿里云部署中继，更新 Mac 和手机应用并完成配对；经公网中继完成 `initialize`、列出 25 个工具和只读手机状态调用。

## 启动

在仓库根目录运行 `./scripts/mcp.sh`。它启动本机统一网关 `127.0.0.1:18765`，保留手机 MCP 的 `127.0.0.1:8765`，并把 adb 转发放在本机 `18766`。有 adb 时，脚本用 `connect.sh` 整理现有连接、启用手机 MCP、读取本地 Bearer 并完成 `initialize`；adb 不可用时，已有远程客户端在线也能返回网关地址。输出包含：

```text
http://127.0.0.1:18765/mcp
channel=local local=<毫秒>ms remote=<毫秒>ms
Authorization: Bearer <本机网关令牌>
```

`18765` 是调用方固定访问的统一入口；`18766` 只供 adb 转发。Mac 网关令牌随机生成，保存在用户配置目录的权限为 `0600` 的配置文件中，随 `mcp.sh` 输出以便调用方访问；它不是手机服务令牌。手机本地 MCP 令牌仍会随服务进程重启而变化，只由 adb 从受系统 DUMP 权限保护的服务诊断入口读取，并交给本机网关。远程中继凭据保存在 macOS 钥匙串。

网关用无副作用的 `ping` 独立探测两条通道：本地每秒一次、超时 1 秒，远程每 2 秒一次、超时 3 秒。远程探测或业务调用不会挡住本地断线检测。只有一条可用时立即使用该通道。两条都可用时优先 adb；本地延迟连续三次高于 500 毫秒且远程延迟至少低 40% 时改走远程。因本地断线改走远程后，本地连续三次低于 150 毫秒、且切换已过 3 秒就可恢复；因持续高延迟选择远程时，仍需本地连续六次低于 150 毫秒和 60 秒冷却，避免来回切换。无线 adb 保持原状；远程通道承接本应用 MCP 工具，其中 shell 命令通过手机上已启动并授权的 Shizuku 执行。截屏、投屏和录屏脚本仍使用本地 adb；远程 shell 与安装入口见 [remote-ops.md](remote-ops.md)。

本地派发前先做 `ping`，转发还接受 TCP 但手机已经不可达时，实际操作直接走可用远程通道。检测到本地断线会取消仍在等响应的本地请求；`ping`、`initialize`、`tools/list` 和明确列入白名单的只读工具可以自动转到远程重试。读取手机剪贴板的 `station_clipboard_get` 与客户端交换都不在重放白名单中；具体工具不能仅凭 `readOnlyHint` 判断是否会重试。文件修改、通知、剪贴板、打开文件、保持亮屏和未知工具均不自动重放，结果不明时报告需要核实。已取消的旧探测也不能把通道重新报成在线。

`./scripts/mcp.sh stop` 需要 adb 在线，通过 `McpControlReceiver` 关闭手机 MCP 与远程开关，再移除转发；配对资料会保留。`./scripts/mcp.sh status` 不启动服务，也不跑 `connect.sh`，只刷新已有 adb 转发信息并报告网关探测到的通道。读取手机服务开关、刷新转发和读取令牌的 adb 命令各有 2 秒超时。菜单栏每秒通过内部 `snapshot` 命令读取网关 JSON 状态；adb 转发与令牌另行在后台刷新，其他操作进行中也会继续刷新连接显示。远程在线时，首页和菜单栏图标也显示已连接，只有远程可用时标明「远程连接」并禁用 adb 操作。手机首页与常驻通知同样把实际远程连接计入已连接，开关打开或已配对本身不算。

远程中继由用户配置，默认没有服务器地址或凭据。手机「远程中继设置」与 Mac「MCP 服务 → 远程中继」可独立保存资料，不需要 adb。两端使用同一 HTTPS 地址和 SPKI 指纹，分别保存手机、电脑两枚不同令牌。手机点「保存并连接」，Mac 点「保存 Mac 配置」。地址与指纹回填，令牌隐藏且不回填。Mac 勾选「同时配置手机」可通过 adb 一次配置两端；这条流程以及 `pair`、`unpair` 才要求 adb 在线。也可以使用脚本：

```sh
./scripts/mcp.sh desktop https://relay.example.com '<SPKI-SHA256>'
./scripts/mcp.sh forget-desktop
./scripts/mcp.sh pair https://relay.example.com '<SPKI-SHA256>'
./scripts/mcp.sh profile
./scripts/mcp.sh unpair
```

示例域名是占位符，使用时换成自己的中继。地址支持 HTTPS 域名、IPv4、方括号 IPv6，可省略端口（443），也可带反向代理的路径前缀；末尾斜杠会去掉。不能包含账号、查询参数、片段或 `.`、`..` 路径段。证书指纹是服务器叶证书公钥 SPKI 的 SHA-256，64 位十六进制；不是整个证书的 SHA-256。Mac 与手机使用相同的地址校验规则。

`desktop` 只保存此 Mac 的配置；隐藏输入一枚电脑令牌，`--stdin` 从标准输入读取一行，写入成功后启动网关并在后台探测。保存成功与远程已连接分别显示，不把配置成功当作连接确认。`forget-desktop` 删除 Mac 远程配置和钥匙串条目，不修改手机。本地 adb 配置仍保留。

手机配置页在本应用进程里调用 `RemoteStore.configure`，令牌由 Android Keystore 加密。手机保存后开启 MCP 与远程通道；地址和指纹未改变时，空令牌保留原加密凭据，更换任意一项或首次配置则必须重新输入手机令牌。令牌字段禁用保存实例状态和自动填充，页面离开后清空输入。清除手机配置需在应用内确认，不改 Mac 配置。

`pair` 隐藏提示输入服务器分别为手机和电脑生成的两枚不同 64 位十六进制令牌。菜单栏通过 `pair … --stdin` 把两枚令牌各一行写入标准输入；不放进 Mac 命令参数或临时文件。电脑端令牌存进 macOS 钥匙串，手机端令牌经显式广播 `PhoneRelayControlReceiver` 写入 Android Keystore 加密的偏好；两端还会保存 HTTPS 地址和服务器公钥 SPKI SHA-256 pin。提交凭据前先只读检查手机是否支持配置确认；旧手机版本没有确认码时需先更新手机应用，不发送新配对资料。手机确认新配置成功后才更新电脑配置。电脑保存失败时，脚本明确报告手机已变更、电脑仍保留原配置，提示检查钥匙串和配置目录后用相同资料重试；菜单栏保留刚填写的地址与指纹便于重试。令牌不会进入仓库或应用日志。手机远程开关与 MCP 服务同时打开。`profile` 不打开服务，也不访问钥匙串，只返回配置中的地址、指纹和是否已有配对资料；Mac 文件为 `~/Library/Application Support/Phone Station/remote.json`。`unpair` 需要 adb 在线，会删除手机端配对凭据、电脑端钥匙串令牌和网关中的地址与 pin，不停掉仍开启的本地 MCP。

新安装不预设中继，升级保留用户已有配置。更换地址或凭据会使 Mac 丢弃旧通道的在线状态，重新探测；旧请求的迟到响应不能把新通道报成在线。手机退出旧轮询并启用新配置，每个客户端固定自己的地址与凭据，已经执行的操作结果不会发给另一台服务器。Mac 每次配对使用独立的钥匙串条目，旧请求不会读到新服务器的令牌；没有配对版本标记的旧配置仍从原钥匙串条目读取。配置写入加文件锁，避免后台刷新 adb 令牌覆盖新配对。

首页「MCP 服务」下的「远程通道」是状态入口，显示未配置、已关闭、连接中或已连接，点击进入远程中继页。远程通道的开关在配置页上方，未配置时禁用，保存资料后可独立开关；打开远程通道时也会打开 MCP 服务。远程客户端用 HTTPS 长轮询该中继，再在手机进程内调用同一个 `McpProtocol`，所以文件权限与本地 MCP 相同。远程访问依赖中继已部署、Mac 网关已连接；任一尚未就绪时仍走原 adb 通道。中继能读取转发的请求和结果，应使用自己部署或信任的服务。

手机监听默认网络变化，Wi-Fi 与蜂窝切换、或 VPN 底层网络改变时请求结束旧长轮询、唤醒退避并使用新的默认网络重连。手机轮询请求带 `waitMs=2000`，没有操作时中继在 2 秒后返回空闲响应，读取超时为 5 秒；旧蜂窝网络仍可用、关闭连接未能立即唤醒读取时，也不再等待原来的 25 秒空闲轮询。已执行操作只重传同一操作 ID 的结果，不再执行工具。中继允许同一已认证手机接替旧的等待轮询，避免旧 Wi-Fi TCP 未关闭时阻塞新网络；接替不重派已运行操作。需要远程已配对、开关开启，且蜂窝或其他默认网络能访问中继。没有可用网络时无法完成切换。

手机把 MCP 与远程通道的开关记在应用偏好里。MCP 服务本地监听失败时，只要远程配对已开启，前台服务仍会启动手机长轮询。进程退出或重启后，开机广播会按偏好恢复服务。菜单栏检查走本机网关状态，不会触发 `connect.sh`。关掉应用里的「保持无线调试」不会停 MCP 服务。下拉栏不另起一条：和无线调试保持共用渠道 `keep` 那条，里面写 MCP 服务开还是关，不写地址。会话提醒仍占用 id 2 和 3。合并前单独那条的 id 4 会在升级时清掉。

传输是 Streamable HTTP，只接受 `POST /mcp`。请求头：

```text
Content-Type: application/json
Accept: application/json, text/event-stream
Authorization: Bearer <mcp.sh 打印的令牌>
```

当前 `initialize` 返回协议版本 `2025-03-26`，`serverInfo.name` 为「手机工位」，`serverInfo.version` 为手机 APK 的版本名。成功之后就可以 `tools/list` 和 `tools/call`；实际工具集合以这次的 `tools/list` 为准。没有 `Mcp-Session-Id`。没有 `id` 或 `id` 为 null 的通知回 HTTP 202。手机服务请求体上限 8 MiB；中继与桌面网关上限 18 MiB。别的路径或方法回 405，正文是「只接受 POST /mcp」。

工具失败写在结果里，`isError` 为真，正文是 `{"error":"…"}`。JSON 解析失败才是协议错误，例如 `-32700`。

调用方始终使用 `mcp.sh` 打印的统一网关地址和令牌。adb 转发消失时，已配对且在线的远程通道仍可继续提供 MCP。手机本地令牌重启后会换，本机网关令牌则保存在用户配置中。

## 中继兼容要求

中继地址是服务根地址，客户端在其后拼接 `/v1/phone/poll`、`/v1/phone/result` 和 `/v1/desktop/call`，三者均为带角色 Bearer 令牌的 HTTPS POST、JSON 请求。手机与电脑角色要分别鉴权；TLS 公钥必须匹配配置的 SPKI 指纹。本仓库包含 Mac 网关与 Android 客户端，中继服务端源码目前不在本仓库，不能仅把网关当作公网中继部署。

手机轮询请求为 `{"waitMs":2000}`，空闲时返回 `{"operationId":null}`；有操作时返回 `{"operationId":"<唯一ID>","payload":<MCP请求>}`。手机执行后向 result 提交 `{"operationId":"<同一ID>","response":{"status":<HTTP状态>,"body":<MCP结果>}}`。没有正文的通知结果可省略 `body`。电脑调用的请求为 `{"operationId":"<唯一ID>","payload":<MCP请求>}`，中继返回 `{"status":<HTTP状态>,"body":<MCP结果>}`。

相同操作 ID 的请求必须去重，并保留已派发操作的结果状态。手机结果提交可重传；已执行工具不能再执行一次。手机新轮询可接替旧等待轮询，不能重派正在运行的操作；电脑请求取消或超时后，已运行修改操作的结果不明时不能自动重做。中继与桌面网关正文上限为 18 MiB，手机本地 MCP 为 8 MiB。

## 界面

手机上的 MCP 服务由首页开关、菜单栏「MCP服务」页或本机脚本协调启停。手机上关闭它会同时关闭远程开关；远程中继页的「启用远程通道」可以单独开关，且打开时会确保 MCP 服务开着。MCP 本地监听时，手机「接入信息与说明」页显示 `http://127.0.0.1:8765/mcp`，这条地址只在手机上听。电脑固定访问统一网关 `http://127.0.0.1:18765/mcp`；菜单栏页显示当前通道和接入地址，提供「复制 Authorization」，不展示令牌明文；`./scripts/mcp.sh` 打印地址与授权头。令牌不出现在手机界面或应用日志中；电脑经 adb 读取受 DUMP 权限保护的诊断入口。菜单栏没开着、也没再跑 `status` 或 `stop` 时，已经建立的 adb 转发会留到下一次。

首页「接入信息与说明」是给站在手机前的人看的短页，四段：怎么连上、能做的、做不到的、交接。工具名、参数和下面的验证记录不写在那一页。`versionName` 21 在这台上点开过，画面记在 [adb-keep.md](adb-keep.md)。

## 监听和令牌

服务只绑定手机上的 `127.0.0.1`，不听局域网。Mac 本机网关也只绑定 `127.0.0.1:18765`。

同一台手机上别的应用也能访问 `127.0.0.1`，所以手机本地 MCP 每个请求仍要带 Bearer。令牌是进程里的 16 字节随机数，打成 32 位大写十六进制。服务启动不再把令牌写入 logcat；脚本只用 `dumpsys activity service dev.phonestation.adbkeep/.FileMcpService` 刷新令牌，不依赖日志留存。这条服务诊断入口受系统 DUMP 权限保护，普通应用不能调用，不要把完整输出贴进聊天或仓库。本机网关只从 adb 读取这个令牌；远程手机客户端另用进程内的随机令牌调用现有 MCP 分发器，不把本地令牌送到中继。

令牌不放进 `Settings.Secure`。那项设置任何应用都能读，和本机监听放在一起就会被拿走。

Android 15 上，清单里没有 `exported` 的前台服务，shell 直接 `am start-foreground-service` 会被拒绝：`not exported from uid`。所以 `FileMcpService` 是 exported，类型是 `specialUse`。别的应用能把服务拉起来，读不到令牌。返回值是 `START_NOT_STICKY`。

## 配对和重连的兼容处理

2026-10-02 在 PGT-AN20 和本机 Mac 上遇到并修正：

- Android Keystore 开启随机加密要求时，AES-GCM 的 IV 必须由 `cipher.init(ENCRYPT_MODE, key)` 生成，再用 `getIV()` 保存；手工提供 IV 会使配对失败。
- 没有访问组 entitlement 的临时签名 Mac 命令行助手不能使用 Data Protection Keychain（错误 `-34018`），现用用户的登录钥匙串。助手固定放在已安装 App 中，查询和存储都通过 Security API。
- `mcp.sh status` 从受 DUMP 权限保护的服务诊断读取当前本地令牌，重启或日志淘汰后仍可恢复 adb 转发。
- 网关把远程调用串行交给中继，业务调用期间跳过竞争的健康探测。中继收到取消时，未派发请求直接过期，已派发 `ping` 标为未知并释放；已派发的其他操作保留原 ID 和结果状态，不能自动重做。

## 权限

`./scripts/android.sh --adb` 的本地安装流程在两次 `pm grant` 之后再执行；远程更新沿用已有权限：

```text
appops set --uid dev.phonestation.adbkeep MANAGE_EXTERNAL_STORAGE allow
```

`pm grant` 授不上「所有文件访问」。授上之后 `appops get` 打印 `Uid mode: MANAGE_EXTERNAL_STORAGE: allow`。清单里的 `INTERNET` 用来在本机监听和连接已配置的中继，安装时就有。

短信、通讯录、位置、相机、麦克风这些运行时权限没有授。它们到不了更多文件。

`MANAGE_EXTERNAL_STORAGE` 覆盖内部共享存储。Android 11 起，它到不了别的应用的 `Android/data`、`Android/obb`，也到不了 `/data/user/0/<其他包>`。这台 PGT-AN20 上 shell 能列出其他应用的外部目录；其他应用的 `/data/user/0` 私有数据仍不对 shell 开放。

### Shizuku shell

`station_shell_status` 只读检查 Shizuku 是否安装、运行及授权，返回 `available`、`state`、`uid`、`identity`、`serverVersion` 和不可用原因 `reason`。不启动 Shizuku，也不弹授权框。要执行命令，先在手机上启动 Shizuku 13 或更新版，再到手机工位的「权限 → Shizuku」允许使用；已保存的授权沿用。非 root 手机重启后需要重新启动 Shizuku。

`station_shell_exec` 在本地 MCP 和远程中继使用同一个执行端。手机通过 `bindUserService` 创建特权进程，调用方 UID 必须是手机工位的应用 UID，执行进程 UID 必须是 `2000`（shell）或 `0`（root）。没有服务或授权时拒绝执行，不回退到普通应用 UID。中继令牌负责远程接入，Shizuku 授权负责手机执行，两者互不替代。通过 adb 启动的 Shizuku 仍不能访问其他应用的 `/data/user/0/<包名>`；已有文件工具的范围保持原样。

参数：

| 参数 | 范围与默认值 |
| --- | --- |
| `command` | 必填，交给 `/system/bin/sh -c`，最多 16384 字，不能有 NUL |
| `timeoutMs` | 100–60000 毫秒，默认 10000；绑定执行服务另有 5 秒超时 |
| `maxOutputBytes` | stdout 与 stderr 合计保留 1–65536 字节，默认 32768 |

返回 `stdout`、`stderr`、`exitCode`、`timedOut`、`outputTruncated`、`durationMs`、`uid`、`identity`。输出按 UTF-8 解码，截断位置或二进制内容可能出现替代字符。达到输出上限后仍排空两个流，避免管道阻塞。输出读取失败另报 `outputIncomplete: true`；进程未能退出时 `exitCode` 为 null。

命令在 `/` 下运行，标准输入立即关闭，没有交互终端。用 `setsid` 建立独立进程组，结束或超时会终止该组内的子进程，每次调用后移除 UserService，不保留后台任务；命令主动脱离该进程组的进程不保证被清理。超时不撤销已经生效的修改。服务停止、授权撤销或网络断开时，先核实结果，不自动重做。工具标为非只读、具有破坏性，连 `id` 这种只读命令也不会加入自动重放白名单；只读状态工具允许重试。

本地 HTTP 用有上限的请求线程池，工具调用仍串行执行，`ping` 不等待长 shell 命令，避免网关因忙碌误判断线。原有文件、亮屏、通知等工具继续使用应用自身权限。

电脑侧 `scripts/shell.sh` 直接经中继调用这个 shell 工具，不连接 adb；`scripts/install-apk.sh` 复用同一组文件和 shell 工具，实现 APK 校验、安装 session、应用自身更新后的结果查询。安装助手是明确需要跨应用重启继续运行的有界任务；普通 shell 请求仍按原来的进程组规则清理。步骤与边界见 [remote-ops.md](remote-ops.md)。

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

参数以当次 `tools/list` 为准。下面包含 `versionName` 37 新增的 shell 工具。

### 读取

| 工具 | 作用 |
| --- | --- |
| `station_file_access_policy` | Home、规则、六个常用目录、交接目录，以及不开放的目录。动文件之前先调这个 |
| `station_storage_summary` | 剩余空间，以及六个常用目录各占多少 |
| `station_device_status` | 在线状态、连接类型、远程是否在线，以及电量、是否在充电、响铃模式、Wi-Fi 是否连着、USB 调试和无线调试开关、息屏时间和充电时常亮、剩余空间。不读 Wi-Fi 名字 |
| `station_shell_status` | Shizuku 的运行、授权、执行身份和不可用原因；不启动服务 |
| `station_clipboard_get` | 读手机当前文字剪贴板；后台读取需要 Shizuku，锁屏或敏感内容不返回文字 |
| `station_clipboard_state` | 读共享设置、内存中的预览和 Mac 在线状态，不发起系统剪贴板读取 |
| `station_file_list` | 列出一个目录的直接内容。`path` 空着表示内部存储根 |
| `station_file_stat` | 类型、字节大小、修改时间，以及 `targetVersion` |
| `station_file_read_text` | 按行读文本，换行原样保留。默认 200 行，最多 2000 行 |
| `station_file_read_bytes` | 按偏移读原始字节，返回大写十六进制。一页最多 65536 字节 |
| `station_file_search` | 在目录下按文件名找普通文件 |
| `station_file_search_text` | 在一个文本文件里按字面量查找，带回行号和前后文 |

### 修改

| 工具 | 作用 |
| --- | --- |
| `station_shell_exec` | 通过已授权的 Shizuku 执行 shell 命令；参数和结果见上面的「Shizuku shell」 |
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
| `station_clipboard_configure` | 开关共享与自动同步；关闭共享清除内存预览并停止剪贴板后台服务 |
| `station_clipboard_exchange` | Mac 客户端双向交换；自动模式可写手机剪贴板，标为非只读且具有破坏性 |

`annotations` 里，读取类的 `readOnlyHint` 为 true。会改掉或删掉已有内容的工具，`destructiveHint` 为 true。`copy`、`create_directory`、`station_file_open`、`station_stay_awake` 和 `station_notify` 会写出新东西或改手机状态，但 `destructiveHint` 是 false。`station_clipboard_set` 会盖掉剪贴板，`destructiveHint` 为 true。

`station_stay_awake` 只接受布尔值 `on`。true 把 `screen_off_timeout` 写成 2147483647，把 `stay_on_while_plugged_in` 写成 7。false 写成 60000 和 0。写完再读回，对不上就拒绝。

`station_notify` 要 `title` 和 `text`，不能是空的，分别最多 200 字和 4000 字。`mode` 省略时是 `replace`，原地更新同一条；`stack` 另发一条。`agent` 只认 `Grok`、`Claude`、`Codex`。可选输入 `sound` 指定手机上的铃声文件或 content URI；省略时沿用应用的铃声选择。它交给和 `notify.sh` 同一个接收器，并等到铃声结果：`sound` 是 `played`、`silent` 或 `failed`。没有铃声、通知权限没开，或 15 秒内没有结果，工具失败。

`station_clipboard_set` 的 `text` 不能是空的，最多 100000 个 UTF-16 单元。它沿用应用自己的写入权限，不要求共享开关或 Shizuku；后台读取和共享交换使用专用 Shizuku 服务。

42 版新增共享和后台读取工具；Mac 与手机两端页面的用法、版本协商与敏感内容处理见 [clipboard.md](clipboard.md)。`station_clipboard_exchange` 不自动跨通道重放；下次交换先重建基线。

`station_file_open` 用内容地址把文件交给系统查看器。没有应用能打开这种类型时失败。系统不允许从后台直接打开时，下拉栏留一条「打开文件」，点一下再开；这条用安静通道，不响，id 是 5，标记是 `open`。返回里 `opened` 表示已经打开，`notified` 表示改留下了那一条。

## 两端配置界面与独立配置验证

2026-10-02，Android 36 与 Mac 0.1.0 (2) 完成构建并安装。手机首页将本地 adb 与 MCP 服务分组，远程中继设置页支持编辑本机资料；Mac 的 MCP 页分别显示两条通道，远程设置默认独立配置，也可通过 adb 同时配置手机。

- 完整 Mac 构建包含 Go race、12 项模拟配对与配置测试、Swift 表单和连接状态测试、Android 全量测试及编译。新增测试覆盖 Mac 配置与清除不接触 adb、保存失败不启动网关，以及手机原地址和指纹不变时可保留令牌、更换任意一项必须提供新令牌。
- PGT-AN20 增量安装前比对签名并备份旧 APK；最终手机 APK 与本机构建逐字节一致。手机表单在令牌留空时成功保存原配置，保留原加密凭据，服务与远程通道仍开启。未更换真实服务器或令牌。
- Mac 从新暂存目录替换安装，旧 App 备份保留，签名验证通过，已启动进程对应新版；安装二进制与构建的 SHA-256 相同。重启本机网关使用新版源码，网关只读状态为 `ready=true`、`mode=local`、`localOnline=true`、`remoteOnline=true`。
- 手机实机截图检查了首页、配置页浅色和深色，以及深色 1.3 倍字号下滚动区域的字段、操作和电脑端说明。系统栏与键盘 Insets 在共用页面实现，滚动内容不进入状态栏。密码键盘出现时 `screencap` 返回 `Status: -1`，该画面未取得；没有绕过截屏限制。字号恢复为原来的 1.0，夜间模式恢复为原来的关闭。四项服务、远程和 adb 开关与安装前相同。
- Mac 使用真实 `NSHostingView` 渲染模拟数据，检查未配置、远程在线、无 adb 的双端配置、等待手机和仅远程首页。Mac 辅助功能界面工具超时，没有以它验证已安装菜单栏的实际点击。配置动作由模拟脚本与状态测试覆盖，视觉证据来自原生渲染。
- 独立界面复核提出的两项修正均已完成并重新安装：Mac 的校验与保存失败提示固定在滚动区域外、保存按钮上方；浅色已连接文字在页面背景上对比度为 5.81:1，在 14% 着色状态胶囊上为 4.76:1。七张更新后的 Mac 原生渲染证据包含提交校验失败场景；复核的 `ship` 结论仅覆盖这两项修正。

截图在忽略的 `.impeccable/review/`，安装备份路径记在忽略的 `build/.phone-station/last-ui-install`。本轮保留已部署中继与已有配对，没有再次运行 Wi-Fi 断开实验。

## 可配置中继的本机与安装验证

2026-10-02，完成源码修改、本机构建，并在用户要求真机安装后对 PGT-AN20 增量更新：

- 网关 `go test -race ./...` 覆盖自定义域名、IPv4/IPv6、默认或自定义端口、路径前缀、非法地址、默认空配置、配置输出不含令牌，以及换服务器或凭据后旧响应不能恢复在线状态。并发配置测试验证 20 次更新与 adb 令牌刷新不会丢掉远程配置。
- `test_mcp_pair.py` 的 10 项测试使用模拟 adb、钥匙串与中继状态，覆盖标准输入配对、旧手机版本在发送凭据前被拦住、手机拒绝新资料时保留电脑旧配置、电脑保存失败时解释部分完成状态和重试方式、已有开关不能冒充新配对确认、缺失输入与非法值不接触 adb、只读配置查看、凭据分开保存和写入失败保留旧资料。
- Mac 全量构建、配置表单数据与标准输入测试、既有连接状态测试、Android 全量构建与地址校验测试、shell 语法检查、项目指引镜像检查均通过。构建产物位于 `build/.phone-station/手机工位.app` 与 `build/adb-keep.apk`。

配置页以模拟资料渲染了浅色未配置、深色已配对两种状态；长地址和不可用按钮均已查看。独立界面复核提出的旧版本预检、部分完成提示与按钮禁用外观三项已修正并复核通过。未读取真实远程令牌，也未重新配对实机。

真机安装使用 `scripts/android.sh`。安装前确认型号为 PGT-AN20、旧 APK 与新包签名一致并备份旧包；增量安装成功，应用版本仍为 35，手机已安装 APK 的 SHA-256 与本机构建一致。只读 `capabilities` 广播返回 `result=2`，确认手机已经支持配置确认预检。主用户的写系统设置与通知权限已授予，所有文件访问为 UID `allow`。

安装前后的 `phonestation_mcp`、`phonestation_remote`、`adb_enabled`、`adb_wifi_enabled` 都为 1。原有配对资料保留；应用重启后，本机网关只读状态为 `ready=true`、`mode=local`、`localOnline=true`、`remoteOnline=true`。本次没有更换中继地址、读取真实远程令牌或重新配对，也没有安装新的 Mac App。

下列 PGT-AN20 记录是之前已部署版本的实测，不能代替本次配置更换的实机验证。

## 在 PGT-AN20 上验证过的

2026-10-02，`versionName` 35、`/Applications/手机工位.app` 和深圳中继已更新，保留配对资料。先在 34 版实测发现恢复 Wi-Fi 时旧蜂窝轮询继续等待、一次只读调用超时，35 版补上 2 秒空闲响应后重新验证：

- 两轮均短暂关闭手机 Wi-Fi，由手机自身预先安排恢复任务。网关在关闭后约 1.2–1.4 秒切到远程；第一条蜂窝只读状态调用在关闭后约 1.6–3.6 秒返回成功。恢复 Wi-Fi 后约 6–11 秒自动回到 adb；恢复等待期间仍可远程调用。
- 两轮连续 `station_device_status` 共 100 次全部成功，没有调用超时。关闭 Wi-Fi 时返回 `wifiConnected=false`、`remoteConnected=true`、`connectionType=远程连接`；恢复后为「无线连接 · 远程连接」。这是该机型、当时网络下的测量，并非固定时限。
- 手机首页在 Wi-Fi 关闭期间实际显示「已连接」「远程连接」，没有残留「无线连接」。Mac 连接文字、远程在线图标回调及 adb 操作禁用由 `ConnectionStatusTest` 验证；本次未通过界面工具取得 Mac 面板的视觉采样。
- Mac 与 Android 全量构建通过。网关 `go test -race` 覆盖不响应的 adb 转发、派发前切换、在途只读恢复、在途修改不重放、忙碌中继不阻塞状态及稳定恢复；Android 测试覆盖退避唤醒、Wi-Fi 消失使无线心跳失效及通知连接类型。中继 `go test -race ./...` 覆盖旧轮询接替、角色鉴权、已运行修改不重派和空闲等待范围。

结束时 Wi-Fi、无线调试、adb 转发和两条 MCP 通道均已恢复，测试临时文件已清理。上线前保留了旧 APK、旧 Mac App 和中继二进制；本次未实测重启后的开机恢复。

2026-10-02，`versionName` 32、已安装的 Mac 菜单栏 App 与深圳阿里云中继一起验证：

- 公网中继完成 `initialize`、`tools/list`（25 个工具）及 `station_device_status`，状态调用约 160–325 毫秒。
- 仅移除本机 adb MCP 转发，保留手机无线调试；网关约 2 秒改走远程，只读状态调用成功。恢复转发后，约 64 秒切回本地。
- 在本机转发前临时加入 800 毫秒延迟，本地实测约 850–900 毫秒，远程约 180–240 毫秒；两条通道始终在线，连续三次慢探测后约 16 秒改走远程，状态调用成功。
- 移除延迟后，经稳定探测和冷却约 70 秒切回本地，状态调用成功。临时代理与测试转发已移除，正常转发恢复；全过程无线调试仍为开启。
- Android 原有测试、Mac 原有测试和中继 `go test -race ./...` 通过。没有切换手机 Wi-Fi/蜂窝网络，也没有重启手机验证开机恢复；以上断线测试针对 adb 转发失效。

2026-10-01，应用版本 6。`./scripts/android.sh` 增量安装成功，随后 `./scripts/mcp.sh` 打印了地址并完成 `initialize`。

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

同一天的 `versionName` 20。`./scripts/android.sh` 增量安装成功，`versionName` 是 20。随后 `./scripts/mcp.sh` 完成 `initialize`，`tools/list` 里有原来的文件工具，加上 `station_storage_summary`、`station_device_status`、`station_stay_awake`、`station_notify`、`station_clipboard_set`、`station_file_open`。

- `station_file_access_policy` 的六个目录是截图、相机、下载、文档、电影、录音。录音的路径是 `/storage/emulated/0/Sounds`。交接目录是 `Download/手机工位/inbox` 和 `outbox`，没有创建。
- `station_device_status` 读到了电量、充电、响铃、Wi-Fi、两枚调试开关、息屏时间和剩余空间。当时响铃是 `vibrate`，Wi-Fi 连着，USB 调试和无线调试都开着。息屏已经是 2147483647，充电掩码已经是 7，所以 `stayAwake` 为 true。这次没有调用 `station_stay_awake`，设置没改。
- `station_storage_summary` 里这六个目录 `exists` 都是 true，`truncated` 都是 false。
- 写入 `Download/phonestation-mcp-probe.txt`，结果 `mediaScan` 为 true。媒体库里能查到这一行，`_data` 就是这个路径。删掉之后 `mediaScan` 仍为 true，`stat` 是「没有这个文件」，媒体库里这一行也没了。

`station_file_open`、`station_notify`、`station_clipboard_set` 这次没有在手机上调用。电脑上的 `FileOpsTest`、`StayAwakeTest`、`McpLoopbackTest` 在打包前通过。核对完跑了 `./scripts/mcp.sh stop`。

同一天的 `versionName` 29。增量安装成功。当时只有无线，没有改 `adb_wifi_enabled`，也没有重启。shell 广播打开之后 `settings global phonestation_mcp` 是 1，logcat 里有一行 32 位十六进制的 `mcp token`。接着 `am stopservice` 把这项写成 0，再打开应用它回到 1。再用广播关掉，打开应用之后仍是 0。`./scripts/mcp.sh status` 在关着时打印 `off`。`./scripts/mcp.sh` 打印了本机 `18765` 的地址和 `Authorization`，`./scripts/mcp.sh stop` 之后这项是 0。开机把「要开着」再拉起来这一步没有在这台上重启验证。谁可以发这条广播，见 [notify.md](notify.md)。

2026-10-02，`versionName` 37，在荣耀 PGT-AN20（Android 15）上接通 Shizuku shell：

- 使用已安装 Shizuku 的 `start.sh` 启动服务，进程为 `shizuku_server`、用户为 shell；手机工位已有授权沿用。
- 新增 `station_shell_status` 和 `station_shell_exec`，本地及公网中继 `tools/list` 均为 27 个工具；实际 `id` 返回 `uid=2000(shell)`、`context=u:r:shell:s0`。
- 经公网中继验证 stdout、stderr、退出码 7，以及 9000 字节输出只保留 127 字节后仍正常退出。300 毫秒超时返回 `timedOut: true`，再次以 `kill -0` 只读确认同组 sleep 子进程已不存在。
- 本机统一网关完成 `sleep 3; id`，健康探测不再被长命令挡住。Android 构建与测试通过，Mac 网关测试通过；测试覆盖未授权错误、无效参数、无 Bearer 拒绝、长工具调用期间 ping 响应，以及 shell 执行不自动重放。
- 没有重启手机或关闭无线调试来验证 Shizuku 的重启行为。
