# 手机工位 MCP

手机上「手机工位」自己的 MCP 服务，提供普通文件、手机状态、亮屏、提醒、剪贴板，以及通过已授权 Shizuku 执行 shell 的工具。以后要加的工具做在这个应用里。日常用法见 [../README.md](../README.md) 的「文件」。

本仓同时维护 Android MCP、Mac 本机网关与公网中继。组件和身份边界见 [架构说明](architecture.md)，当前源码与部署状态见 [发布记录](releases.md)；历次设备验证保存在历史文档。

## 启动

在仓库根目录运行 `./scripts/mcp.sh`。它启动本机统一网关 `127.0.0.1:18765`，保留手机 MCP 的 `127.0.0.1:8765`，并把 adb 转发放在本机 `18766`。有 adb 时，脚本用 `connect.sh` 整理现有连接、启用手机 MCP、读取本地 Bearer 并完成 `initialize`；adb 不可用时，已有远程客户端在线也能返回网关地址。输出包含：

```text
http://127.0.0.1:18765/mcp
channel=local local=<毫秒>ms remote=<毫秒>ms
Authorization: Bearer <本机网关令牌>
```

`18765` 是调用方固定访问的统一入口；`18766` 只供 adb 转发。Mac 网关令牌随机生成，保存在用户配置目录的权限为 `0600` 的配置文件中，随 `mcp.sh` 输出以便调用方访问；它不是手机服务令牌。手机本地 MCP 令牌仍会随服务进程重启而变化，只由 adb 从受系统 DUMP 权限保护的服务诊断入口读取，并交给本机网关。远程中继凭据保存在 macOS 钥匙串。

网关用无副作用的 `ping` 独立探测两条通道：本地每秒一次、超时 1 秒，远程每 2 秒一次、超时 3 秒。远程探测或业务调用不会挡住本地断线检测。只有一条可用时立即使用该通道。两条都可用时优先 adb；本地延迟连续三次高于 500 毫秒且远程延迟至少低 40% 时改走远程。因本地断线改走远程后，本地连续三次低于 150 毫秒、且切换已过 3 秒就可恢复；因持续高延迟选择远程时，仍需本地连续六次低于 150 毫秒和 60 秒冷却，避免来回切换。无线 adb 保持原状；远程通道承接本应用 MCP 工具，其中 shell 命令通过手机上已启动并授权的 Shizuku 执行。Mac App 的截屏、保持亮屏和手电筒可使用远程 MCP，见 [remote-controls.md](remote-controls.md)。截屏、投屏和录屏脚本仍使用本地 adb；Mac 的远程实时投屏与录屏使用独立 WSS，见 [remote-screen.md](remote-screen.md)；远程 shell 与安装入口见 [remote-ops.md](remote-ops.md)。

本地派发前先做 `ping`，转发还接受 TCP 但手机已经不可达时，实际操作直接走可用远程通道。检测到本地断线会取消仍在等响应的本地请求；`ping`、`initialize`、`tools/list` 和明确列入白名单的只读工具可以自动转到远程重试。读取手机剪贴板的 `station_clipboard_get` 与客户端交换都不在重放白名单中；具体工具不能仅凭 `readOnlyHint` 判断是否会重试。文件修改、通知、剪贴板、打开文件、保持亮屏和未知工具均不自动重放，结果不明时报告需要核实。已取消的旧探测也不能把通道重新报成在线。

`./scripts/mcp.sh stop` 需要 adb 在线，通过 `McpControlReceiver` 关闭手机 MCP 与远程开关，再移除转发；配对资料会保留。`./scripts/mcp.sh status` 不启动服务，也不跑 `connect.sh`，只刷新已有 adb 转发信息并报告网关探测到的通道。读取手机服务开关、刷新转发和读取令牌的 adb 命令各有 2 秒超时。菜单栏通过内部 `watch` 命令持续接收网关的鉴权 NDJSON 状态流（每 100 毫秒检查状态差异），流中断自动重连，并用 `snapshot` 兜底；adb 转发与令牌另行在后台刷新，其他操作进行中也会继续刷新连接显示。远程在线时，首页和菜单栏图标也显示已连接，只有远程可用时标明「远程连接」；截屏、保持亮屏和手电筒按 MCP 实际能力启用，命令页的 `shell …` 可通过已授权 Shizuku 的 MCP 后台任务执行；其他 adb 子命令需要本地连接；Mac 22 与手机 61 起，交互式远程终端使用 `station_terminal_*` PTY 会话。手机首页与常驻通知同样把实际远程连接计入已连接，开关打开或已配对本身不算。两端远程状态只把最近 6 秒的成功应答作为已确认连接；远程业务占用通道、暂时无法新探测时显示「确认连接中」，不会取消或重放正在执行的操作。adb 连接由列表事件与实际 shell 应答单独判断，本地 MCP 关闭不会导致误报 adb 断线。

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

首页「MCP 服务」下的「远程通道」是状态入口，显示未配置、已关闭、连接中、确认连接中、连接失败重试或认证失败，点击进入远程中继页。远程通道的开关在配置页上方，未配置时禁用，保存资料后可独立开关；打开远程通道时也会打开 MCP 服务。远程客户端用 HTTPS 长轮询该中继，再在手机进程内调用同一个 `McpProtocol`，所以文件权限与本地 MCP 相同。远程访问依赖中继已部署、Mac 网关已连接；任一尚未就绪时仍走原 adb 通道。中继能读取转发的请求和结果，应使用自己部署或信任的服务。

手机监听默认网络变化，Wi-Fi 与蜂窝切换、或 VPN 底层网络改变时请求结束旧长轮询、唤醒退避并使用新的默认网络重连。手机轮询请求带 `waitMs=2000`，没有操作时中继在 2 秒后返回空闲响应，读取超时为 5 秒；旧蜂窝网络仍可用、关闭连接未能立即唤醒读取时，也不再等待原来的 25 秒空闲轮询。已执行操作只重传同一操作 ID 的结果，不再执行工具。中继允许同一已认证手机接替旧的等待轮询，避免旧 Wi-Fi TCP 未关闭时阻塞新网络；接替不重派已运行操作。需要远程已配对、开关开启，且蜂窝或其他默认网络能访问中继。没有可用网络时无法完成切换。

手机把 MCP 与远程通道的开关记在应用偏好里。MCP 服务本地监听失败时，只要远程配对已开启，前台服务仍会启动手机长轮询。进程退出或重启后，开机广播会按偏好恢复服务。菜单栏检查走本机网关状态，不会触发 `connect.sh`。关掉应用里的「保持无线调试」不会停 MCP 服务。下拉栏不另起一条：和无线调试保持共用渠道 `keep` 那条，里面写 MCP 服务开还是关，不写地址。会话提醒仍占用 id 2 和 3。合并前单独那条的 id 4 会在升级时清掉。

传输是 Streamable HTTP，只接受 `POST /mcp`。请求头：

```text
Content-Type: application/json
Accept: application/json, text/event-stream
Authorization: Bearer <mcp.sh 打印的令牌>
```

当前 `initialize` 返回协议版本 `2025-03-26`，`serverInfo.name` 为「手机工位」，`serverInfo.version` 为手机 APK 的版本名。成功之后就可以 `tools/list` 和 `tools/call`；实际工具集合以这次的 `tools/list` 为准。没有 `Mcp-Session-Id`，也不提供 SSE：已鉴权的 `GET /mcp` 回 405，并带 `Allow: POST`；未知路径回 404。

请求必须是 JSON-RPC 2.0，`id` 为字符串或整数，不能为 null；`params` 若存在必须是对象。没有 `id` 的合法通知，以及合法客户端应答，只接受并忽略，回 HTTP 202 空正文，不执行工具。支持 1–64 项批次，各请求结果保留原编号，通知不占应答项；混有无效帧时逐项返回协议错误。同一批次请求编号重复时，在执行任何一项前拒绝整个批次。JSON 重复字段或解析失败回 `-32700`，无效帧回 `-32600`，错误参数回 `-32602`，未知方法回 `-32601`。工具执行失败仍写在结果里，`isError` 为真，正文是 `{"error":"…"}`。

手机服务和桌面网关检查每个请求的 Host 与 Origin：Host 只允许 `localhost`、`127.0.0.1`、`[::1]`，支持 adb 转发端口；Origin 若存在，必须为使用相同端口的 HTTP 回环来源。远端 Host、跨站或跨端口 Origin 回 403，即使 Bearer 令牌正确也不能通过；原生客户端可以不发 Origin。手机拒绝重复 Host、Origin、Authorization 和长度头，不接受分块编码；正文严格按 UTF-8 解码。来源校验和 GET 行为依据 [MCP 2025-03-26 HTTP 传输规范](https://modelcontextprotocol.io/specification/2025-03-26/basic/transports)，帧与批次规则依据 [基础消息规范](https://modelcontextprotocol.io/specification/2025-03-26/basic)。

手机服务在读取正文前鉴权；单行请求头上限 8 KiB、全部请求头上限 64 KiB，超过即时回 431。包括排队在内的请求读取绝对期限为 20 秒，超时回 408，逐字节发送不能续期。手机服务请求体上限 8 MiB；中继与桌面网关上限 18 MiB。

调用方使用统一网关地址；Mac 19 起可用独立客户端令牌，只授予所需的状态、文件读取、文件修改或 shell 权限，见 [客户端权限](mcp-clients.md)。原来的 `mcp.sh` 输出为能力完整的主令牌。adb 转发消失时，已配对且在线的远程通道仍可继续提供 MCP。手机本地令牌重启后会换，本机网关令牌则保存在用户配置中。

AI 和脚本可用 `scripts/mcp.sh call`，从标准输入提交一个带 `id` 的 JSON-RPC 2.0 请求。网关 CLI 从本机配置内部读取凭据，向已有 `/mcp` 发送请求，保留本地优先和远程接替逻辑；它不启动手机服务或刷新 adb，不要求配置远程中继。初始化与工具发现仍由调用方完成。标准输出只有 RPC 应答，需要继续检查 `error` 和工具 `isError`；传输失败、HTTP 重定向或应答编号不符会失败且不重发。原有 `remote-call` 仍固定走共享队列里的远程通道。

`scripts/mcp.sh status --safe` 查询已运行网关的状态，不输出令牌，也不刷新 adb；网关未运行时返回失败。默认启动和普通 `status` 为手工接入保留授权头输出，内部 `snapshot/watch` 也可能含令牌，不能直接贴进聊天或日志。需要从 AI 启动服务时用 `scripts/mcp.sh >/dev/null`，随后用 `status --safe` 和 `call`。新版子命令需要重编网关，随包入口需更新 Mac App。

## 中继兼容要求

中继服务端源码在 [server/relay](../server/relay/README.md)，与 Mac 网关是不同组件。公网路径、请求与应答、角色鉴权、会话编号、持久回执、429 重试和重启语义统一以 [中继合约](relay-contract.md) 为准。中继与桌面正文上限 18 MiB，手机本地 MCP 为 8 MiB。

Mac 20 先通过固定 TLS 的 status 握手取得会话代号，再提交唯一 operationId；Android 透传编号。应答丢失不自动重发，即使同编号已具备持久去重。先更新 Mac 再更新服务端，见 [发布顺序](releases.md#兼容顺序与回滚)。

## 界面

手机上的 MCP 服务由首页开关、菜单栏「MCP服务」页或本机脚本协调启停。手机上关闭它会同时关闭远程开关；远程中继页的「启用远程通道」可以单独开关，且打开时会确保 MCP 服务开着。MCP 本地监听时，手机「接入信息与说明」页显示 `http://127.0.0.1:8765/mcp`，这条地址只在手机上听。电脑固定访问统一网关 `http://127.0.0.1:18765/mcp`；菜单栏页显示当前通道和接入地址，提供「复制 Authorization」，不展示令牌明文；`./scripts/mcp.sh` 打印地址与授权头。令牌不出现在手机界面或应用日志中；电脑经 adb 读取受 DUMP 权限保护的诊断入口。菜单栏没开着、也没再跑 `status` 或 `stop` 时，已经建立的 adb 转发会留到下一次。

首页「接入信息与说明」是给站在手机前的人看的短页，四段：怎么连上、能做的、做不到的、交接。工具名、参数和下面的验证记录不写在那一页。`versionName` 21 在这台上点开过，画面记在 [adb-keep.md](adb-keep.md)。

## 监听和令牌

服务只绑定手机上的 `127.0.0.1`，不听局域网。Mac 本机网关也只绑定 `127.0.0.1:18765`。

同一台手机上别的应用也能访问 `127.0.0.1`，所以手机本地 MCP 每个请求仍要带 Bearer。令牌是进程里的 16 字节随机数，打成 32 位大写十六进制。服务启动不再把令牌写入 logcat；脚本只用 `dumpsys activity service dev.phonestation.adbkeep/.FileMcpService` 刷新令牌，不依赖日志留存。这条服务诊断入口受系统 DUMP 权限保护，普通应用不能调用，不要把完整输出贴进聊天或仓库。本机网关只从 adb 读取这个令牌；远程手机客户端另用进程内的随机令牌调用现有 MCP 分发器，不把本地令牌送到中继。

令牌不放进 `Settings.Secure`。那项设置任何应用都能读，和本机监听放在一起就会被拿走。

Android 15 上，清单里没有 `exported` 的前台服务，shell 直接 `am start-foreground-service` 会被拒绝：`not exported from uid`。所以 `FileMcpService` 是 exported，类型是 `specialUse`，同时要求系统 DUMP 权限；普通第三方应用不能启动或停止它。同 UID 的应用内调用和 adb shell 仍可用。返回值是 `START_NOT_STICKY`。

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

`station_shell_status` 只读检查 Shizuku 是否安装、运行及授权，返回 `available`、`state`、`uid`、`identity`、`serverVersion` 和不可用原因 `reason`；61 起另返回 `terminalSupported` 与 `terminalProtocol: 1`。不启动 Shizuku，也不弹授权框。要执行命令，先在手机上启动 Shizuku 13 或更新版，再到手机工位的「权限 → Shizuku」允许使用；已保存的授权沿用。非 root 手机重启后需要重新启动 Shizuku。

`station_shell_exec` 在本地 MCP 和远程中继使用同一个执行端。手机通过 `bindUserService` 创建特权进程，调用方 UID 必须是手机工位的应用 UID，执行进程 UID 必须是 `2000`（shell）或 `0`（root）。没有服务或授权时拒绝执行，不回退到普通应用 UID。中继令牌负责远程接入，Shizuku 授权负责手机执行，两者互不替代。通过 adb 启动的 Shizuku 仍不能访问其他应用的 `/data/user/0/<包名>`；已有文件工具的范围保持原样。

参数：

| 参数 | 范围与默认值 |
| --- | --- |
| `command` | 必填，交给 `/system/bin/sh -c`，最多 16384 字，不能有 NUL |
| `timeoutMs` | 100–60000 毫秒，默认 10000；绑定执行服务另有 5 秒超时 |
| `maxOutputBytes` | stdout 与 stderr 合计保留 1–65536 字节，默认 32768 |

返回 `stdout`、`stderr`、`exitCode`、`timedOut`、`outputTruncated`、`durationMs`、`uid`、`identity`。输出按 UTF-8 解码，截断位置或二进制内容可能出现替代字符。达到输出上限后仍排空两个流，避免管道阻塞。输出读取失败另报 `outputIncomplete: true`；进程未能退出时 `exitCode` 为 null。

命令在 `/` 下运行，标准输入立即关闭，没有交互终端。用 `setsid` 建立独立进程组，结束或超时会终止该组内的子进程，每次命令结束后移除执行 UserService，普通子进程不保证长期存活；命令主动脱离该进程组的进程不保证被清理。超时不撤销已经生效的修改。服务停止、授权撤销或网络断开时，先核实结果，不自动重做。工具标为非只读、具有破坏性，连 `id` 这种只读命令也不会加入自动重放白名单；只读状态工具允许重试。

本地 HTTP 用有上限的请求线程池，文件事务跨通道串行；主机状态、剪贴板和通知不占文件锁。`station_shell_start` / `station_screen_capture_start` 立即提交后台任务，`station_operation_status` 查询原编号；新版 CLI 和 Mac 截屏使用这些接口，长执行期间仍可接收通知与剪贴板。原有文件、亮屏、通知等工具继续使用应用自身权限。

电脑侧 `scripts/shell.sh` 经网关共享队列固定使用中继，不连接 adb；`scripts/install-apk.sh` 复用同一组文件和 shell 工具，实现 APK 校验、安装 session、应用自身更新后的结果查询。安装助手是明确需要跨应用重启继续运行的有界任务；普通 shell 请求仍按原来的进程组规则清理。步骤与边界见 [remote-ops.md](remote-ops.md)。

### 交互式 PTY

手机 61 起新增 `station_terminal_open/read/input/resize/close`。创建前保存唯一 `sessionId`，open 返回后台创建回执，用 read 查询原会话；input 用连续序号与十六进制字节，read 返回不消耗的输出游标、遗漏字节数和已接收序号。输入应答丢失后只查原序号，不重发。独立 Shizuku UserService 只允许应用 UID 调用，PTY 辅助程序运行身份为 shell/root。查询不启动或重新绑定执行服务，关闭、服务停止与租期到期会清理原会话。

界面、CLI、租期、输出范围与恢复方法见 [远程交互终端](remote-ops.md#远程交互终端)。单次 `station_shell_*` 的标准输入关闭与进程组清理语义独立保留。只有 `station_terminal_read` 加入网关的只读重放白名单，其他 terminal 操作不自动重放；全部 terminal 工具要求 `shell` 客户端权限。

## 路径

Home 是 `/storage/emulated/0`。`path` 空着就是这里。相对路径从这里算。绝对路径要落在这下面，`..` 走出去会得到「路径不在内部存储里」。只看词法，`/tmp/store-evil` 不会算进 `/tmp/store`。

应用自己的外部目录不开放，返回「这条路径不开放」：

- `/storage/emulated/0/Android/data/dev.phonestation.adbkeep`
- `/storage/emulated/0/Android/obb/dev.phonestation.adbkeep`

路径上每一段都不跟随链接。碰到链接是「不跟随链接」。

删除不进回收站。内部存储根，以及 `Android`、`Android/data`、`Android/obb` 这三层，删的时候返回「不能删除这一层目录」。

## 写入和读取

`targetVersion` 是不透明版本串，来自上一次 `stat` 或读取。普通文件使用文件身份、时间和内容 SHA-256；同大小、同修改时间的内容变化也会拒绝旧版本。所有本地与中继文件请求共用进程级事务锁，版本校验与写入一起串行执行。内容校验流式读取，不缓存外部写入后的旧摘要；大文件校验会增加磁盘读取。已有文件的写入必须带上这一次的值。对不上就拒绝，并给出当前值：「文件已变化，先重新读取。当前 targetVersion 是 …」。新建文件不要带它。

整文件写入先写到同目录的临时名 `名字.phonestation-<纳秒>`，再换上去。追加、补丁、截断改原文件。复制和移动的目标不能已经存在。移动一个目录时，不会把它并进自己里面。

文本输入与最终编码结果（含 BOM）上限均为 8 MiB。写入、追加和替换使用有界编码缓冲，替换不构造膨胀后的完整字符串；超限先拒绝，原文件保持不变。认编码的顺序是：UTF-8 BOM，UTF-16 LE/BE BOM，能按 UTF-8 来回的按 UTF-8，否则能按 GB18030 来回的按 GB18030，再不行就是「这个文件不是文本，用字节工具」。字节一页最多 65536。按文件名搜索是区分大小写的子串，不进压缩包，一次最多看 20000 个访问；到上限时 `truncated` 为 true。结果条数正好等于 `maxResults` 时，也可能标成截断。

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
| `station_operation_status` | 原后台任务状态与短期结果；只查询、不重新执行 |
| `station_shell_status` | Shizuku 的运行、授权、执行身份、终端协议支持和不可用原因；不启动服务 |
| `station_terminal_read` | 原终端输出、字节游标、状态与已接收输入序号；不创建会话，定期查询维持租期 |
| `station_clipboard_get` | 读手机当前文字剪贴板；后台读取需要 Shizuku，锁屏或敏感内容不返回文字 |
| `station_clipboard_state` | 读共享设置、内存中的预览和 Mac 在线状态，不发起系统剪贴板读取 |
| `station_notification_status` | 读手机通知同步、系统授权、监听状态、已选应用数量和 Mac 在线状态；不返回正文，不建立接收会话 |
| `station_notification_icon` | 读已选应用的 96×96 PNG 图标，最多 64 KiB；须已开启同步并授权，未选应用返回 `available: false`；省略 `packageName` 时读取第一个已选应用供预览 |
| `station_file_list` | 列出一个目录的直接内容。`path` 空着表示内部存储根 |
| `station_file_stat` | 类型、字节大小、修改时间，以及 `targetVersion` |
| `station_file_read_text` | 按行读文本，换行原样保留。默认 200 行，最多 2000 行 |
| `station_file_read_bytes` | 按偏移读原始字节，返回大写十六进制。一页最多 65536 字节 |
| `station_file_search` | 在目录下按文件名找普通文件 |
| `station_file_search_text` | 在一个文本文件里按字面量查找，带回行号和前后文 |

### 修改

| 工具 | 作用 |
| --- | --- |
| `station_shell_start` | 提交有编号的后台 shell 任务；同参数同编号去重 |
| `station_screen_capture_start` | 提交有编号的后台截图任务 |
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
| `station_clipboard_configure` | 配置共享、自动同步与默认关闭的 images；仅启用 shared 时同时启用 automatic，显式 automatic 优先；关闭共享清除内存状态并停止剪贴板后台服务 |
| `station_clipboard_exchange` | Mac 客户端双向文字交换；图片开关开启后可将新 Mac 图片保存到手机相册；自动模式可写手机剪贴板，标为非只读且具有破坏性 |
| `station_notification_configure` | 开关手机通知同步；应用白名单只在手机设置页选择，不授予系统通知使用权 |
| `station_notification_poll` | Mac 的通知接收与会话心跳；只返回已选应用的在线新通知，非只读，不自动重放，见 [notify.md](notify.md#手机通知同步到-mac) |

`annotations` 里，读取类的 `readOnlyHint` 为 true。会改掉或删掉已有内容的工具，`destructiveHint` 为 true。`copy`、`create_directory`、`station_file_open`、`station_stay_awake` 和 `station_notify` 会写出新东西或改手机状态，但 `destructiveHint` 是 false。`station_clipboard_set` 会盖掉剪贴板，`destructiveHint` 为 true。

`station_stay_awake` 只接受布尔值 `on`。true 把 `screen_off_timeout` 写成 2147483647，把 `stay_on_while_plugged_in` 写成 7。Android 60 起，false 恢复开启前持久保存的原设置，保留中途手动改过的字段。与 adb 脚本共用记录，写后读回核实；无记录的旧版常亮才回退到 60000/0。故障恢复及升级边界见 [亮屏原设置恢复](remote-controls.md#亮屏原设置恢复)。

`station_notify` 要 `title` 和 `text`，不能是空的，分别最多 200 字和 4000 字。显示方式由手机 App「首页 → 通知 → 电脑提醒 → 手机 → 显示方式」统一决定：默认只显示最新一条，也可选择每条都显示。旧客户端传来的 `mode` 不再覆盖手机设置，工具列表也不再提供该输入；返回的 `mode` 是实际采用的 `replace` 或 `stack`。`agent` 只认 `Grok`、`Claude`、`Codex`，认出来时左侧换成对应图标。可选输入 `sound` 指定手机上的铃声文件或 content URI；省略时沿用应用的铃声选择。它交给和 `notify.sh` 同一个接收器，并等到铃声结果：`sound` 是 `played`、`silent` 或 `failed`。没有铃声、通知权限没开，或 15 秒内没有结果，工具失败。

`station_clipboard_set` 的 `text` 不能是空的，最多 100000 个 UTF-16 单元。它沿用应用自己的写入权限，不要求共享开关或 Shizuku；后台读取和共享交换使用专用 Shizuku 服务。

42 版新增共享和后台读取工具；Mac 与手机两端页面的用法、版本协商与敏感内容处理见 [clipboard.md](clipboard.md)。`station_clipboard_exchange` 不自动跨通道重放；下次交换先重建基线。

`station_file_open` 用内容地址把文件交给系统查看器。没有应用能打开这种类型时失败。系统不允许从后台直接打开时，下拉栏留一条「打开文件」，点一下再开；这条用安静通道，不响，id 是 5，标记是 `open`。返回里 `opened` 表示已经打开，`notified` 表示改留下了那一条。

## 请求协调与结果恢复

`mcp.sh stop` 要求手机广播确认、两个开关明确为 0 且实际服务退出，才清理转发和本地令牌并报告成功。请求失败、断线或退出未确认时返回非零，保留本机通道状态；不以直接 stopservice 替代远程开关关闭。旧版没有确认码时须先更新。

MCP 前台服务、MCP 开关与提醒广播入口均由系统 `android.permission.DUMP` 保护；保持 exported 供 adb shell 调用，同 UID 的应用内调用仍可用。Android 29–33 也由系统权限拦住普通第三方应用，不依赖 Android 34 才提供的发送方 UID 检查。远程配对广播沿用同一权限边界。

MCP 网关所有 App、CLI 和探测请求共用最多 64 项队列。状态、任务查询、剪贴板与通知优先，字节上传下载在后；连续三次交互请求后让最早的等待请求前进，避免大文件饥饿。排队取消后不得迟到执行，配置变化时拒绝尚未派发的旧请求。健康探测仅在队列空闲时发起，不把忙碌当离线。

后台任务使用 32 位小写十六进制 jobId，同编号同参数只返回原回执，不同参数拒绝。一个工作线程、最多四项等待，排队超过 10 秒的任务不再执行。回执最多 256 个，私有目录 0700、文件 0600，保留 24 小时；只存请求摘要与有界结果，不保存 shell 命令原文。应用重起后未完成任务返回 `result_unknown`，查询不触发执行；超出保留窗口仍不可自动重提旧任务。`station_shell_exec` 与 `station_screen_capture` 留作旧客户端同步接口，新客户端优先后台接口。

## 历史验证

历次安装、回归与机型测试见 [历史验证记录](history/mcp.md)；当前覆盖和待验证项见 [验证说明](validation.md)。
