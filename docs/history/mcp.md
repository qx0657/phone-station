# mcp 历史验证记录

以下为迁移前的日期、版本、测试与部署记录，叙述中的“当前”“本轮”均指当时。不能据此判断今天运行的版本或把模拟测试当成生产验收。当前规则见 [mcp.md](../mcp.md)，部署状态见 [发布记录](../releases.md)。

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

同一天的 `versionName` 29。增量安装成功。当时只有无线，没有改 `adb_wifi_enabled`，也没有重启。shell 广播打开之后 `settings global phonestation_mcp` 是 1，logcat 里有一行 32 位十六进制的 `mcp token`。接着 `am stopservice` 把这项写成 0，再打开应用它回到 1。再用广播关掉，打开应用之后仍是 0。`./scripts/mcp.sh status` 在关着时打印 `off`。`./scripts/mcp.sh` 打印了本机 `18765` 的地址和 `Authorization`，`./scripts/mcp.sh stop` 之后这项是 0。开机把「要开着」再拉起来这一步没有在这台上重启验证。谁可以发这条广播，见 [notify.md](../notify.md)。

2026-10-02，`versionName` 37，在荣耀 PGT-AN20（Android 15）上接通 Shizuku shell：

- 使用已安装 Shizuku 的 `start.sh` 启动服务，进程为 `shizuku_server`、用户为 shell；手机工位已有授权沿用。
- 新增 `station_shell_status` 和 `station_shell_exec`，本地及公网中继 `tools/list` 均为 27 个工具；实际 `id` 返回 `uid=2000(shell)`、`context=u:r:shell:s0`。
- 经公网中继验证 stdout、stderr、退出码 7，以及 9000 字节输出只保留 127 字节后仍正常退出。300 毫秒超时返回 `timedOut: true`，再次以 `kill -0` 只读确认同组 sleep 子进程已不存在。
- 本机统一网关完成 `sleep 3; id`，健康探测不再被长命令挡住。Android 构建与测试通过，Mac 网关测试通过；测试覆盖未授权错误、无效参数、无 Bearer 拒绝、长工具调用期间 ping 响应，以及 shell 执行不自动重放。
- 没有重启手机或关闭无线调试来验证 Shizuku 的重启行为。

### 44 版连接状态验证

2026-10-02，连接状态与同一工作目录中的剪贴板恢复修改一起构建。`go test -race ./...`、Mac 全量构建及 `ConnectionStatusTest`、Android 全量构建及 `RelayConnectionTest` 通过。新增测试覆盖鉴权状态流的首次快照与变化推送、忙碌远程通道的确认过期与应答恢复、分段/空 adb 帧、带空格的 mDNS 序列号、断线事件后的旧探测失效，以及远程失败不会清掉有效 adb 序列号。

PGT-AN20 经已有中继完成保留数据升级到 44，安装任务 `34b8a1604c8f40e1858a2f6377bf80f7` 返回 `verified: true`、`completed: true`，后台服务与主页自动恢复。当次 APK SHA-256 为 `819244e395c7ad26bcaf99e96c09e8c9106e2f65da2ef471f63e5a3ce5df228e`。只读远程调用返回版本 44、`connectionType=无线连接 · 远程连接`、`remoteConnected=true`、`remoteChecking=false`。Mac 安装并启动新版网关后，状态快照为本地和远程均在线、远程已确认。原配对保留，没有切换 Wi-Fi 或调试开关来制造断线；本段不把隔离测试的时序当作实机断网延迟。

手机首页浅色实机画面已检查；Mac 首页、连接页与 MCP 页分别检查浅色/深色 `NSHostingView` 原生渲染，使用当时实际连接数据，图片位于忽略的 `.impeccable/review/mac44-*.png`。Mac 渲染窗口未激活，灰色系统控件不能单独证明禁用；电脑 UI 工具未能绑定菜单栏应用，操作边界由状态测试覆盖。连接页分别列出 adb 与远程状态，首页并列显示已确认的连接类型。

Mac 构建时网关和钥匙串助手先输出到独立暂存文件，再原子替换；覆盖原 Mach-O 文件后出现过配对测试进程被 SIGKILL，暂存替换后的全量构建通过。更新已安装 App 时仍需退出旧 App 与网关、保留旧目录，再替换并重新启动网关。

`mcp.sh stop` 要求手机广播确认、两个开关明确为 0 且实际服务退出，才清理转发和本地令牌并报告成功。请求失败、断线或退出未确认时返回非零，保留本机通道状态；不以直接 stopservice 替代远程开关关闭。旧版没有确认码时须先更新。

MCP 前台服务、MCP 开关与提醒广播入口均由系统 `android.permission.DUMP` 保护；保持 exported 供 adb shell 调用，同 UID 的应用内调用仍可用。Android 29–33 也由系统权限拦住普通第三方应用，不依赖 Android 34 才提供的发送方 UID 检查。远程配对广播沿用同一权限边界。

MCP 网关所有 App、CLI 和探测请求共用最多 64 项队列。状态、任务查询、剪贴板与通知优先，字节上传下载在后；连续三次交互请求后让最早的等待请求前进，避免大文件饥饿。排队取消后不得迟到执行，配置变化时拒绝尚未派发的旧请求。健康探测仅在队列空闲时发起，不把忙碌当离线。

后台任务使用 32 位小写十六进制 jobId，同编号同参数只返回原回执，不同参数拒绝。一个工作线程、最多四项等待，排队超过 10 秒的任务不再执行。回执最多 256 个，私有目录 0700、文件 0600，保留 24 小时；只存请求摘要与有界结果，不保存 shell 命令原文。应用重起后未完成任务返回 `result_unknown`，查询不触发执行；超出保留窗口仍不可自动重提旧任务。`station_shell_exec` 与 `station_screen_capture` 留作旧客户端同步接口，新客户端优先后台接口。

### 59 版协议边界本地验证

2026-10-03，Android 59 与 Mac 构建 17 的本地测试覆盖：带有效 Bearer 的恶意 Host/Origin 仍被拒绝，adb 转发端口和无 Origin 的原生客户端可用；GET 返回 405 与 Allow 头；重复头、畸形 UTF-8、非法 JSON-RPC 帧、重复字段与重复编号批次拒绝，合法混合批次保留各项编号，通知不执行文件写入。网关测试还覆盖只读批次可恢复、含修改项的批次不重放。以上仅指电脑回环测试与构建阶段，随后的实机部署验证如下。

### 59 版整改部署验证

2026-10-03，3 个 P1 与 7 个 P2 的独立整改提交已推送到 `codex/system-remediation`，随后更新两端：

- PGT-AN20 通过已有中继与已授权 Shizuku 从 58 保留数据升级到 59。原安装任务 `151b3a7adb8f431db28a694f2ce68b48` 返回 `verified: true`、`completed: true`；MCP 前台服务与主页都确认收到本次任务编号，新进程启动且远程通道自动恢复。再次查询原任务仍为完成。已安装 APK、本地 APK 与 Mac 随包 APK 的 SHA-256 都为 `e0e99ac13196b938060c5927979be26c82d04733a89860529b226852bda788a5`。
- `/Applications/手机工位.app` 从 16 更新到 17 并启动。严格签名校验通过，主程序、网关、远程脚本和随包 APK 与构建一致；未改动的钥匙串助手沿用原有签名。旧完整包保存在忽略的 `build/.phone-station/backups/system-remediation-before-17-20261003-160638.app`。实际运行的网关来自已安装 App；连续 8 次、间隔 3 秒的采样均为远程在线且已验证。
- 新后台 shell 接口实际执行 `id`，返回 UID 2000、退出码 0；查询为 completed，再以相同 jobId 和参数提交得到完全相同的原回执。
- 已安装网关带正确令牌的跨站 Origin 请求返回 403，GET 返回 405 与 `Allow: POST`。经公网通道向手机分发 null id 请求返回 `-32600`，包含两项 ping 和一项通知的批次只返回两项原编号。
- 原有通知开关和所选应用数量、共享与自动剪贴板设置保留；只读复核为通知使用权已授权、监听已连接、通知与剪贴板的 Mac 会话在线，Shizuku 可用，手机 `health.issues` 为空。验证仅保存和输出设置与会话字段，不输出剪贴板或通知正文。

本次使用现有远程通道，没有变更中继服务端代码、重新配对或进行断网与手机重启实验；不能用这次短时在线检查替代断线与重启场景验证。
