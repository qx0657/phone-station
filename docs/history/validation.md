# validation 历史验证记录

以下为迁移前的日期、版本、测试与部署记录，叙述中的“当前”“本轮”均指当时。不能据此判断今天运行的版本或把模拟测试当成生产验收。当前规则见 [validation.md](../validation.md)，部署状态见 [发布记录](../releases.md)。

## 2026-10-03 第二轮检查

本机完整检查通过：47 项 Python 测试、28 个 Java 测试程序、12 个 Swift 测试程序、Go race，以及 Mac 全量源码编译。GitHub 上的托管 CI 尚未运行，不能用本机结果代替。另通过 Android 59 APK 与 Mac 18 完整打包，Mac 包签名及随包网关、MCP 脚本一致性核对通过。此次没有部署或操作手机。

修正了三类已确认问题：

- 截图去重不再沿相似链合并；直接匹配最终保留图，跳过损坏/未知界面文件，并验证候选归档。详情见 [截图文档](../screenshot-cleanup.md)。
- Mac 有限时任务不再被 stdin 管道写入卡住，超时清理同一进程组内的子进程。详情见 [Mac 文档](../mac-app.md)。
- MCP 增加不输出令牌的状态与调用入口；Skill 改为后台任务优先、按原编号恢复，并纠正通知显示方式和截图计算位置的旧说明。详情见 [MCP 文档](../mcp.md)。

第二轮留下的三个设计边界是中继去重与恢复、客户端权限和亮屏原值恢复。后续整改如下。

## 2026-10-03 设计边界整改

- Android 60 统一 adb 广播与 MCP 的亮屏控制，手机先持久保存原设置，再写系统；重复开启、手动修改、部分失败和下次恢复有明确语义。Mac 19 本地状态同时核对息屏时间和充电掩码。见 [亮屏恢复](../remote-controls.md#亮屏原设置恢复)。
- Mac 网关新增独立令牌的状态、文件读、文件写与 shell 权限，按整个 RPC 批次预检，过滤工具发现，撤销即时作用于后续鉴权。主令牌和既有 Mac 功能保留；未自动签发真实令牌。见 [客户端权限](../mcp-clients.md)。
- 远程应答丢失不再自动重发，429 必须明确确认未入队才重试；两端禁止中继重定向。新增隔离中继的可执行验收及其离线自测，能识别模拟重启丢失回执。见 [中继合约](relay-contract.md)。

完整回归通过：50 项 Python 测试、28 个 Java 测试程序、12 个 Swift 测试程序、Go race、隔离中继检查器自测及 Mac 全量源码编译。Android 60 APK 与 Mac 19 完整打包通过，Mac 签名验证通过；phone-mcp Skill 结构校验通过。未运行 GitHub 托管 CI。

本轮另修正 HTTP 回归测试的临时目录清理竞态：先等待后台任务的回执完成，再删除测试目录，避免后台原子替换与测试删除并发。

公网服务端源码和隔离部署配置尚未取得，因此未验证或修改现网的去重、持久化和重启行为。服务端还需故障注入覆盖强杀、磁盘写满、保留期和多端竞争。亮屏恢复需要真机测试权限撤销、进程重启与保留数据升级；本次只做本机回归和打包，不安装、不改变手机状态。

## 2026-10-04 Mac 21 远程命令

统一本机检查通过：51 项 Python 测试、28 个 Java 测试程序、两个 Go 模块 race、真实中继 TLS 联合合约、13 个 Swift 测试程序与 Mac 全量源码编译。新增命令回归覆盖远程命令参数、机型与 Shizuku 状态、提交应答丢失后的原编号查询、App 重启、本地恢复后查询、缺失/不匹配回执、退出码与超时。第一轮完整检查的停止服务模拟请求有一次超时；该测试独立重跑、完整检查重跑及完整打包均通过，没有修改停止服务实现。

Mac 21 完整打包通过。真机验证固定使用中继通道，在现有本地连接保持在线时执行两个只读后台命令，核实管道、引号、机型输出与原任务查询；未执行唤醒，也未更新手机或公网中继。安装验收见 [发布记录](../releases.md#mac-21-远程命令)。

云端首轮失败来自已有 `McpHttpTest` 的慢请求写端：绝对期限生效后仍尝试写入已关闭连接。已修正测试，继续要求实际 408 应答与原超时上限；连续 12 次该用例、完整本机检查、Android 打包及修正后的 Mac/Linux CI 均通过。Mac 21 安装后的整包、签名、实际网关路径、本地/远程在线与远程只读命令复验均通过，手机和公网中继未更新。

## 2026-10-04 远程交互终端

Mac 22 / Android 61 增加独立的 Shizuku PTY 会话。统一本机检查通过：59 项 Python 测试、29 个 Java 测试程序、三个 Go 模块 race、Android/arm64 交叉编译、真实中继 TLS 联合合约、13 个 Swift 测试与 Mac 全量源码编译。新增回归覆盖创建去重与排队取消、查询不绑定服务、输入丢失后的原序号确认、重启恢复、字节游标与环形输出上限、租期与总期限、本机 TTY 的 Ctrl-C 转发、关闭与模式恢复，以及网关权限与重放策略。Linux CI 的真实 PTY 回归与最终 Mac 云端全量检查均通过。钥匙串助手增加未变化产物复用与跨目录真实编译摘要回归；云端发现的服务关闭覆盖已过期回执问题已修正，确定性用例能捕获原实现，修正后的 OperationJobsTest 连续 30 次通过。


最终 APK 和 App 均从干净提交 `8b6f407c8ccccb003530f9421cbeb8be520fbe3b` 构建并安装。PGT-AN20 / Android 15 经实际中继验证 PTY、目录与环境、Ctrl-C、尺寸、UTF-8、方向键和持续输出；输入应答丢失后不重发。已安装 Mac 客户端强杀后恢复原会话，原目录和环境仍在，关闭后 TTY 模式恢复。断读 64 秒验证租期到期，shell 与同会话后台子进程均被清理。原安装任务查询、摘要、后台服务自动恢复、两通道在线和随包客户端结果见 [Mac 22 发布验收](../releases.md#mac-22-远程交互终端)；公网中继未修改或重启。

## 2026-10-06 中英语言与深浅主题（本机构建）

Android 和 Mac 的设置页增加本端语言与外观选择，默认跟随系统，手动选择持久保存。两端使用同一份 1,016 条英文显示词典；连接与权限模型仍保留原来的规范文案，文件名、命令输出、通知正文和实际目录保持原文。Android 常驻通知的颜色跟随系统通知底色，避免与应用主题选择不同时降低对比度。实现与维护边界见 [显示词典说明](../../app/i18n/README.md)。

最终源码的 `scripts/check.sh` 全部通过，包含 34 个 Java 测试程序、17 个 Swift 测试程序、Python 回归、四个 Go 模块 race、真实中继 TLS 联合合约与 Mac 全量源码编译。新增回归覆盖语言选择持久化与非法值回退、中英显示切换、连接与权限组合文案、未知内容与参数原值保留，包含用户内容中的花括号；规范状态没有随翻译改变。

`scripts/build-mac-app.sh` 与其调用的 Android 完整构建通过。APK 与 Mac App 的签名验证通过，两端包内词典与源码一致，Mac 本地化资源与随包 APK 的字节一致性通过。Mac 新增设置区域使用隔离的原生渲染程序检查中文／英文 × 浅色／深色四种组合，360pt 宽度下文字与控件完整显示；该程序没有启动实际手机工位服务或修改实际应用的持久偏好。

本次只修改源码和生成本机产物，未提交、推送、安装、更新运行中的应用或操作手机与公网中继。两端真实菜单点击、退出重开、系统语言／明暗改变，以及 Android 返回旧页、表单草稿、通知重建、大字号／窄屏和系统生命周期尚未实测；本机回归与新增控件的原生渲染不能替代这些交互验收。

## 2026-10-06 安装与 Android 运行时修复

随后用户要求安装。首个 APK 的实际安装成功，但 Android ICU 对未转义右花括号的处理与本机 JDK 不同，导致翻译词典初始化崩溃、自动恢复失败。已转义模板检测与替换中的右花括号，重新完成 Android 与 Mac 完整构建；将实际 `StationTextTest` 和词典在手机 Android 15 的 ART 中执行，连接与权限组合、参数和未知内容保留等回归通过。测试改用 Android 同样支持的 `Files.readAllBytes` 读取词典；不能以桌面 JDK 的正则测试替代手机运行时。

首个远程安装任务没有重放。用户明确允许 adb 修复后保留数据安装修正版，主页、稳定进程、MCP 前台服务和真实远程连接核实；Mac 已完成备份、替换、签名及完整目录核对，两通道在线。具体摘要、恢复方式和原任务的失败事实见 [安装验收](../releases.md#中英语言与深浅主题安装2026-10-06)。此前未完成的界面点击、字号与系统切换验收仍未完成，公网服务未改动，源码未提交或推送。

## 2026-10-06 应用弹窗样式

Android 的语言、主题、通知显示方式、缺权限提示与清除配置确认改为共用应用弹窗，在原生 Dialog 窗口中提供卡片、图标、勾选、语义按钮与受可用高度限制的滚动。页面销毁时清理弹窗，保存失败继续显示原有反馈。Mac 的语言／主题菜单和远程清除确认使用面板内的统一弹窗，底层页面禁用并从辅助功能中隐藏，页面、窗口关闭和 Escape 清理弹窗状态，避免另开窗口影响现有菜单栏关闭行为。

`lib/android/build.sh` 与 `scripts/build-mac-app.sh` 的完整构建和随附回归通过，显示词典的两项 Python 检查、文档链接与 diff 空白检查通过。本轮没有把此前的 `scripts/check.sh` 全量结果记作新弹窗源码的全量检查。Mac 用实际 SwiftUI 组件离屏渲染了语言、主题、清除确认三类弹窗的中英 × 浅深色，共 12 种组合，标题、选中状态、正文和按钮未截断；复核后移除语言选项的文字型图标，并提高清除说明对比度。

正式 Mac 窗口的界面读取超时，未完成实际点击、键盘焦点、Escape、遮罩与取消验收。Android 真机弹窗外观、大字号／窄屏、读屏与页面重建后的窗口清理也尚未实测；源码约束和构建通过不替代这些设备验收。最终原生渲染与构建记录留在忽略目录 `build/.phone-station/dialog-install-20261006/`。

两端随后按已有安装授权完成更新。Android 原远程任务的内容、自动启动证明与前台服务／远程恢复均确认；Mac 完整包、签名和正式安装目录的进程核对，最终两条通道在线。具体摘要与部署回执见 [应用弹窗安装](../releases.md#应用弹窗更新与安装2026-10-06)。安装验收时未提交或推送；随后用户另行明确要求提交和推送。公网中继未更新。

## 2026-10-16 资源与耗电基线

为架构改造留对照数据，设计见[改造路线](../roadmap.md)与[统一链路与设备身份](../link-v2.md)。手机为 PGT-AN20 / Android 15，安装 Android 69；Mac 为 0.1.0 (33)，中继 5。手机侧全部经已有远程通道的 `scripts/shell.sh` 以 Shizuku shell 只读执行 `ps`、`dumpsys meminfo`、`dumpsys procstats --hours 24`、`dumpsys batterystats` 与 `/proc/<pid>/stat` 采样，没有使用 adb、修改设置或打开页面；临时文件写在 `/data/local/tmp`，读取后已删除。采样时手机已开机 5 天 14 小时，正在充电，亮屏且手机工位主页在前台，远程连接空闲。

| 项目 | 结果 |
| --- | --- |
| 主进程当时 | PSS 约 130 MB，其中图形 64（全屏窗口的 EGL 缓冲 60）、native 26、Java 11；RSS 233 MB，40 个线程 |
| 主进程 24 小时 | 59% 的时间主页在前台，平均 RSS 172 MB、峰值 359 MB；其余时间只有前台服务，平均 37 MB（33–43） |
| Shizuku 助手 | `:torch` PSS 61 MB、`:clipboard` 60 MB、`:shell` 60 MB（每条命令临时一个）；各约 57 MB 私有匿名内存，RSS 约 122 MB |
| 耗电 | 拔电后 16 小时共 34.9 mAh，约占计算耗电 4634 mAh 的 0.75%；屏幕 20.0（主页前台 12 分钟）、CPU 10.8、Wi-Fi 4.0、蜂窝 0 |
| 网络 | Wi-Fi 约 6400 个包、1.1 MB，与 20 秒一次的中继心跳相符；蜂窝活跃 30 秒 |
| CPU | 拔电后 16 小时累计 6 分 36 秒（用户态 3:33、内核态 3:03）；主页前台亮屏时，30 秒独立采样约占 2.5% 单核 |
| 唤醒 | 拔电后 16 小时闹钟唤醒 2 次，唤醒锁共约 7 秒 |
| Mac | 本机 adb 没有连接手机时，`footprint` 与 `ps` 约 12 秒采样读到 App 约 33 MB、空闲约 3% CPU、约每秒起一次 adb，网关约 13 MB、16 个线程，状态 watch 约 5 MB |

`:torch` 的启动时间与本次第一次只读 `shell.sh --status` 一致：`station_controls_status` 为报告手电状态而绑定该助手，之后它会常驻到服务停止或关闭手电功能。本次没有为此修改设置，采样结束时它仍在运行。第一次 CPU 采样被 CLI 每 0.5 秒查询任务结果的请求干扰，改为在手机上启动脱离会话的 30 秒采样。`procstats` 的包名参数没有过滤输出，改为先导出到临时文件再定位本包段落。

未覆盖：Mac 在“仅 adb”“adb 与远程都在”两种状态下的进程数和请求频率仍按代码推算；主进程 359 MB 峰值的来源未查明；助手内存偏高推测是因为 app_process 单独启动、拿不到 zygote 共享，尚未用 showmap 细分；息屏、蜂窝和长时间后台没有单独采样。上述 Mac 两种状态已在下一节补测。

## 2026-10-16 P1 部署前后复测

同一台 Mac mini 与 PGT-AN20 / Android 15，方法同上一节：Mac 用 `footprint` 与 `ps` 各采约 12 秒，手机经 Shizuku shell 只读读取 `ps`、`dumpsys meminfo`、`showmap`、`procstats` 和运行计数。部署过程见[发布记录](../releases.md#android-70--mac-35-p1-部署2026-10-16)。

| 项目 | 部署前：Mac 33、Android 69 | 部署后：Mac 34、Android 70 |
| --- | --- | --- |
| Mac App | footprint 40 MB，9–12 线程 | footprint 33 MB（峰值 35） |
| 网关 | footprint 13 MB，17 线程 | 12 MB |
| 12 秒内起的 adb | 16 次，约每秒 1.3 次：功能开关读取约每 2 秒一次，其余是连接配置写入；另有 5 次 `host-state.sh mark`、常驻 `host-state.sh watch` 每 2 秒写一次、1 次 `mcp.sh status` | 0，也没有起脚本 |
| 手机常驻助手 | 2 个（`:torch`、`:clipboard`） | 1 个；`id` 与 `true` 不新起进程 |

部署前「仅 adb」（手机远程开关关闭）与「adb 与远程都在」的内存和起进程数接近。两次部署前采样时，旧 App 自己的 `mcp.sh status` 约 40 毫秒后就删掉 18766 转发，本地 MCP 一直不在线；数字是这种状态下的稳态。

部署后：

- 手电打开后结束助手进程，灯随进程关闭；之后一次状态读取重新拉起助手，灯保持关闭。
- 手机运行计数（本次进程）：请求 796（剪贴板 136、通知 69、命令 2、控制 85、协议 504），系统设置写入 2，助手启动 2 次、退出 1 次，都记在剪贴板，中继重连 0。
- 主进程主页前台 RSS 约 274 MB，其中图形约 63 MB、EGL 约 59 MB，峰值主要来自全屏窗口的图形缓冲。`procstats` 里 69 的前台 RSS 峰值记为 419 MB；70 运行时间太短，峰值记为 0.89 GB，不能当结论，24 小时后再读。
- 助手私有脏页约 56 MB，其中 Dalvik 匿名内存 44 MB。它由 app_process 单独启动，拿不到 zygote 共享。
- 设备密钥：StrongBox 为 false。密钥在重装后第一次读服务状态时生成，不在下面五次里。五次签名分别是 22、15、18、14、19 ms，第一次之后四次的中位数 16.5 ms。
- `adb forward localfilesystem:` 能建立，但套接字权限是 0755，不是 0600；试完已删除，原 18766 转发保留。
- Wi-Fi 关几秒再开：Mac 34 不通过，Mac 35 在 30 秒内本地与远程自己恢复，原因见[故障排查](../troubleshooting.md)。
- 关掉再打开 USB 调试后，本地 MCP 没有自己恢复，手动处理后才恢复。不通过。
- 手机复制到 Mac 的延迟没有得到数字：一次到达但没有起点，其余几次因无线调试与 Shizuku 在重启后不稳定没有到达。现有日志不记成功同步，不能对齐两端时间；要测需另加两端事件时间。
- 重装后荣耀「应用启动管理」回到自动管理，应用不在前台时 MCP 断开；改为手动并允许自启动、关联启动和后台活动后，后台连接和剪贴板正常。

未覆盖：息屏与蜂窝、24 小时 `procstats`。复制延迟和 USB 调试关闭后的本地恢复见下一节。

## 2026-10-16 P1 三项复测

同一台 Mac mini 与 PGT-AN20 / Android 15。安装的是 Android 71 与 Mac 0.1.0 (36)，过程见[发布记录](../releases.md#android-71--mac-36-p1-复测2026-10-16)。复测前本地与远程都在线。中继仍是 5。

- 手机复制到 Mac：共享和自动同步本来就开着。经手机剪贴板写入一次新文字后，Mac 写入本机剪贴板，`usage.app` 的 `clipboardLatency` 为样本 1、最近 63 ms、不确定度 16 ms、最大 63 ms。计数里没有剪贴板正文。
- USB 调试关闭再打开：远程仍在时把 `adb_enabled` 写成 0。那次 shell 的结果未知，任务 `33c6fbe42dd84b15904b324e650a7cd2`；随后手机状态读到 `adbEnabled: false`，无线调试开关仍是开，adb 先显示 offline，约 30 秒后从列表消失。本地 MCP 随之不在线。Shizuku 随 adbd 停止，远程 shell 不能再写设置，所以没有把 USB 调试写回打开。Mac 36 的退避重试要求序列号还在而本地不在线；这次序列号离开了列表，没有观察到 5/10/20/30 秒的重试。不通过，本地没有恢复。
- 401 后刷新 adb 转发：工具调用没有走到 HTTP 401。网关令牌换掉后，状态流发现令牌不一致就断开，菜单栏约 1 秒后用新令牌重连；这期间调度器 `failed=0`，没有运行 `mcp.sh status`，18766 转发也没有重做。把手机本地令牌换成无效值后，网关探测失败，模式改成远程，调用仍成功。序列号还在，约 5.5 秒后菜单栏运行 `mcp.sh status`，本地重连次数从 1 变为 2，转发恢复，模式回到本地。这条是本地离线后退避，不是 401 分支。

用户重新打开 USB 调试后，菜单栏在 15:49:17 自动执行 `connect.sh` 并连上 `192.168.0.108:42973`。本地与远程都回到在线。关掉 USB 调试时序列号离开列表、退避没有跑到，这一条仍然不通过。

复测结束时：Android 71，Mac 36，模式是本地，本地与远程都在线，adb 转发 `tcp:18766` 指向手机 `8765`。复制延迟计数后来又有一笔，共 2 笔，最近 113 ms，不确定度 31 ms，最大 113 ms。没有提交或推送。

## 2026-10-16 僵死但仍显示为 device 的无线 adb

同一台 Mac mini 与 PGT-AN20 / Android 15，Android 71、Mac 0.1.0 (36)、中继 5。开始时只有一条无线 adb：`192.168.0.108:38289`，状态 `device`，本地与远程都在线。没有把 `adb_wifi_enabled` 写成 0，也没有关 USB 调试。

本机 `sudo` 需要密码，做不了只丢包的防火墙。手机 shell 不能改 iptables 或 tc，也不能向 adbd 发信号，所以没有做成「TCP 还在、只是不应答」的半开连接。这次改为在手机上关掉 Wi-Fi 约 8 秒再打开；另有一个独立进程在 14 秒时再执行一次打开。客户端先报结果未知，任务 `626b844300b549dcb10ec97be6023145`。原编号后来查到已完成，退出码 0，输出里 Wi-Fi 已重新打开。随后 `wifi_on=1`、`adb_wifi_enabled=1`。

22:41 起每秒看 `adb devices -l`、网关安全状态，以及是否出现 `adb shell true` 和 `connect.sh`：

- 22:41:50 网关改走远程，本地不在线，会话仍是 `device`。菜单栏对 `192.168.0.108:38289` 执行 `adb shell true`。
- 22:41:52 探测结束。菜单栏的连接重连计数从 7 变为 8。这几秒里没有看到 `connect.sh` 把这条仍为 `device` 的会话摘掉，同一条会话也没有复原。
- 22:41:59 adb 自己把这条标成 `offline`。重连计数变为 9，这时才出现 `connect.sh`。
- 22:42:03 旧端口仍是 `offline`，同时出现新端口 `192.168.0.108:42395` 为 `device`。22:42:04 只剩新端口。22:42:05 模式回到本地，本地与远程都在线。

结论：会话仍显示为 `device` 时，探测失败不会把它复原，`connect.sh` 也不会断开它。adb 把它标成 `offline` 之后，原来的重连会断开这条离线无线会话并连上新端口。15 秒复查和「网关重新在线后解除标记」没有作用在原来那条仍为 `device` 的会话上，因为 adb 在复查之前就改了状态。同一条 TCP 靠丢包后自行应答来复原，这次没有覆盖。结束时只有一条 `device`，Wi-Fi 与无线调试都是开的。没有提交或推送，公网中继没有改。

## 2026-10-16 本机 MCP 关闭时的按钮和暂停文案

同一台手机与 Mac 36。总开关、本地 MCP、远程原来都是开。从首页进入「MCP 服务」，关掉「本地 MCP 接入」。没有关远程，也没有用会同时关掉远程的停止广播。

手机页面：服务状态仍是「运行中」，说明是「本地：已关闭；远程：已连接。」三个功能组仍是「3 项可用」「1 项可用」「4 项可用」。没有「手机工位已暂停」。设置值为总开关 1、本地 MCP 0、远程 1。网关改成远程，本地不在线，远程仍在。

Mac 菜单栏面板：标题是荣耀 PGT-AN20，无线和远程都在。首页按钮仍是「打开投屏」「截取画面」「开始录屏」，下面是「保持亮屏」「手电筒」「灯光跟随声音」。MCP 一行是「运行中 · 远程」。共享剪贴板是「自动双向同步」，手机通知是「正在接收 · 1 个应用」。这些行都没有写成「手机工位已暂停」。当时远程 `station_controls_status` 读到 `master` 为 true、`localMcp` 为 false，没有带「暂停」的原因。打开面板时看到的 adb 读取是息屏相关的两项设置，不是功能开关投影；远程还能读到开关，所以没有走到那条 adb 投影。

把「本地 MCP 接入」打开后，手机回到「本地：运行中；远程：已连接。」总开关、本地 MCP、远程都是 1。网关在接下来约 8 秒里仍是远程、本地不在线，菜单栏没有自己刷新转发。运行一次 `mcp.sh status` 之后本地马上在线，大约 13 秒后模式回到本地。结束时仍是这一条无线 adb，本地与远程都在线。没有提交或推送。

## 2026-10-16 运行计数的大字号、窄屏、中英和折叠

同一台 PGT-AN20 / Android 15，Android 71。屏幕 1312×2848，原来的字号是 1.0，密度是物理 560（宽约 375 dp）。没有拨无线调试、自动保持、总开关、本地 MCP 或功能开关，也没有打开系统的通知使用权。`adb_wifi_enabled` 前后都是 1，始终只有 `192.168.0.108:42395` 这一条 `device`。

首页、连接设置、连接检查、运行计数、MCP 服务、共享剪贴板、通知、选择应用、电脑提醒铃声、设置、功能管理、权限与检查、关于，都从画面上读到了该页的标题或主要文案。连接检查展开后能看到 Wi-Fi 网络、本地电脑、本地 MCP、远程通道、调试写入权限。功能管理能看到通知与剪贴板、文件访问、命令与安装、屏幕与手机控制，再往下还有远程访问范围；没有拨那些开关。选择应用只看到搜索框，没有改勾选。铃声页只确认标题，没有改选。显示方式对话框能看到「电脑提醒显示方式」和「取消」，点了取消。关于页有版本和作者，设置行上的版本是 71。语言原来是跟随系统。

远程接入和远程访问范围的窗口分别是 `RemoteRelayActivity`、`RemotePermissionsActivity`。这两页有 `FLAG_SECURE`，截屏没有可读文字，没有读页面内容，也没有改开关。

运行计数先展开，再改显示：

- 字号 1.0、宽约 375 dp：五行左右排列。长数字在右侧换行，右括号留在屏幕里。
- 字号改成 1.45 后页面重建，五行仍展开，名称在上、数字在下，左对齐，右括号都在。
- 字号回到 1.0，再把密度临时改成 676（宽约 311 dp，小于 340）。五行仍展开，仍是上下排列，数字完整。计数卡片本身完整；它上面的远程卡片被滚出屏幕上沿。
- 密度恢复后改成英文：Requests、Settings writes、Helper starts、Helper stops、Reconnects，数字也是英文，左右排列，右括号在。

测完字号回到 1.0，密度回到物理 560，语言回到跟随系统，首页恢复中文。没有提交或推送。

## 2026-10-16 Android 71 的 24 小时 procstats 尚未到期

同一台 PGT-AN20。当时 `ps` 里主进程 `dev.phonestation.adbkeep` 已运行 7 小时 44 分，`:helper` 已运行 7 小时 38 分。离约 24 小时还差一截，这次没有读 `procstats`，也不把这段运行时间里的内存当结论。Android 70 的前台峰值 0.89 GB 仍然只是短时数字。满约 24 小时后再按[基线](#2026-10-16-资源与耗电基线)的方法读。没有改设置，没有提交或推送。

同日晚些时候，下面「读令牌超时」那次观察结束后，原来的主进程已经不在，新进程重新计时。24 小时仍要从新进程算起，这次仍不读 `procstats`。2026-10-17 按 `362e30d` 同版本再装后，主进程再次更换，计时再从头开始。见[同版本再装](../releases.md#android-71-同版本再装2026-10-17)。13:07 再看时这枚进程仍未满 24 小时，见[仍未满 24 小时](#2026-10-17-android-71-主进程仍未满-24-小时)。

## 2026-10-16 息屏与蜂窝

同一台 PGT-AN20 / Android 15，Android 71。采样仍走远程 Shizuku 的 `ps`、`dumpsys meminfo` 和 `/proc/<pid>/stat`，没有读 `procstats`，也没有把 `adb_wifi_enabled` 写成 0。基线那次是亮屏、充电、主页在前台。

息屏：`mScreenState=OFF`、`mWakefulness=Dozing`，这段 20 秒前后都是这个状态。没有在充电，电量 98。主进程 PSS 229 MB、RSS 373 MB，其中图形大约 4 MB；基线亮屏前台的图形大约 60 MB。`:helper` RSS 84 MB。这 20 秒里主进程用了 0.76 秒 CPU，大约占一核的 3.8%。采样命令当时还连着，所以这不是把远程调用摘掉之后的空闲数字，也不能拿来说明比亮屏的 2.5% 更低。

蜂窝：先确认移动数据开关是开的，再关掉 Wi-Fi。大约 2 秒后默认路由离开 `wlan0`，随后 10 秒一直在 `rmnet_data3`。这段时间屏幕是亮的，`mWakefulness=Awake`。主进程 PSS 258 MB，`ps` 的 RSS 341 MB。这 10 秒用了 0.12 秒 CPU，大约占一核的 1.2%。同一条命令末尾把 Wi-Fi 打开，结束后 `wifi_on=1`，路由回到 `wlan0`，`adb_wifi_enabled` 仍是 1。无线会话换成 `192.168.0.108:39473`，只有这一条 `device`。没有提交或推送，公网中继没有改。

## 2026-10-16 序列号还在时读令牌超时的转发退避

同一台 PGT-AN20，Android 71，Mac 36。总开关、本地 MCP、远程都是开。没有改令牌，也没有关 USB 调试。当时只有 `192.168.0.108:39473` 这一条 `device`，`adb_wifi_enabled` 是 1。用大约 180 路并行的服务 dump 把下一次读令牌拖过 2 秒，输出全部丢掉，窗口大约 75 秒。这 75 秒里序列号一直还在，状态一直是 `device`，主进程一直是 7788。

菜单栏状态大约每秒看一次，不刷新转发：

- 23:38:40 本地在线，模式是本地。
- 23:38:42 本地掉线、模式改成远程，大约 1 秒后回到本地。这一秒没有看到 `mcp.sh status`。不算进退避。
- 23:39:17 起本地再次掉线，模式保持远程，一直到观察结束的 23:39:54。
- 不含 `--safe`、也不含 `snapshot` 的 `mcp.sh status` 出现在 23:39:22–23 和 23:39:34–35。从这次掉线到第一次大约 5 秒。两次相隔大约 12 秒，和第一次跑完后再等 10 秒相符；采样大约每秒一次，中间有个别秒没有读到。
- 20 秒和 30 秒这两档在窗口里没有出现。第二次在 23:39:35 还能看到，洪水大约 23:39:54 停掉，20 秒那一档还没到。

停掉之后本地仍不在线。随后一次服务 dump 马上失败，进程列表里已经没有 7788。无线会话变成 `192.168.0.108:39473` `offline`，接下来大约 20 秒没有自己回到 `device`。当时没有第二条在线会话，于是运行 `connect.sh`：连上 `192.168.0.108:42537`，整理后只留这一条 `device`。没有把 `adb_wifi_enabled` 写成 0。结束后 `wifi_on=1`，默认路由在 `wlan0`，三个开关仍是 1。Shizuku 进程还在。新的主进程已在跑。`mcp.sh status` 之后本地在线；远程随后自己恢复，网关远程重连次数从 9 变为 10。结束时本地与远程都在线。

这次只确认到大约 5 秒和大约 10 秒两档。20 秒和 30 秒没有在这次窗口里看到，不能写成四档都通过。没有提交或推送，公网中继没有改。

## 2026-10-17 读令牌变慢时本地探测仍在

同一台 PGT-AN20，Android 71，Mac 36。只有 `192.168.0.108:39347` 为 `device`，主进程一直是 365。没有改令牌，没有关 USB 调试，也没有再做前一晚那种大约 180 路、把 dump 输出拉回电脑的洪水。

在手机上同时跑服务 dump，输出留在手机里：80 路时下一次 dump 2.6 秒，130 路 4.4 秒，160 路 4.9 秒，已经超过读令牌的 2 秒上限。接着把在途 dump 维持在大约 100 路、约 100 秒。这段时间网关本地一直在线，模式一直是本地，没有出现 `mcp.sh status`。

再从电脑侧同时拉 25、45 和 120 路同样的 dump，输出直接丢掉。120 路都还在跑时，本地仍在线，往返 14 毫秒。直连转发端口大约 20 毫秒就有响应。主进程和这条 `device` 都还在。

转发退避要等网关的本地探测失败才开始。探测超时是 1 秒，走 HTTP，不走这次服务 dump。只把 dump 拖过 2 秒并没有让本地探测失败，所以 20 秒和 30 秒这两档仍然没有出现。没有提交或推送，公网中继没有改。后来不拖 dump，让这次 HTTP 探测本身失败，四档都看到了，见[四档退避](#2026-10-17-本地-http-探测失败时的四档转发退避)。

## 2026-10-16 工具调用的 HTTP 401 仍未覆盖

同一轮没有改令牌，工具调用也没有收到 HTTP 401。`ClipboardProtocol` 里只有状态码 401 才会刷新转发；这次没有走到。上一节的两次 `mcp.sh status` 是本地掉线后的 5/10 秒退避，不是 401 分支。手机令牌无效时网关改走远程、约 5.5 秒后刷新转发，仍是[三项复测](#2026-10-16-p1-三项复测)里已经记下的那条，本轮不重复。这一项仍未覆盖。

## 2026-10-16 序列号离开后退避不跑的复核

同一台 PGT-AN20 / Android 15，Android 71，Mac 36。远程仍在时，用 Shizuku 把 `adb_enabled` 写成 0，没有写 `adb_wifi_enabled`。任务 `718ca20b92004eef9c66d4eb663bdb51` 的执行结果未知。按原编号再查时，Shizuku 已经连接中断，没有重做。

23:48:39 只有 `192.168.0.108:42537` 为 `device`，本地与远程都在线。23:48:40 这条变成 `offline`，本地不在线，远程仍在。同一秒菜单栏启动 `connect.sh`，链路重连计数从 18 变为 19。这是列表里已经没有 `device` 时的自动连接，大约跑到 23:48:48，没有连上新的 `device`。

从 23:48:49 到 23:49:23，这条一直停在 `offline`。脚本计数停在 758，没有看到 `mcp.sh status`。5 秒、10 秒和 20 秒都落在这段里，转发退避没有跑。23:49:23 之后再看，adb 列表已经空了。链路重连计数变为 20，脚本计数变为 763；中间没有逐秒看进程，这多出来的一次链路计数仍是自动 `connect.sh`。列表没有恢复 `device`，本地仍不在线，远程仍在。

手机控制状态仍是 PGT-AN20、版本 71，总开关、本地 MCP 和远程都开着。截屏、手电和 shell 都要求在手机上重新启动 Shizuku。USB 调试保持关闭，这次没有写回去。

结论与[三项复测](#2026-10-16-p1-三项复测)相同：序列号不再是 `device` 之后，5/10/20/30 秒的转发退避不跑，本地不恢复。

2026-10-17 00:28 用户重新打开后，`adb_enabled`、`adb_wifi_enabled` 和 Wi-Fi 都是 1，Shizuku 13 已授权。菜单栏进程还是原来的，无线调试已经在广播。从 00:28:01 到 00:29:16，adb 列表一直是空的，链路重连计数停在 20，没有看到 `connect.sh`。这次没有复现下午那种自己连上。空列表没有新的设备事件时，自动连接要等下一次读取才会再试，这段时间没有再读。00:30 列表仍空，于是手动运行 `connect.sh`，连上 `192.168.0.108:39347`，只有这一条 `device`。`mcp.sh status` 之后本地在线，模式回到本地。00:31 远程仍在确认，重连次数还是 10。00:36 再看时远程已在线并已确认，重连次数变为 11。当时只有这一条 `device`，USB 调试、无线调试和 Wi-Fi 都是 1。没有提交或推送，公网中继没有改。

## 2026-10-17 Android 71 主进程仍未满 24 小时

同一台 PGT-AN20 / Android 15。只读 `scripts/status.sh`：只有 `192.168.0.108:39347` 为 `device`，机型是 PGT-AN20，局域网无线调试广播也是这个地址。没有新建第二条连接，没有把 `adb_wifi_enabled` 写成 0。

13:06:57 手机时间，`ps` 里主进程 `dev.phonestation.adbkeep` 是 12374，已运行 4 小时 52 分；`:helper` 是 12872，已运行 4 小时 51 分。这枚主进程就是同版本再装后换上的 12374。离约 24 小时还差一截，没有读 `procstats`，也不把这段运行时间里的内存当结论。Android 70 的前台峰值 0.89 GB 仍然只是短时数字。没有改设置，没有提交或推送。

## 2026-10-17 本地 HTTP 探测失败时的四档转发退避

同一台 PGT-AN20 / Android 15，Android 71，Mac 36。总开关、本地 MCP、远程都是开。只有 `192.168.0.108:39347` 为 `device`，`adb_enabled` 和 `adb_wifi_enabled` 都是 1。没有改令牌，没有关 USB 调试，没有新建第二条连接，也没有用服务 dump。

网关每秒向 `127.0.0.1:18766/mcp` 发一次 HTTP POST，超时 1 秒。这条转发原来指向手机 `8765`。观察时把它改到手机上没有进程监听的端口，并在窗口里维持这个转发，探测因此连接失败。adb 的控制连接没有动，序列号可以一直停在 `device`。

13:14 先看过一次，进程采样大约 0.25 秒一次，还和改转发的命令抢 adb。本地掉线后大约 5.5 秒、36 秒、70 秒看到 `mcp.sh status`，10 秒那档落在采样缝里，不能分开。13:19 用大约 50 毫秒一次的进程采样再做，主进程仍是 12374，`:helper` 仍是 12872。

13:19:13 本地从在线变为不在线，模式改成远程。菜单栏做了一次 `adb shell true`，adb 计数从 323 变为 324。没有 `connect.sh`，链路重连计数停在 21。不含 `snapshot`、`watch` 和 `--safe` 的 `mcp.sh status` 分成四组，时间从这次掉线算起：

- 5.42 秒起：5 秒那档。
- 16.54 秒起：距上一组结束大约 10.7 秒，10 秒那档。
- 38.59 秒起：距上一组结束大约 21.6 秒，20 秒那档。
- 70.94 秒起：距上一组结束大约 32.0 秒，30 秒那档。

每一组里零点几秒内又出现了一次 `mcp.sh status`，间隔不是这四档，算同一次触发后的连续刷新。这 80 秒里本地一直不在线，网关的本地探测计数大约每秒加一。序列号一直是这一条 `device`。主进程没有换。

13:20:33 把转发改回手机 `8765`。大约 1 秒后本地重新在线，没有再手动跑 `mcp.sh status`。模式随后回到本地，远程仍在，往返大约 95 毫秒。结束时仍是这一条 `device`，`adb_enabled` 和 `adb_wifi_enabled` 都是 1。没有提交或推送，公网中继没有改。

## 2026-10-17 工具调用的 HTTP 401 仍未覆盖

同一轮没有改令牌，菜单栏的工具调用也没有收到 HTTP 401。`ClipboardProtocol` 只在 HTTP 状态码 401 时刷新转发；网关在令牌对不上时才回这个状态。正在使用的调用带着当前网关令牌，手机令牌也有效，所以这条分支没有走到。不重复[三项复测](#2026-10-16-p1-三项复测)里换令牌的那次。这一项仍未覆盖。

## 2026-10-17 只丢包而 TCP 仍挂着仍未覆盖

同一台 Mac 与 PGT-AN20。本机 `sudo -n` 要密码，`pfctl` 和 `dnctl` 因此没有用。本机没有 `tc`。手机 shell 是 uid 2000：`iptables -L` 报 filter 表 Permission denied；`tc qdisc show` 能看，`tc qdisc add dev dummy0 root netem loss 100%` 报 `Cannot talk to rtnetlink: Permission denied`，dummy0 仍是 noqueue。没有写成丢包规则，没有动 `wlan0`，没有关 USB 调试，也没有把 `adb_wifi_enabled` 写成 0。同一条 TCP 在丢包后靠 15 秒复查或网关重新在线复原，仍然没有覆盖。

## 2026-10-17 重新打开本地 MCP 时应刷新转发

对照 Mac 36 源码里的 `McpSession`：`mcp.sh status` 只在序列号变化、工具调用的 HTTP 401，或「序列号还在、本机 MCP 开着、本地不在线」的 5/10/20/30 秒退避里运行。退避挂在本地从在线变成离线那一下；本机 MCP 关着时 `wantsLocal` 为假，这一下不会排上。再打开时序列号没变，本地也已经是离线，没有新的边，所以不会自己刷新。这和[本机 MCP 关闭](#2026-10-16-本机-mcp-关闭时的按钮和暂停文案)里约 8 秒没有自己刷新相符。

本机 MCP 从关到开、序列号还在、本地仍不在线，应当补一次转发刷新；已经在线、没有序列号，或只是第一次读到开着，不刷新。这个判断写进 `McpSession.shouldRefreshAfterLocalMcp`，`Station` 在同步开关时调用 `noteLocalMcp`。本机测试盖住了从关到开、本来就开、没有序列号、空序列号和本地已在线。正在运行的 Mac 36 还没有这处改动。这次没有关远程，也没有再拨手机上的本地 MCP。没有提交或推送。

## 2026-10-17 重新打开本地 MCP 后菜单栏自己刷新转发

Mac 0.1.0 (37) 换上 `/Applications/手机工位.app` 之后，在同一台 PGT-AN20 / Android 15、Android 71 上只拨了「本地 MCP 接入」。没有关远程，没有关 USB 调试，没有改令牌，没有把 `adb_wifi_enabled` 写成 0，也没有用服务 dump 拖超时。安装见[发布记录](../releases.md#mac-37-重新打开本地-mcp2026-10-17)。

开始时只有 `192.168.0.108:39347` 这一条 `device`，转发是 `tcp:18766 tcp:8765`。总开关、本地 MCP、远程都是开，`adb_enabled` 和 `adb_wifi_enabled` 都是 1。主进程仍是 12374，`:helper` 仍是 12872。

14:24:08 关掉「本地 MCP 接入」。手机页服务状态仍是「运行中」，说明是「本地：已关闭；远程：已连接。」设置变成总开关 1、本地 MCP 0、远程 1。02:24:18 网关模式改成远程，本地不在线，远程仍已确认。序列号没变。转发一直还是 `tcp:18766 tcp:8765`；本地监听已经停了，所以这条转发本身没有把本地探活拉回来。菜单栏面板标题仍是荣耀 PGT-AN20，无线和远程都在。MCP 一行是「运行中 · 远程 · 文件、命令与屏幕」。共享剪贴板仍是「自动双向同步」，手机通知仍是「正在接收 · 1 个应用」。没有「已暂停」，也没有「手机工位已暂停」。

14:28:01 再打开同一个开关。当时序列号仍是这一条，本地仍不在线。没有手动跑 `mcp.sh status`。02:28:07（打开后 5.7 秒）菜单栏自己运行了安装目录里的 `mcp.sh status`。零点三秒内出现两组 zsh，算同一次触发后的连续刷新，不是 5/10/20/30 秒退避的下一档。这组开始后大约 1 秒，本地重新在线，模式回到本地，远程仍在，往返 15 毫秒。转发仍是 `tcp:18766 tcp:8765`。之后到进程采样结束，没有再出现不含 `snapshot`、`watch` 和 `--safe` 的 `mcp.sh status`。链路重连计数仍是 0，没有新建第二条连接。

结束时手机页是「本地：运行中；远程：已连接。」，本地 MCP 开关开着。总开关、远程、功能勾选和远程权限与拨开关之前一致。`adb_enabled` 和 `adb_wifi_enabled` 仍是 1。仍是这一条无线 adb。主进程和 `:helper` 没有换。`ps` 的已运行时间在 14:18 是 1 小时 16 分，14:28 是 1 小时 26 分；这和[仍未满 24 小时](#2026-10-17-android-71-主进程仍未满-24-小时)里 13:06 的 4 小时 52 分对不上。这次只确认进程号没换，没有读 `procstats`，也不把这组短时间当成内存结论。

工具调用的 HTTP 401、只丢包而 TCP 仍挂着，仍然没有覆盖。没有提交或推送，没有重装手机，公网中继没有改。P1 仍未完成。随后不再用运行时长挡住后续，见[不再等待运行时长](#2026-10-17-不再等待运行时长)。

## 2026-10-17 不再等待运行时长

约 24 小时 `procstats` 不再挡住 P1 收口，也不挡住后面的阶段。进程年龄用 `/proc/<pid>/stat` 的启动时刻和 `/proc/stat` 的 `btime` 计算。满约 24 小时时按[基线](#2026-10-16-资源与耗电基线)补读；没满就跳过并继续。短时数字仍然不能当结论，Android 70 的前台峰值 0.89 GB 仍是短时数字。主进程 12374 在 13:06 记成已运行 4 小时 52 分，14:18 记成 1 小时 16 分，两笔对不上。补读前先重算，不沿用其中一笔。

工具调用的 HTTP 401，以及只丢包而 TCP 仍挂着，这台机器做不到。菜单栏的工具调用带着当前网关令牌；本机 `sudo` 要密码，手机 shell 是 uid 2000，改不了 `iptables` 或 `tc`。这两项记为限制，不再用改令牌、关 USB 调试或服务 dump 去重试。有别的可操作办法时再验；验出问题就修。

Mac 35 / Android 70 上通过的常驻助手 1 个、连续 shell、手电随助手退出、空闲不起进程和 Wi-Fi 短断，不依赖运行时长。写下这段时还没在 Android 71 / Mac 37 上复采；随后已经通过，见[复采](#2026-10-17-android-71--mac-37-复采常驻助手与-wi-fi-短断)。主进程年龄已按 `/proc` 重算，未满约 24 小时，见[主进程年龄](#2026-10-17-主进程年龄未满-24-小时)。改现有通道仍按[切换与回滚](../link-v2.md#切换与回滚)。写下这段时助手源码还没安装；随后已装到 Android 72，见[共用助手](#2026-10-17-android-72-共用助手)。当时 L4 还没开始。随后本地和远程握手只在测试里做了，见[本地握手](#2026-10-17-本地握手只在测试进程)和[远程握手](#2026-10-17-远程握手只在测试进程)。

写下这段时只改了文档，没有新的采样。没有提交或推送。

## 2026-10-17 Android 71 / Mac 37 复采常驻助手与 Wi-Fi 短断

同一台 PGT-AN20 / Android 15，Android 71，Mac 0.1.0 (37)，中继 5。先只读 `scripts/status.sh`：只有 `192.168.0.108:39347` 为 `device`，机型是 PGT-AN20，局域网无线调试广播也是这个地址。没有新建第二条连接，没有把 `adb_wifi_enabled` 写成 0，没有改令牌，没有关 USB 调试，没有用服务 dump。五项都过了，没有改源码，没有重装手机，也没有换 Mac。

空闲时用 adb 只读 `ps`，不走会拉起助手的 shell。剪贴板、shell、手电这一路只有 `dev.phonestation.adbkeep:helper` 12872，没有 `:torch`、`:clipboard`、`:shell`、`:screen` 或 `:terminal`。主进程仍是 12374。

连续两条 `echo` 走手机工位 shell。第一条输出 `station-recheck-1`，第二条输出 `station-recheck-2`。两条当时的进程列表都是主进程 12374 和 `:helper` 12872，没有新起助手。

手电当时关着。`station_controls_status` 读到 `on: false`，并带 `maxLevel: 4`，这是助手还在时的相机读数。`station_torch` 打开后返回 `on: true`，助手仍是 12872。接着结束 12872。进程列表里助手消失，主进程仍是 12374。紧接着的状态读数是 `on: false`，没有 `maxLevel`，这是助手不在时直接报告未开的那条路径。后来又出现 `:helper` 30882，再读一次是 `on: false` 且 `maxLevel: 4`，灯仍关着。结束助手会打断剪贴板监听；这次没有单独计重绑时间。灯留在关。

Mac 面板关掉。采样前和采样后，PhoneStation 的窗口数都是 0。大约 20 秒里，菜单栏、随包网关和已经在跑的 `watch-status` 都没有再起子进程，也没有新的 `adb`、`mcp.sh` 或 `connect.sh`。

Wi-Fi：14:48:58 手机时间，`wifi_on=1`，`adb_wifi_enabled=1`，仍是 `192.168.0.108:39347` 这一条 `device`。经 shell 执行关掉大约 8 秒再打开，任务 `c728e22d8450431d8735bed311d97dc6`。客户端先报结果未知，没有重做。原编号后来查到已完成，退出码 0，用时 8291 毫秒，手机时间 14:49:08，输出里 `wifi=1`、`adb_wifi=0`。这次命令只读了这个设置，没有写入。Wi-Fi 断开时系统会把无线调试开关写成 0，见[开关为什么会自己关](../adb-keep.md#开关为什么会自己关)。另外两个脱离会话的进程在 14 秒和 22 秒时再执行一次打开，分别在 14:49:12 和 14:49:21 留下记录；临时文件随后删除。

从观察开始大约 13 秒，这条会话变成 `offline`。菜单栏自己运行了安装目录里的 `connect.sh`。大约 18 秒时旧端口仍是 `offline`，同时出现 `192.168.0.108:43333` 为 `device`；大约 19 秒后只剩这一条。这次观察没有读到网关状态。随后网关已是本地在线，转发是 `tcp:18766 tcp:8765`，远程还在确认。14:52:55 再读：`wifi_on=1`，`adb_wifi_enabled=1`，`adb_enabled=1`，模式本地，本地与远程都在线，远程已确认，往返分别是 63 毫秒和 87 毫秒。仍是 `192.168.0.108:43333` 这一条 `device`。局域网里旧端口 `39347` 还以带 `(2)` 的名字在广播，没有去连它。主进程仍是 12374。没有提交或推送，公网中继没有改。

## 2026-10-17 主进程年龄未满 24 小时

同一台 PGT-AN20，主进程仍是 12374。14:52:22 手机时间，`/proc/stat` 的 `btime` 是 1792174236，`/proc/12374/stat` 的启动时刻是 3908940 个时钟滴答，`CLK_TCK` 是 100。启动时刻是 1792213325.40，对应大约 13:02:06。当时年龄 6617 秒，也就是 1 小时 50 分。没有满约 24 小时，没有读 `procstats`。Android 70 的前台峰值 0.89 GB 仍是短时数字。

13:06 那笔 4 小时 52 分和 14:18 那笔 1 小时 16 分都没有沿用。按这次启动时刻，14:18 会是大约 1 小时 16 分，13:06 只有大约 4 分钟，所以对不上的是 13:06 那笔。没有改设置，没有提交或推送。

## 2026-10-17 Android 72 共用助手

同一台 PGT-AN20 / Android 15，Mac 0.1.0 (37)，中继 5。源码在 `b2c674a` 之上的未提交工作区。安装前 `scripts/check.sh` 通过，含 43 个 Java 测试、22 个 Swift 测试和 Mac 全量源码编译；它不连手机。通过之后才把清单从 71 改为 72。Shizuku 的 `UserServiceArgs.version` 源码里仍是 71，没有改成 72，也没有改 Mac 版本。安装见[发布记录](../releases.md#android-72-共用助手2026-10-17)。

安装前只读 `scripts/status.sh`：只有 `192.168.0.108:43333` 为 `device`，机型是 PGT-AN20。局域网里旧端口 `39347` 还以带 `(2)` 的名字在广播，没有去连。主进程 12374，`:helper` 30882。`firstInstallTime` 是 2026-10-17 00:24:17。剪贴板共享开着，自动同步也开着。手电关着，状态里有 `maxLevel`。`adb_enabled` 和 `adb_wifi_enabled` 都是 1。远程版本是 71。

`android.sh --remote` 保留数据安装，任务 `4affab5329a346b294df1581ca880c43`。APK SHA-256 `55cb81109a1537864f2599054c8f42edc9781230fecbc6c3e5d4ead0f96a7fac`，`verified: true`、`completed: true`。没有卸载。装完远程版本 72，主进程换成 21407，`:helper` 换成 21685。`firstInstallTime` 仍是 2026-10-17 00:24:17，`lastUpdateTime` 变成 2026-10-17 16:10:44。剪贴板仍是共享并且自动同步。没有把 `adb_wifi_enabled` 写成 0，没有关 USB 调试，没有改令牌，没有用服务 dump，也没有再拨本地 MCP。

16:13 再读 `scripts/status.sh`，仍是 `192.168.0.108:43333` 这一条 `device`。然后开了一个短终端，会话 `29724184693a4c499fd91167f09227d7`。读到 `running` 时，进程列表里没有 `:terminal`。PTY 子进程 22391 的父进程是 `:helper` 21685。关掉后状态是 `closed`，再读是 `lost`。剪贴板共享还开着，21685 留下了。

接着短开一次投屏，会话 `81cecde0a69142ef9c849a283ec10198`。采集进程 `screen-arm64` 22413 的父进程仍是 21685，没有 `:screen`。当时状态还是 `starting`，`videoFrames` 是 0，没有等画面送到 Mac。关掉后状态是 `ended`，再查是 `lost`，采集进程不在了。21685 仍在。没有留下会话。

手电全程关着，没有为了这项开灯。灯、远程、USB 调试、Wi-Fi 和其他开关都没有动。结束时 `adb_enabled` 和 `adb_wifi_enabled` 仍是 1，手电仍是关，状态里仍有 `maxLevel`。终端和投屏都没有把整个助手打退出去；剪贴板共享还在，这次没有另造失败。荣耀相机 `ACTION_IMAGE_CAPTURE` 这次没有做。L4 没有开始。

结束时网关是本地模式，本地与远程都在线，远程已确认，往返分别是 12 毫秒和 86 毫秒。主进程仍是 21407，`:helper` 仍是 21685。没有重打包 Mac，公网中继没有改。安装当时还没提交；源码和这段记录后来在 `ee4efaa` 提交并推送。装上的 APK 仍是提交前的工作区构建，摘要见[发布记录](../releases.md#android-72-共用助手2026-10-17)。当时 L4 还没开始。随后只在测试进程里做了本地握手，见[本地握手](#2026-10-17-本地握手只在测试进程)。

## 2026-10-17 本地握手只在测试进程

Go `lib/link` 和 Java `LocalLink` 在 `127.0.0.1` 的临时端口上完成 hello、auth、ready。签名不对是 `bad_signature`，不在授权列表里是 `unknown_device`，带 Origin 或非回环 Host 的升级被拒绝，断线结束会话。同编号同摘要再提交只执行一次，这条断言在 Java `LocalLinkTest`。Go 测试不调用 `LinkReceipts`。Mac `LocalLinkMac` 只用 `DeviceKey` 签同一段原文，不连手机。`PhoneDeviceKey.localSigner()` 没有被服务调用。`FileMcpService` 的 `/mcp`、`cmd/phone-relay` 和中继 5 没有改。没有安装，没有切公网。`scripts/check.sh` 通过，含 44 个 Java 测试、22 个 Swift 测试和 Mac 全量源码编译；它不连手机。这些文件还在 `ee4efaa` 之上的工作区，还没提交。远程握手见[下一节](#2026-10-17-远程握手只在测试进程)。

## 2026-10-17 远程握手只在测试进程

远程握手已在测试里通过、还没接到现有通道、没有安装、没有切公网。Go `lib/link` 的 `DialRelay` 和 Java `RemoteLink` 对着已有的中继 6 测试服务器，在 `127.0.0.1` 的临时端口上完成 hello、auth、ready。服务端先发 hello，客户端发 auth。原文是 `phone-station link-v2 auth`、stationId、nonce、deviceId、purpose，直接相连。`purpose` 覆盖了 `main` 和 `media`。先验签，再看是否撤销。Go `TestRelay6DialAuth` 里，签名不对是 `bad_signature`，未注册是 `unknown_device`；撤销设备上的错误签名仍是 `bad_signature`，签名正确才是 `revoked`。Java `RemoteLinkTest` 测了 `main`、`media`、`bad_signature` 和 `unknown_device`，没有测 `revoked`。测试证书补了主体名，Java 才能解析这张自签证书。`FileMcpService` 的 `/mcp`、网关、`cmd/phone-relay` 和正在运行的中继 5 没有改。没有启动对着手机或公网的实例。当时跨通道查询和事件替代轮询还没有。补上这次握手后再跑 `scripts/check.sh`，通过，含 45 个 Java 测试、22 个 Swift 测试和 Mac 全量源码编译；它不连手机。这些文件还在 `ee4efaa` 之上的工作区，还没提交。跨通道查询见[下一节](#2026-10-17-跨通道查询只在测试进程)。

## 2026-10-17 跨通道查询只在测试进程

跨通道查询已在测试里通过、还没接到现有通道、没有安装、没有切公网。同一台 Mac 的本地会话和远程会话共享手机上的 `LinkReceipts`。Java `CrossLinkTest` 用已有的 `LocalLink` 和 `RemoteLink`：先在一边 `call`，再在另一边按原编号 `query`，返回原来的回执，不执行第二次。摘要相同的再次提交返回原回执；摘要不同仍是 `conflict`，并且不执行。断掉提交那一边之后，另一边仍从回执库读到。两个方向都走了：本地提交后从远程查，远程提交后从本地查。测的是已经完成的回执。还在运行、要等 `ops` 事件的查询没有做。Go 测试不调用回执。中继 6 测试服务器只在 `127.0.0.1` 的临时端口上转发这两台已注册设备的帧。`FileMcpService` 的 `/mcp`、网关、`cmd/phone-relay` 和正在运行的中继 5 没有改。没有安装，没有切公网。事件替代轮询还没有。补上这次查询后再跑 `scripts/check.sh`，通过，含 46 个 Java 测试、22 个 Swift 测试和 Mac 全量源码编译；它不连手机。这些文件还在 `ee4efaa` 之上的工作区，还没提交。

## 2026-10-17 事件替代轮询在测试进程通过，接入前检查通过

六个主题都在 `LinkEvents`。`device`、`grants`、`capabilities`、`ops` 在 1 秒内合并，`ops` 再按 op 合并。`clipboard` 只留最新一条，`notifications` 保留 20 秒、最多 32 条。游标还接得上就补发，断档或游标为 0 而最旧已超过 1 就 `reset`。没有游标不补历史。未知主题的原因是 `unknown`，坏游标的原因是 `cursor`。订阅替换同一会话，会话结束就卸下。Java `LinkEventTest` 覆盖合并、授权范围、有序补发和重置。Java `CrossLinkTest` 里远程 `call` 还在 running 时，本地订阅收到 `ops`，不先 query；放行后调用完成，再断开远程，本地按原编号仍读到 complete。`LinkRoute` 和 Go `Route` 在两边都可用时让新操作走本地，在途留在原通道，断开后改查另一边，流不迁移。Go 不调用回执。当时 `FileMcpService`、网关、`cmd/phone-relay` 和正在运行的中继 5 还没接这条会话。没有安装，没有切公网。接入现有通道之前跑了 `scripts/check.sh`，通过，含 47 个 Java 测试、22 个 Swift 测试和 Mac 全量源码编译；它不连手机。这些文件还在 `ee4efaa` 之上的工作区，还没提交。

## 2026-10-17 本地链路接到 8765，尚未安装

手机 `McpHttp` 仍只听 `127.0.0.1`。GET `/v2/link` 在读完升级头后交给 `LocalLink.acceptHttp`，不换成 Java-WebSocket 服务端。POST `/mcp` 仍用原来的令牌。`LinkHttpTest` 在临时端口上走完握手，`terminal.open` 的回执在 `LinkReceipts`：同编号同摘要不执行第二次，摘要不同是 `conflict` 且不执行；未知 Mac 是 `unknown_device`；带 Origin 的升级是 403；之后 POST `/mcp` 的 ping 仍是 200。`terminal.*` 和 `install.*` 走特权队列。旧 `/mcp` 的终端创建仍写 `OperationJobs`，切换去掉 `/mcp` 时一起去掉。没有单独的安装任务类型，`install.*` 只在 `LinkReceipts`。手机身份是 `PhoneDeviceKey.identity()`。授权文件空着时不放行。Mac 软件密钥回退是 `DeviceKey.loadOrMake`，网关通过旁边的 `phone-device-key` 签名，这一步不用 Secure Enclave。网关 `serve` 走本地链路，不再把手机临时令牌用于这条通道；网关单测仍注入 HTTP 假手机，`TestProxyLinkUsesDeviceLinkWithoutPhoneToken` 用空的手机令牌走 `terminal.open`。`cmd/phone-relay` 和正在运行的中继 5 没有改。没有安装，没有切公网。这一段的测试是 Java `LinkHttpTest`、`McpHttpTest`、`LinkReceiptTest`、`LocalLinkTest`、`LinkEventTest`、`CrossLinkTest`，Go `lib/link` 与网关的 `-race` 测试，以及 `DeviceKeyTest`。接入之后的全量 `scripts/check.sh` 留到安装前的放行关口，这次还没重跑。这些文件还在 `ee4efaa` 之上的工作区，还没提交。

## 2026-10-17 安装前的本机放行

Java `RemoteLinkTest` 对着只听 `127.0.0.1` 的中继 6 测试服务器补了撤销顺序：错误签名仍是 `bad_signature`，签名正确才是 `revoked`。`ServeLoopback` 拒绝 `0.0.0.0`、`::` 和测试网地址，只在 `127.0.0.1` 上完成一次 `DialRelay` 的 ready。没有面向手机，也没有面向公网。补上 `ServeLoopback` 和 `LinkGrants` 的捕获修改之后再跑 `scripts/check.sh`，通过，含 48 个 Java 测试、22 个 Swift 测试和 Mac 全量源码编译；它不连手机。`LinkGrants` 的多异常捕获改成 `IOException | RuntimeException` 后，`lib/android/build.sh` 写出 `build/adb-keep.apk`。`scripts/build-mac-app.sh` 写出 `build/.phone-station/手机工位.app`。`scripts/build-relay.sh` 写出 Linux/amd64 与本机的 `phone-relay`，两者仍是中继 5，没有启动。正在运行的中继 5 只做了只读核对，服务是 active，NRestarts 为 0。状态目录里 149 个完成回执、1 个 unknown，没有 queued 或 running。没有停机，没有归档，没有部署中继 6。本机没有投屏或录屏进程；已安装的 Mac 应用和网关仍在运行。手机 adb 记录是 offline，无线调试没有发现，没有新建连接，所以没有安装。`DialRelay` 仍只连接回环。`DialRelayPinned` 对字面 IP 做证书名和 SPKI 校验，连上的地址必须就是这个 IP；它只对着 `127.0.0.1` 的 `ServeLoopback` 完成了 ready，没有向公网拨号。`RelayAddress` 和 Java `literalIPv4` 接受字面 IPv4、拒绝域名、`0.0.0.0` 和组播，本身不打开套接字。这些是第二次全量 `scripts/check.sh` 之后补上的，对应测试已通过，全量检查还没有把它们再跑一遍。Go `RelaySession.Bind` 在只听 `127.0.0.1` 的中继 6 上把 `terminal.open` 从一台设备送到另一台并拿到 complete。Java `LinkRemote` 在同一台隔离服务器上把 `clipboard.set` 送到手机侧回执。手机服务只在端点是字面 IPv4、并且授权列表恰好有一台 Mac 时才拨这路；没有授权就不拨。网关的远程调用仍走中继 5 的角色令牌。没有停中继，没有安装。这些文件还在 `ee4efaa` 之上的工作区，还没提交。

## 2026-10-17 网关远程改走链路，配对页在源码，仍未安装

网关源码的 `serve` 把远程调用改成设备密钥拨中继 6 的 `/v2/link`，不再用角色令牌。`TestProxyRemoteUsesRelayLink` 注入调用，不拨公网；域名和坏 pin 在 `DialRelayPinned` 之前拒绝。已安装的网关进程没换。`scripts/status.sh` 仍看到 `192.168.0.108:43333` 为 offline，无线调试没有发现。没有新建 adb，没有把 `adb_wifi_enabled` 写成 0。公网切流未做：主机可以只读核对，但没有停中继 5。停掉它会拆掉正在运行的 Mac 远程通道，而手机不在线，安装无法接着做。`cmd/phone-relay` 仍是中继 5。

配对页接在手机「连接设置」和 Mac 连接设置，无线调试二维码没有改。本地配对走回环 `POST /v2/pair`，签名不对是 400，用户点允许后才写入 `link-grants`，撤销会改写授权文件并关掉这条会话。邀请文本、12 位码和 ZXing 解码在 Java `LinkInviteTest`、Go `TestInviteTextAndManualCode` 和 Swift `StationInviteTest` 通过。应用清单没有 `CAMERA` 权限。`lib/android/build.sh` 写出 `build/adb-keep.apk`，没有安装。拍照解码未在真机做。浅深色、大字号和真机点击没有验收。补上文案键之后 `scripts/check.sh` 通过，含 49 个 Java 测试、23 个 Swift 测试和 Mac 全量源码编译；它不连手机。加密策略 `SealChoice` / `LinkSealPolicy` 是这次检查之后加的：必须加密拒绝明文，本地链路不加密，重放和换钥的单独测试已通过。现有会话还没调用这层。这些文件还在 `ee4efaa` 之上的工作区，还没提交。

## 2026-10-17 远程主会话接上端到端加密，仍未安装

必须加密默认关闭。Mac 只在 `link-phone.json` 的 `seal` 为 `required` 时给远程会话装加密；本地拨号不看这个字段。手机 `link-seal` 不存在就是关闭，读失败则不拨远程。Go `TestRequireSealRefusesLocal` 在本地会话上返回 `local`。`TestSealCodecHidesBodyAndRejectsPlaintext` 覆盖密文不含正文、重放、篡改和明文拒绝。`TestRelay6SealHidesBody` 在只听 `127.0.0.1` 的中继 6 上完成密封握手，`clipboard.set` 的正文没有出现在转发载荷里，对端仍拿到原文。`TestRelay6PlaintextDoesNotRun` 里手机要求加密而 Mac 仍发明文，调用没有执行。Java `LinkSealPolicyTest` 用两台引擎走完同一套握手并拒绝未握手的明文。Java `RemoteLinkTest` 的密封调用对着同一台隔离中继完成。满 24 小时或 2³² 帧时会话以 `rekey` 结束，不降级。媒体链路还没有单独握手。没有安装，没有切公网，没有把这次改动再跑进全量 `scripts/check.sh`。这些文件还在 `ee4efaa` 之上的工作区，还没提交。

## 2026-10-17 stationd 只在测试进程里

`lib/stationd` 对着假 adb server 做了设备列表、端口转发和 shell v2。它拒绝 `127.0.0.1` 以外的地址。mDNS 解析不浏览网络。本机套接字在同一用户、同一可执行文件时放行，其他用户这次没有第二账户可测。不同路径的签名比较有纯函数测试；这台 Mac 没有用 Developer ID 签过 `stationd`。命令入口直接退出，没有登记 LaunchAgent，没有替换正在运行的网关，没有改 `scripts/*.sh`。`scripts/check.sh` 已把这个模块列进 Go race，这次没有重跑全量检查。

## 2026-10-17 station 命令能列设备，仍未登记登录启动

`station devices`、`forward` 和 `shell` 只拨 `127.0.0.1` 上已有的 adb server，不启动 adb，也不连接手机。`station serve -socket` 在本机 Unix 套接字上提供同样的三个命令；对端 UID 或签名不对时返回错误，不把命令送到 adb。`TestServeFakeAdbAndPlist` 用假 adb server 走完这三步，并检查 LaunchAgent 文本含 `RunAtLoad` 和 `KeepAlive`，不含 `0.0.0.0` 和 `18765`。`station plist` 只打印这段文本。没有调用 `launchctl`，也没有调用 SMAppService，所以登录启动、崩溃自启和升级替换没有在这台 Mac 上登记。正在运行的网关没有替换。`scripts/status.sh` 在 `build/.phone-station/station` 或 App 包里的 `lib/station` 可执行时用它列设备，失败再退回 `adb devices -l`。无线调试仍用 `lib/adb_mdns.py`。本地投屏和录屏仍走 scrcpy。没有安装，没有切公网。全量 `scripts/check.sh` 还没有把这一段再跑一遍。

## 2026-10-17 全量检查通过，随后无线调试改由 station 浏览

在加上 `station wireless` 之前跑了 `scripts/check.sh`，通过。Java 测试 50 个，Swift 测试 23 个，Mac 全量源码编译通过。它不连手机。钥匙串相关的弃用警告还在，没有当成失败。

这之后 `station wireless` 才改用本机 `dns-sd` 浏览 `_adb-tls-connect._tcp`。解析用录好的行测过：带空格的 `adb-phone (2)`、`can be reached at`、避开 `172.19.0.1`。`TestBrowseConnectReturns` 只确认 `dns-sd` 能在 300 毫秒内返回，不要求发现手机。`scripts/status.sh` 优先调用这个命令；退出码 3（`dns-sd` 没起来）才退回 `lib/adb_mdns.py`。当时没有在线设备，也没有发现无线调试。没有新建 adb，没有安装，没有切公网。这次改动还没有再跑全量 `scripts/check.sh`。

## 2026-10-17 Mac 包已构建，没有安装

`scripts/build-mac-app.sh` 生成 `build/.phone-station/手机工位.app`。包里有 `lib/station`。版本号仍是 Info.plist 里的 0.1.0 (37)。签名是 adhoc，TeamIdentifier 是 not set，没有公证。`/Applications/手机工位.app` 仍是 02:15 的已安装副本，版本 37。`dev.phonestation.stationd` 没有登记到当前用户的 launchd。没有替换正在运行的网关，没有切公网。包生成之后才加上 `ReadScrcpy`：它把 scrcpy 4.1 的一路套接字收成原生解码器已经接受的包，不启动 scrcpy，也不连接手机。这个函数不在刚生成的包里。本地投屏和录屏仍走 scrcpy。

## 2026-10-17 本机画面帧可以送进原生解码器，菜单栏仍退回 scrcpy

`ServeNative` 只听 `127.0.0.1`，把一路 scrcpy 视频收成带长度的原生包。`TestReadScrcpyVideoAndDisabledAudio` 读到 codec 包 `\x00h264`。`ScreenFrameBuffer` 能把拆开的字节拼回一帧。`RemoteScreenSession.startLocal` 只连接回环端口并调用原来的 `receive`。`station screen` 在标准输入是终端或空设备时退出 2，不拨 adb。`NativeScreen.port` 因此得到空端口，菜单栏投屏仍启动 scrcpy。没有连接手机，没有安装。这次没有重跑全量 `scripts/check.sh`，也没有把新的 Swift 文件再打进已生成的 App 包。

## 2026-10-17 SMAppService 登记了 stationd，系统拒绝启动

构建包 `build/.phone-station/手机工位.app` 里有 `Contents/Library/LaunchAgents/dev.phonestation.stationd.plist`。`BundleProgram` 是包内的 `station`，参数是 `serve`，没有 TCP 端口。用这个包执行 `--register-station`，`SMAppService.agent` 返回 enabled。`launchctl print` 里的退出原因是 `OS_REASON_CODESIGNING`，cdhash 列表是空的。包的签名仍是 adhoc，TeamIdentifier 是 not set。随后执行 `--unregister-station`，`gui/501` 里已经没有 `dev.phonestation.stationd`。没有做崩溃自启，也没有做升级替换。没有替换 `/Applications/手机工位.app`，没有碰正在运行的网关。

## 2026-10-17 station disconnect 只在假 adb 上测过

`Disconnect` 对空序列号发送 `host:disconnect:`，对 `192.168.0.8:5555` 发送 `host:disconnect:192.168.0.8:5555`。非法序列号和 `127.0.0.1` 以外的地址在拨号前拒绝。`scripts/disconnect.sh` 找到构建产物或 App 包里的 `station` 就交给它，否则仍调用 `adb disconnect`。没有对正在运行的 adb server 执行断开。`connect.sh` 没有改。

## 2026-10-17 MCP 列表改成 18 个名称，还没安装

`tools/list` 现在只返回 `device_list`、`device_status`、`files_list`、`files_stat`、`files_read`、`files_write`、`files_edit`、`files_manage`、`files_grep`、`files_transfer`、`app_install`、`screenshot`、`shell_run`、`clipboard_read`、`clipboard_write`、`phone_alert`、`device_control`、`task_status`。应答带 `resultType`、`ttlMs` 300000 和 `cacheScope` private。`server/discover` 列出 `2025-03-26` 和 `2026-07-28`。剪贴板交换、通知接收、投屏会话和终端不在列表里。`tools/call` 仍接受原来的 `station_` 名称，新名称会转到原来的处理函数。`McpProtocolTest`、`RemotePolicyTest`、`McpLoopbackTest` 和网关的 `TestClient` 已通过。没有授权时这次列表应答是 5306 字节。没有连接 Cursor、Claude Code 或 Codex，没有安装，没有改正在运行的 `/mcp`。

## 2026-10-17 旧工具名不再执行

`tools/call` 对已经并进 18 个新名称的 `station_` 名字返回 `unknown_tool`。`device_status` 同时带上原来的设备状态和控件状态。`McpProtocolTest`、`RemotePolicyTest`、`FileOpsTest`、`McpLoopbackTest`、`lib/test_remote_ops.py`、`lib/test_remote_terminal.py` 已通过。`CommandSessionTest`、`DeviceHealthSessionTest`、`ConnectionStatusTest` 已通过。剪贴板交换、通知接收、投屏会话和终端仍可用旧名字调用，不在 `tools/list` 里。仓库里的远程脚本先看这次 `tools/list`：正在运行的手机没有新名称时仍发旧名称。没有安装，没有连接三个客户端。

## 2026-10-17 机型判断收成 verified

`CommandSession`、`RemoteControlsReading` 和 `remote_terminal.py` 只看 `verified`。`remote_ops.py` 在工具列表里有 `station_controls_status` 时也只看它；没有这个工具的旧手机仍用 `getprop` 对照 PGT-AN20。手机上 `VerifiedDevice` 是唯一把型号写成已验证的地方，现在只包含 PGT-AN20。`lib/test_remote_terminal.py` 里型号是 other 且 `verified` 为真时可以继续，`verified` 为假时拒绝。没有第二台设备，2 台 Mac × 2 台手机没有测。菜单栏本地连接在只有 `getprop` 时仍把其他型号标成未验证。

## 2026-10-17 两台 Mac 的剪贴板不会把同一段文字写回去

`ClipboardState` 按 `clientId` 记住各自的 `macVersion`。另一台第一次联系只建立基线。它把手机上已有的同一段文字再送回来时 `appliedMac` 为假，不调用写入。它随后用新版本送出不同文字时会写入。原来那台再送回这段文字时也不写入。`ClipboardStateTest` 通过。没有第二台 Mac，也没有在手机上交换剪贴板。

## 2026-10-17 两台 Mac 的通知各自接收

`NotificationSyncState` 为每个 `clientId` 保留自己的最近接收时间。后一台第一次 `poll` 得到 `baseline`，事件列表是空的。前一台用自己的 cursor 仍能读到这段还没取走的通知。这段通知不会补发给后一台。之后新来的通知两台用各自的 cursor 都能读到。最多记住 8 台 Mac，更早且排在前面的会被忘掉。`NotificationSyncStateTest` 和 `EventLeaseTest` 通过。没有第二台 Mac，也没有在手机上投递通知。

## 2026-10-17 菜单栏可以切换列出的手机

`PhoneRoster` 把同一身份的 USB 和无线序列号合成一项，USB 优先。不同身份留在列表里。没有选择时用列表里的第一项；右键选中的序列号会记住，无线别名仍选中那一台。选中的手机不在列表里时回到第一项。`PhoneRosterTest` 通过。Mac 源码可以编译。两台不同手机在线时不再把连接标成失败，也不再为此运行 `connect.sh`。同一台手机的重复连接仍会整理。没有第二台手机，菜单没有在真机上点过。正在使用的 Mac App 没有换。

## 2026-10-17 多台手机时脚本使用菜单栏的选择

`adb_mdns.py` 的 `choose_group` 在只有一组连接时仍返回那一组。两组以上时，菜单栏记住的序列号或环境变量 `PHONE_STATION_SERIAL` 能选中对应的那一组，包括它的无线别名；选不中就返回空。`serial` 子命令因此不再一律拒绝多台手机。没有选择时仍停止，并提示先在菜单栏选择。`lib/adb_mdns.py self-check` 通过。没有连接 adb，没有第二台手机。

## 2026-10-17 诊断文本脱敏与签名身份

`lib/redact.py` 会把 `Authorization: Bearer` 后面的值、JSON 里的 token、pin、enroll、password、secret、authorization、spki，以及单独出现的 64 位十六进制换成 `[redacted]`。短的序列号保留。`lib/test_redact.py` 通过。还没有诊断包，也没有日志轮转。`security find-identity -p codesigning` 显示 0 个有效身份，Developer ID、公证、自动更新和 Secure Enclave 替换没有做。

## 2026-10-17 网关日志在下次启动时轮转

`rotateGatewayLog` 在新网关进程打开日志之前检查 `remote-gateway.log`。不到 1 MiB 保持原文件。达到 1 MiB 时改名为 `remote-gateway.log.1`，再有一份满日志就替换这一份，更早的不保留。`TestRotateGatewayLog` 用临时目录通过。没有重启正在运行的网关，也没有读它的日志。

## 2026-10-17 脱敏诊断目录

`write_bundle` 只接受当前目录下的 `.txt` 名字，写出前先脱敏，文件权限是 0600。非空目录会拒绝写入。`gateway_excerpt` 只取日志末尾。`lib/test_diagnose.py` 通过。`scripts/diagnose.sh` 在临时家目录里只写出说明，没有读正在运行的网关日志，也没有连接手机。

## 2026-10-17 设置改到独立窗口

菜单栏弹出层现在只显示首页上的快捷操作。离开首页的页面，包括连接、MCP、中继、权限、通知和剪贴板，打开标题为「设置」的窗口，并关掉弹出层。关掉窗口回到首页。需要配对二维码时，设置窗口算作界面可见，会继续出示二维码。页面订阅表 14 页通过，Mac 源码可以编译。浅色深色、大字号和真机点击还没做。正在使用的 Mac App 没有换。

## 2026-10-17 分享与拖放的路径和分帧

`DropPhonePath` 只接受单个文件名，目标是 `/storage/emulated/0/Download/`。`ShareMacName` 只从内部存储的普通文件取出文件名，`Android` 及其下面的路径拒绝。拖到手机时续传偏移是 0；分享到 Mac 时可以用已经收到的偏移，但不能超过文件长度。`DataFrames` 把内容切成 `KindData`，中间帧是 More，最后一帧是 End，拼回去与原内容相同。`TestSharePaths` 和 `TestShareResumeAndFrames` 通过。`TestShareOverLink` 把 `note.txt` 的内容切成帧，经测试里的链路送到对端，拼回来与原文相同。Java `LinkEngineTest` 也把同一段内容用 `KindData` 送到对端。没有向真机传文件。

## 2026-10-17 保持亮屏脚本改走 station

仓库里能找到 `station` 时，`stay-awake.sh` 用 `station shell` 发送原来的亮屏广播。找不到时仍用 `adb shell`。`lib/test_stay_awake.py` 通过：没有 `station` 时仍是一条 adb 广播；放进假的 `station` 后改走 `shell`，不再调用 `adb -s shell`。没有对手机执行这个脚本。

## 2026-10-17 安装前的全量本机检查

`scripts/check.sh` 通过。Java 测试 51 个，Swift 测试 25 个，Mac 全量源码编译通过。钥匙串接口的弃用警告没有让编译失败。这次检查不连接手机，也不启动正在运行的网关。安装仍没做：adb 没有在线设备，正在运行的中继仍是 5，没有停它。这次检查在 `vibrate.sh` 改走 `station shell` 之前。

## 2026-10-17 拖放只记下目标路径

Mac 的 `FileShare` 和手机的 `SharePaths` 使用与链路相同的路径规则。菜单栏首页接受拖入的文件 URL，只取文件名，提示将保存到 `Download/`，并写明文件还没有送上链路。`FileShareTest` 和 `SharePathsTest` 通过，Mac 源码可以编译。没有读文件内容，没有注册手机系统分享，没有向真机传文件。浅色深色和真机拖放还没做。

## 2026-10-17 网关订阅链路事件

Go 的本地会话可以 `Subscribe`，并在同一次 `Call` 还没返回时读到 `ops`。`TestSubscribeReceivesOpWhileCallRuns` 通过。手机把剪贴板和通知变化广播给每台已订阅的 Mac；卸下后不再发送。`LinkEventTest` 通过。网关连上本地链路后订阅六个主题，`event.wait` 在网关本地等待这些事件，测试里确认没有再转发到手机。没有安装，没有切公网。

## 2026-10-17 状态主题接到现有信号

`device` 只带电量、充电、网络、亮屏、USB 调试和无线调试。正文与上次相同就不再入队。`grants` 只发给被允许或撤销的那台 Mac。`capabilities` 带 Shizuku、已验证和功能开关，两台订阅的 Mac 都能收到。`LinkEventTest` 通过。读状态、保持亮屏、功能开关和无线调试保持的心跳会推这些状态。没有安装。

## 2026-10-17 链路终端不再写第二份回执

`terminal.open` 从链路进来时直接打开会话，不再调用 `OperationJobs.start`。读、输入、改尺寸和关闭也只认这次进程里打开的会话；进程重来后读到 `lost`，不会重新创建。`TerminalJobsTest` 通过。旧 `/mcp` 的终端仍写 `OperationJobs`。安装没有 `OperationJobs` 任务种类。没有安装到手机。

## 2026-10-17 震动脚本改走 station

仓库里能找到 `station` 时，`vibrate.sh` 用 `station shell` 发送原来的 `vibrator_manager` 命令。找不到时仍用 `adb shell`。`lib/test_vibrate.py` 通过：没有 `station` 时仍是 adb；放进假的 `station` 后改走 `shell`，不再调用 `adb -s shell`。没有对手机执行这个脚本。

## 2026-10-17 链路 shell、投屏和安装只记一份回执

`shell.start`、`screen.open` 和 `install.run` 从链路进来时由 `linkWork` 执行，结果写入 `LinkReceipts`。`LinkHttpTest` 确认这三次调用不会进入 `shellStart`、`screenOpen` 或 `captureStart`。安装路径必须是 `/storage/emulated/0/` 下的 apk，`..` 被拒绝。旧 `/mcp` 仍写 `OperationJobs`。`lib/android/build.sh` 通过并写出 `build/adb-keep.apk`。没有安装，没有对手机执行安装。中继 5 未动。

## 2026-10-17 安装前的全量检查再次通过

`scripts/check.sh` 通过。Java 测试 51 个，Swift 测试 25 个，六个 Go 模块和 Mac 全量源码编译通过。这次包含链路 `shell.*`、`screen.*`、`install.run` 只记 `LinkReceipts`，以及网关订阅和状态主题。钥匙串接口的弃用警告没有让编译失败。检查不连接手机，也不启动正在运行的网关。安装仍没做：没有在线 adb 设备，正在运行的中继仍是 5，没有停它。

## 2026-10-17 媒体用途使用同一套密封

中继 6 的 `media` 与 `main` 一样：必须加密时明文调用不执行，密封后的正文不出现在转发帧里。`TestRelay6SealHidesBody` 和 `TestRelay6PlaintextDoesNotRun` 对两种用途都通过。网关和手机服务仍只打开 `main`。没有安装，没有切公网。

## 2026-10-17 拖放文件经数据帧写到 Download

链路消息 `share` 带文件名、长度和奇数通道，随后的 `KindData` 是文件内容。手机用 `FileOps.shareDrop` 写到 `Download/`，整份重传，并会创建还没有的 `Download`。超过 128 KiB 拒绝。`LinkHttpTest` 确认 `note.txt` 的字节落盘，`../` 在发出前拒绝。Go `TestPutFile` 用同一份头把同样的字节写进临时目录。网关 `POST /v2/share` 只接受本机回环和网关令牌，测试用注入的写入，不拨手机。Mac 首页拖入后读取文件并调用这个地址；网关不可用时仍提示还没有送出。没有向真机传文件。

## 2026-10-17 手机分享经偶数通道写到 Mac

手机作为会话的非 Mac 一侧，用偶数通道（从 8 起）发同一份 `share` 头和 `KindData`。Mac 收到后写到 `~/Downloads/手机工位`，目录权限 `0700`，文件权限 `0600`，再回 `shared`。没有这条会话时手机不拨号。超过 128 KiB 在读流时拒绝。`ShareActivity` 注册 `ACTION_SEND`，不申请相机或存储权限。`TestOfferFileToMac` 和 `LinkHttpTest` 的回送确认 `back.txt` 的字节。`lib/android/build.sh` 通过并写出 `build/adb-keep.apk`。没有安装，没有向真机传文件，没有停中继 5。

## 2026-10-17 安装前全量检查覆盖分享

`scripts/check.sh` 通过。Java 测试 51 个，Swift 测试 25 个，六个 Go 模块和 Mac 全量源码编译通过。这次包含两个方向的文件分享，以及更早的链路回执和密封。钥匙串接口的弃用警告没有让编译失败。检查不连接手机。随后 `scripts/status.sh` 看到无线调试广播，adb 设备列表是空的，没有新建连接。

## 2026-10-17 发布命令源码改为中继 6

`cmd/phone-relay` 改为提供 `GET /v2/link`。`--version` 打印 `phone-station-relay 6`。`enroll` 只把加入码写到标准输出，注册表里是摘要。状态目录里已有别的文件时拒绝新建注册表。`PHONE_RELAY_TRUSTED_PROXIES` 写错时不打开状态目录。`server/relay` 的 `go test -race ./...` 通过，含 `cmd/phone-relay`、`internal/relay` 和 `internal/v6`。测试监听只在 `127.0.0.1`。没有运行构建产物，没有部署，正在运行的中继 5 没有停。这次改动在上面那次全量检查之后，安装前还要再跑一次 `scripts/check.sh`。

## 2026-10-17 发布命令的联合测试改看 /v2/link

`scripts/build-relay.sh` 打出 Linux/amd64 的 `phone-relay`，没有启动。接着跑 `scripts/check.sh` 时，`test_control_tls.py` 仍去打 `/v1/desktop/status`，构建出的命令只提供 `/v2/link`，返回 404，检查失败。测试改为：版本是 `phone-station-relay 6`，`enroll` 的加入码不进注册表和标准错误，注册表只留摘要；进程只听 `127.0.0.1`，`/v2/link` 要求升级，旧的 `/v1/desktop/status` 和 `/mcp` 是 404。这个测试单独通过。随后 `scripts/check.sh` 通过：Java 51 个，Swift 25 个，六个 Go 模块和 Mac 全量源码编译。公网中继仍是 active，NRestarts 为 0。状态目录里 10 个 complete，没有 queued 或 running。adb 上已有一台 PGT-AN20，这次没有安装，也没有停中继。

## 2026-10-17 切公网前的版本、影响、验证和回滚

还没停机。要换上的程序是工作区里的中继 6，源码提交 `ee4efaa`，工作区是脏的。Linux/amd64 产物 `build/.phone-station/relay/linux-amd64/phone-relay` 的 SHA-256 是 `5d2685e00fd710aae5da6c6d74b5cff0ba6284462967f947e13823a3689b8849`，`--version` 在本机测试里是 `phone-station-relay 6`。去掉 `/mcp` 之后的手机包 `build/adb-keep.apk` SHA-256 是 `986c6f936c9bb53f9aac41bfe4092dec7a58c4d0761c99536ec40a392a9e7dab`，版本名仍是 72。Mac 仍是 0.1.0 (37)。`build/.phone-station/手机工位.app` 已换入这份 APK 并重新临时签名，没有替换 `/Applications` 里正在运行的应用。当时手机包仍同时提供 `/mcp` 和 `/v2/link`，所以没有停中继。

## 2026-10-17 手机不再提供 /mcp

`McpHttp` 对 `/mcp` 返回 404，不执行工具。`/v2/link` 和 `/v2/pair` 仍在。`McpHttpTest`、`LinkHttpTest` 和 `McpLoopbackTest` 通过；协议行为改由 `McpProtocol.handle` 直接调用。`lib/android/build.sh` 写出新的 `build/adb-keep.apk`。`LinkInviteTest` 原期望 `/mcp` 没有令牌时返回 401，现改为 404，单独通过。没有安装，没有停中继。Mac 包里的 APK 已换成这份。`scripts/check.sh` 已通过。清单里的 `sourceVersions.relay` 改为 6。

影响：停掉中继 5 后，正在运行的 Mac 远程通道断开。已安装的 Android 72 不会说中继 6。本机 adb 仍可用来装新手机端。主机上的其他服务不动。中继 6 用新目录 `/var/lib/phone-station-relay-v6`，旧目录只复制归档，不删除。目录里已有文件时，中继 6 拒绝启动。

验证：新进程 `--version` 是 6，服务 active，旧目录还在，新目录只有注册表。然后保留数据安装手机端，换上新 Mac，本地配对后再断开 adb，核对远程链路、操作查询和事件。

回滚：在安装新手机端之前，换回归档旁留下的中继 5 程序，状态目录仍用原来的那份。安装新手机端之后不能靠卸载降级。加入码不写入这份记录。

## 2026-10-17 中继 6 已切换，手机本地链路已通

停机前状态目录 20 个文件：10 个 complete，没有 queued 或 running。服务 NRestarts 为 0。本机没有投屏或录屏进程。已有一条 PGT-AN20 adb，没有新建第二条。

停止中继 5 后，把状态目录、旧程序和 unit 复制到 `/opt/phone-station-relay/backups/pre-v6-20261017-223122/`，原目录没有删除。新程序 SHA-256 仍是 `5d2685e00fd710aae5da6c6d74b5cff0ba6284462967f947e13823a3689b8849`。`PHONE_RELAY_STATE_DIR` 指向新目录。加入码在服务启动前写入主机上的 root-only 文件，没有进入仓库、日志或聊天。服务再启动后 `--version` 是 `phone-station-relay 6`，active，NRestarts 为 0，旧目录仍有 25 个文件，新目录当时只有 `registry.json` 和 `registry.lock`。

`adb install -r` 保留数据，设备上的包摘要先是 `986c6f936c9bb53f9aac41bfe4092dec7a58c4d0761c99536ec40a392a9e7dab`。进程在 `Files.readString(Path)` 上崩溃，荣耀 Android 15 的运行库没有这个单参数方法。生产代码改成 `readAllBytes` / `Files.write`。`lib/android/build.sh` 通过。重新安装的包摘要是 `b1ebf64ab9db0d42c821f295ef4c567ae3152faca5de79823305d657b7811c42`，版本名仍是 72，进程保持运行，8765 在听。`/Applications/手机工位.app` 已换成这次的包并重新临时签名，版本仍是 0.1.0 (37)。旧应用留在本机备份目录。

Mac 经 `POST /v2/pair` 提交签名公钥，手机上点了允许，再次提交时 `granted` 为 true。网关 `call` 的 ping 返回空 result。没有断开 adb。

`DialRelayEnroll` 在回环测试里用一次性加入码注册，用过的码返回 `rejected`，之后 auth 能 ready。`TestRelay6DialEnroll` 通过。这台 Mac 已用同一调用注册到公网注册表：1 台设备，加入码条目已清掉。手机还没有 join 客户端，远程 ping 没有完成。进程强杀后再查原编号还没有做。拍照解码未在真机做。没有提交，没有推送。

## 2026-10-17 手机已 join 中继 6

上一节的包 `b1ebf64ab9db0d42c821f295ef4c567ae3152faca5de79823305d657b7811c42` 没有 join 客户端。这次 `adb install -r` 保留数据，版本名仍是 72，包摘要换成 `337282cd62aa8046e658aac6515442f35a382d962bc8bd11136817720eb4e659`，与当时的 `build/adb-keep.apk` 一致。Mac 应用没有换包，仍是 0.1.0 (37)、临时签名。没有再注册登录项，没有公证，没有换 Secure Enclave 密钥。

手机按已配对 Mac 的邀请 join。邀请正文没有写入仓库、日志或聊天。批准后手机记住中继端点，没有打开旧的令牌客户端。之后远程调用走 auth。

核对远程链路时先停住菜单栏，再断开 adb，避免它把同一条无线调试又连上。`adb devices` 为空时，网关 remote-call 的 ping 返回空 result。`479cf9bc8ec98eb7126ff9c77f88b1d5` 是已经完成的 `probe.mark`，查询为 complete。同一次订阅收到 3 个主题，事件是 device 快照。`ops` 没有重放。

授权强杀一次：`cb6477dd705bd862043aa429bf6cfb47` 是还在跑的 `shell.start`（`sleep 15`）。进程被停掉时调用以链路结束返回。重新拉起应用并再次断开 adb 后，这个编号是 unknown，上面那个已完成编号仍是 complete。unknown 只说明回执不再是 running，不能证明命令没跑过。换上新的 Mac 中继会话后，第一次查询可能停在 subscribe，等手机重拨后再查才读到回执。

Mac 拖到手机的 4096 字节文件落在 `Download/ps-drop.bin`，SHA-256 是 `f600eca824e84a43f0691b267bd620e462c50da165c5b80e17aecb7a924f1fa8`，与发出的内容一致。手机分享到 Mac 没有文件到达。两份测试文件随后删掉。必须加密没有在这台已安装手机上打开：Mac 的 seal 是空的，手机私有的 `link-seal` 写不进去，没有改成明文降级。没有第二台设备，2 台 Mac × 2 台手机没有测。拍照解码没有做。

正在服务的网关仍是应用包里的旧程序。它经链路要 `meta.tools`，手机返回 51 个 `station_` 工具，整份 `tools/list` 响应 24909 字节。源码里另一份目录是不带前缀的名称，这次客户端没有拿到那一份。Cursor、Claude Code、Codex 都没有配置这台网关。本机没有 tokenizer，没有 token 数。

核对结束后，菜单栏恢复，网关仍由应用包里的 `scripts/mcp.sh` 拉起，本地和远程都在线，remote-call ping 有结果。没有提交，没有推送。

## 2026-10-17 源码网关已接上，手机分享到 Mac 已到达

上一节里「网关仍是应用包里的旧程序」和「手机分享到 Mac 没有文件到达」是这次替换之前的状态。

18765 上的进程换成仓库构建的 `build/.phone-station/phone-relay-gateway`。应用包 `/Applications/手机工位.app` 没有换，仍是 0.1.0 (37)、临时签名。主令牌 `tools/list` 的 HTTP 正文是 5464 字节，名称依次是 `device_list`、`device_status`、`files_list`、`files_stat`、`files_read`、`files_write`、`files_edit`、`files_manage`、`files_grep`、`files_transfer`、`app_install`、`screenshot`、`shell_run`、`clipboard_read`、`clipboard_write`、`phone_alert`、`device_control`、`task_status`，带 `resultType` complete、`ttlMs` 300000、`cacheScope` private。本机 Python 没有 `tiktoken`、`tokenizers` 或 `transformers`，没有 token 数。`station_controls_status` 仍能转发，返回的机型是 PGT-AN20，`verified` 为 true。

手机保留数据安装。版本名 72，`versionCode` 72。设备包摘要 `9ad9f9e2b6cd75ad05e236562ae219101622677ef4ebf9f17195b6e05a445792`，5628296 字节，与当时的 `build/adb-keep.apk` 一致。安装程序拉起过一次主界面。没有重做 join。安装后 remote-call ping 返回空 result。`device_status` 和 `files_stat` 有结果。

手机分享用 `content://dev.phonestation.adbkeep.file/storage/emulated/0/Download/ps-to-mac.bin` 打开本应用的分享页。11 字节落到 `~/Downloads/手机工位/ps-to-mac.bin`，SHA-256 `1dfa0d8df8f9a354ff32b3154c90c596fa27436d51778c1fda1126b06944e703`，与手机上的文件一致。两边随后删掉。没有人在手机前，没有点系统分享面板，也没有拍照解码。必须加密没有打开：Mac 的 seal 仍是空的，没有改成明文。没有第二台设备。没有公证，没有换 Secure Enclave 密钥。没有再注册登录项。

Cursor、Claude Code、Codex 各有一个 `status,files.read` 客户端。令牌只在各自的本机配置里。这三个令牌的 `tools/list` 是 6 个名称。Cursor 在默认审批下拒绝了 `device_status`，加上 `--force` 后报告机型 PGT-AN20、版本 72、电量 4%、已验证。Codex 在只读沙箱里报告同一机型、版本 72、电量 5%、已验证。Claude Code 的 MCP 健康检查已连通，`claude auth status` 是未登录，任务没跑。菜单栏若在 18765 空闲后重新拉起网关，会回到应用包里的旧程序。没有提交，没有推送。

## 2026-10-17 菜单栏应用包换成这次构建

上一节结束时，18765 上的进程是仓库里的 `build/.phone-station/phone-relay-gateway`，`/Applications/手机工位.app` 仍是旧包。那份包里的网关摘要是 `5ebc8e2f565875ec22f9ce3831a78b439c2a51abb14841e8c6e249d4e9ad44f1`。当时构建目录里的应用包也还装着同一份旧网关，所以先跑了 `scripts/build-mac-app.sh`。脚本里的 Go、Python、Swift 和 Android 测试通过。没有另跑 `scripts/check.sh`。

旧包先退出菜单栏，18765 上的进程留着。`scripts/archive-mac-app.sh` 校验后写下 `~/Library/Application Support/Phone Station/Backups/手机工位-20261017-124247-9a17f9b0.zip`，16901626 字节，然后才删掉 `/Applications` 里的旧目录。新包用 `ditto` 放进去。`codesign --verify --deep --strict` 通过，签名仍是 adhoc，TeamIdentifier 是 not set。版本名仍是 0.1.0 (37)。`PhoneStation` 摘要 `9defeb3bf2a97dda330c1385db2489744609fab286655f1401f35e0e72867ffe`。包内网关摘要 `a0d75d57fd3322d4f484c536206ac08b23a44ee7af9c54e6d060639e310cc495`，11890162 字节，与 `build/.phone-station/phone-relay-gateway` 相同。没有再登记登录项。

换包后，18765 仍由换包前的那个进程听着。主令牌 `tools/list` 的 HTTP 正文是 5459 字节，18 个名称和上一节相同，`resultType` complete、`ttlMs` 300000、`cacheScope` private。打开新的菜单栏后，它的子进程已经是新包里的网关程序。接着只向旧的监听进程发了 SIGTERM，没有跑 `mcp.sh stop`，也没有动手机。端口空闲约 6 秒，菜单栏没有再起监听进程。然后执行新包里的 `phone-relay-gateway start`。新的监听进程路径是 `/Applications/手机工位.app/Contents/Resources/phone-station/lib/phone-relay-gateway`，映射的 inode 与这个文件相同。`tools/list` 仍是这 18 个名称、5459 字节、同样的 `resultType`、`ttlMs` 和 `cacheScope`。同一个菜单栏进程随后又拉起子进程，路径仍是这份程序。

包里的 `phone-app.apk` 摘要是 `80cfb9b92905d5799f72dd4e148d03c474060a23a5114846772284b7b945656c`，与手机上的 `9ad9f9e2b6cd75ad05e236562ae219101622677ef4ebf9f17195b6e05a445792` 不同。没有安装手机，没有重做 join、强杀、拖放和那次 11 字节分享。`claude auth status` 仍是未登录，任务没跑。本机没有 tokenizer，没有 token 数。必须加密没有打开。没有人在手机前，拍照解码没做。没有第二台设备。没有公证，没有换 Secure Enclave 密钥。已有的登录项和 `dev.phonestation.stationd` 记录仍是停用，`launchctl` 里没有这个服务。没有提交，没有推送。HEAD 仍是 `ee4efaa`。

## 2026-10-17 随包 APK 已保留数据装上

上一节结束时，手机上的包仍是 `9ad9f9e2b6cd75ad05e236562ae219101622677ef4ebf9f17195b6e05a445792`。Mac 包没有再换，登录项没有再登记。

`install-apk.sh` 先确认远程通道可用。安装任务 `4df181740b5246f3b0a519004f2089c2` 在上传前停下：shell 任务 `45f7fb016b304a6abea74151228c9a7c` 的回执是 missing。随后的只读核对 `4b7236a107bf482dbbda3a9d0f97d9ad` 也是 missing。这两条都没有重做。查询原安装编号时，任务目录和收件箱都不存在，没有进入包管理器。`link-query` 在订阅阶段就结束，没有读到回执正文。

当时 adb 列表是空的，mDNS 只有一台 `192.168.0.108:42177`。`connect.sh` 连上后机型是 PGT-AN20，版本名 72，`versionCode` 72，包摘要仍是上面的旧值，5628296 字节。`adb install -r` 安装 `/Applications/手机工位.app` 里的 `phone-app.apk`，输出 Success。装完设备包摘要是 `80cfb9b92905d5799f72dd4e148d03c474060a23a5114846772284b7b945656c`，5628296 字节，与这份 APK 一致。版本名和 `versionCode` 仍是 72。主进程 18854。`FileMcpService` 和 `KeeperService` 都在前台。

没有重做 join。网关 `remote-call` 的 ping 返回空 result。`device_status` 返回机型 PGT-AN20、版本 72、`verified` 为 true。网关 `localOnline` 和 `remoteOnline` 都是 true。手机状态里的 `remoteConnected` 仍是 false，`remoteState` 是「连接失败 · 重试中」；这是旧的令牌客户端，链路 ping 已经有结果。

没有重做强杀、拖放和那次 11 字节分享。`claude auth status` 仍是 `loggedIn: false`，任务没跑。本机没有 tokenizer，没有 token 数。必须加密没有打开。没有人在手机前，拍照解码没做。没有第二台设备。没有公证，没有换 Secure Enclave 密钥。没有提交，没有推送。HEAD 仍是 `ee4efaa`。

## 2026-10-18 远程状态改跟链路

上一节结束时，手机 `remoteConnected` 是 false，`remoteState` 是「连接失败 · 重试中」。那是旧令牌客户端。链路 ping 已经有结果。

链路主会话已经启动时，首页、连接设置、常驻通知和状态里的远程在线改跟这条会话：会话 ready 且未结束是已连接，否则是连接中。没有启动链路主会话时，仍读旧令牌客户端。`lib/android/build.sh` 通过，包含 `RemoteChannelTest`，写出 `build/adb-keep.apk`。

远程安装任务 `8dae623d3c7143fab964c4afa162b4e3` 在上传前停下。shell 任务 `5f4a5048c2254dd6b410356638779f1a` 查询是 missing。安装状态查询没有读到任务记录，输出里没有上传。这两条都没有重做，也没有进入包管理器。

当时 adb 列表是空的。mDNS 只有 `192.168.0.106:40623`，路由走 en0。ping 通，新进程能对这个端口完成 TCP。`adb connect` 仍是 `No route to host`。本机 adb 服务 pid 34937 从 2026-10-16 13:48 起一直在，环境是 `ADB_MDNS=0` 和 `ADB_MDNS_AUTO_CONNECT=0`，自己不会发现手机。`No route to host` 不会被当成未配对，自动重连会继续，但每次都交给这个拨不出去的旧服务。

停掉这个没有手机连接的 adb 服务，用同样两个变量拉起新服务，再只对 `192.168.0.106:40623` 执行一次 `connect.sh`。列表只有这一条 `device`，机型 PGT-AN20。没有建第二条。`adb_wifi_enabled` 仍是 1。

`android.sh --adb` 保留数据安装。安装入口停过一次应用再打开主页，没有另做强杀回执测试。设备包摘要 `b0c76783650ce7efd850c26a4439cd5ce02f77c924c4b6315c72b0bc094fc93c`，5632392 字节，与 `build/adb-keep.apk` 一致。版本名和 `versionCode` 仍是 72。

`device_status` 返回机型 PGT-AN20、版本 72、`verified` 为 true、`remoteConnected` 为 true、`remoteChecking` 为 false、`remoteState` 为「已连接」、`connectionType` 为「adb 连接 · 远程连接」、`adbWifiEnabled` 为 true。健康问题是空的。状态正文里没有「连接失败 · 重试中」。

网关 `remote-call` 的 ping 返回空 result。`status-safe` 里 `localOnline` 和 `remoteOnline` 都是 true，`mode` 是 remote。18765 上的进程仍是应用包里的 `phone-relay-gateway`。Mac 包没有换，仍是 0.1.0 (37)、临时签名。包里的 `phone-app.apk` 仍是 `80cfb9b92905d5799f72dd4e148d03c474060a23a5114846772284b7b945656c`。

没有重做 join、强杀回执、拖放和那次 11 字节分享。`claude auth status` 仍是 `loggedIn: false`，任务没跑。本机没有 tokenizer，没有 token 数。必须加密没有打开。没有人在手机前，拍照解码没做。没有第二台设备。没有公证，没有换 Secure Enclave 密钥。没有提交，没有推送。HEAD 仍是 `ee4efaa`。

## 2026-10-18 菜单栏包与手机包对齐

上一节结束时，手机包摘要已是 `b0c76783650ce7efd850c26a4439cd5ce02f77c924c4b6315c72b0bc094fc93c`，Mac 包里的 `phone-app.apk` 仍是 `80cfb9b92905d5799f72dd4e148d03c474060a23a5114846772284b7b945656c`。这次先备份再替换。备份是 `~/Library/Application Support/Phone Station/Backups/手机工位-20261018-085205-eec52495.zip`，16962640 字节。替换前菜单栏和网关都在应用包里，归档要求包内没有正在运行的程序，所以先退出菜单栏，再向网关进程发 SIGTERM。没有跑 `mcp.sh stop`，没有动 adb 服务，没有安装手机。

`ditto` 放入新包后 `codesign --verify --deep --strict` 通过。签名仍是 adhoc，TeamIdentifier 是 not set。版本名仍是 0.1.0 (37)。包里的 `phone-app.apk` 与 `build/adb-keep.apk`、手机上的包都是 `b0c76783650ce7efd850c26a4439cd5ce02f77c924c4b6315c72b0bc094fc93c`，5632392 字节。重新构建时 Android 打包写出过另一份同大小的 APK，已把这份已知摘要盖回去再签名，没有拿那份新摘要去装手机。

18765 上的进程是这个包里的 `phone-relay-gateway`，pid 75923，父进程 1，文件 inode 2636017。菜单栏 pid 75930。主令牌 `tools/list` 仍是 18 个没有 `station_` 前缀的名称：`device_list`、`device_status`、`files_list`、`files_stat`、`files_read`、`files_write`、`files_edit`、`files_manage`、`files_grep`、`files_transfer`、`app_install`、`screenshot`、`shell_run`、`clipboard_read`、`clipboard_write`、`phone_alert`、`device_control`、`task_status`。`resultType` 是 complete，`ttlMs` 是 300000，`cacheScope` 是 private。命令行 `call` 的标准输出是 5460 字节。adb 列表仍只有 `192.168.0.106:40623` 这一条 device。`status-safe` 里 `localOnline` 和 `remoteOnline` 都是 true，`ready` 为 true。

本地链路 `127.0.0.1:18766` 上订了六个主题，接受 6 个，拒绝 0。第一条是 device 快照，正文 97 字节。剪贴板和通知各来一条 reset，正文长度为 0，没有历史正文。订阅快照里没有 ops。接着的 7 条 ops 都是 `complete`，出现在一次只读 `device_status` 之后，不是订阅重放。grants 和 capabilities 没有已保存的快照；这次没有撤销授权，也没有改功能开关，所以没有这两条。Mac 设备健康仍每 3 秒读一次 `device_status`。剪贴板和通知的本地等待仍由网关用链路上的事件满足 `event.wait`。

链路主会话没启动时，界面仍显示旧令牌客户端。已安装手机的主会话已经启动，`remoteState` 是「已连接」。没启动时的失败文案「连接失败 · 重试中」留在 `RemoteChannel`，并由 `RemoteChannelTest` 锁住。这条边界写在 [adb-keep.md](../adb-keep.md)。没有为它改手机界面，也没有重装。

没有本地连接时，远程安装会在上传前调用 shell，再按旧任务库查询。链路的 `shell.start` 直接返回带 `exitCode` 的输出，查询旧任务库会得到 missing，于是停在上传前。`shell_output` 把这种没有 `state`、已有 `exitCode` 的结果当成完成，不再查询。真正的 missing 仍停下，不提交第二次。`lib/test_remote_ops.py` 21 项通过。这份 `remote_ops.py` 已放入正在运行的应用包并重新临时签名，网关 pid 75923 和菜单栏 pid 75930 仍在。adb 列表里有 device，没有用 `install-apk.sh` 或 `android.sh --remote` 装手机。没有重试 `4df181740b5246f3b0a519004f2089c2`、`45f7fb016b304a6abea74151228c9a7c`、`4b7236a107bf482dbbda3a9d0f97d9ad`、`8dae623d3c7143fab964c4afa162b4e3`、`5f4a5048c2254dd6b410356638779f1a`。

设置窗口的浅色、深色、大字号和点击验收没有完成。菜单栏面板能在窗口服务器里看到，大小约 324×580，但系统辅助功能列不出这个面板，发到状态栏和面板上的合成点击、Escape 也没有被应用收下。因此没有点到齿轮，没有打开设置窗口，没有切换浅色、深色或大字号。应用自己的主题仍是未设置，也就是跟随系统；系统外观仍是浅色。面板可能还开着，点菜单栏图标或面板外的位置可以关上。

`docs/relay-contract.md`、`docs/remote-control.md`、`docs/remote-screen.md` 已转入 `docs/history/`。当时的 MCP 服务说明转入 `docs/history/mcp-service.md`，没有覆盖原来的 `docs/history/mcp.md`。现行入口留在 `docs/mcp.md`。`python3 lib/doc_links.py` 通过。

必须加密没有打开。手机私有的 `link-seal` 没有写入接口，不能从应用私有目录外面写进去。Mac 的 seal 仍是空的。没有改成静默明文。

这些仍是外部挡路，不算完成：Claude Code 未登录，没有为了跑任务去登录。本机没有 tokenizer，没有 token 数。拍照解码要人在手机前。系统分享面板没有人点过。没有第二台手机和第二台 Mac，P5 的 2×2 不算通过。没有 Apple 开发者账号，Developer ID、公证、自动更新和 Secure Enclave 换钥不算完成。ad-hoc 签名登记 LaunchAgent 会被拒绝，这次没有再注册。

没有重做本地握手、远程握手、已完成回执的跨通道查询、P1 复采、join、强杀后查询、4096 字节拖放和 11 字节分享。没有切公网中继。没有把 `adb_wifi_enabled` 写成 0，没有建第二条 adb 连接，没有重启 adb 服务。没有提交，没有推送。HEAD 仍是 `ee4efaa`。

同一天晚些时候又换了一次菜单栏程序，只为把设置窗口露给辅助功能。备份是 `手机工位-20261018-092205-eb3d6009.zip`。包内 `phone-app.apk` 仍是 `b0c76783650ce7efd850c26a4439cd5ce02f77c924c4b6315c72b0bc094fc93c`。状态栏现在有一项辅助功能动作可以打开标题为「设置」的窗口，窗口浮在其他窗口之上。窗口里的按钮仍不在辅助功能树里，送到窗口上的点击也没有改变主题。浅色、深色、大字号和窗口内点击还没做成。adb 列表仍是原来那一条 `192.168.0.106:40623`，当时状态是 offline，没有另建连接。18765 已由这个包里的网关重新听着。

再往后，设置窗口里的浅色和深色已经点过。窗口服务器上有一层盖住全屏的 shield，外部合成点击到不了这个窗口。点进窗口内部之后，浅色把 `displayTheme` 写成 light，深色写成 dark，随后点了「跟随系统」，键又删掉，恢复成原来的未设置。浅色和深色下都点过「连接设置」和「返回」。没有点「开机自启」「退出手机工位」「手机权限」和「远程访问范围」。语言仍是跟随系统。大字号没有做成：应用域和全局域都写过 `NSFontAdjustsForContentSizeCategory` 与 `UIPreferredContentSizeCategoryName`（`UICTContentSizeCategoryAccessibilityXXXL`），重新打开后设置窗口仍是 635×360，语言行没有下移；系统设置窗口不在辅助功能树里，文字大小滑块没有拨到。这些键已经删掉。验收用的点击记录也已去掉，没有留在菜单栏程序里。

这台 Mac 上 SwiftUI 的 `.body` 不跟着系统文字大小变。设置窗口改为读 `UIPreferredContentSizeCategoryName`，最大档按 3 倍画标题、分区和行。只在应用域写了 `UICTContentSizeCategoryAccessibilityXXXL` 后，窗口里「语言 / Language」高 92、宽 167（缺省时同一行高 16、宽 97），「设置」标题高 56，「连接设置」行高 46，倍率是 3。点了「连接设置」，页面从 more 到 connection；再点标题上的返回，页面回到 more。没有点「开机自启」「退出手机工位」「手机权限」和「远程访问范围」。随后删掉这个键，主题和语言仍是未设置。测量和点击代码已从菜单栏程序去掉。包内 `phone-app.apk` 仍是 `b0c76783650ce7efd850c26a4439cd5ce02f77c924c4b6315c72b0bc094fc93c`，5632392 字节。adb 仍是原来那一条 `192.168.0.106:41591`。

## 2026-10-18 健康事件、设置入口与本机验收

机型仍是 PGT-AN20。adb 仍是 `192.168.0.106:41591` 这一条 device。没有把 `adb_wifi_enabled` 写成 0，没有建第二条无线连接。

已安装手机的 device 快照是 96 字节，正文里没有 `issues`。因此没有卸下菜单栏上每 3 秒一次的 `device_status`。两次各 11 秒的订阅里，ops 都是大约 3 秒一条 complete。电脑测试里，带 issues 的 device 事件会停掉这次空闲调用，正文不变不会再叫一次，没有事件流时仍轮询。网关会留下 device、grants、capabilities 正文，剪贴板和通知的 `event.wait` 仍在本地满足。这份推送没有装进摘要 `b0c76783650ce7efd850c26a4439cd5ce02f77c924c4b6315c72b0bc094fc93c` 的手机包，活的 3 秒轮询受这把摘要锁挡住。同一把锁也挡住手机首页把已连接的无线 adb 显示成「无线已连接」：源码在心跳还在且无线调试开着时使用这个名称，没心跳时不再默认写成「无线未连接」。新的 `build/adb-keep.apk` 没有安装。应用包里的 `phone-app.apk` 仍是上述摘要，5632392 字节。

「电脑提醒 → 手机」从开到关再开回，`phonestation_feature_alerts` 读回仍是 1。第二次链路订阅没有收到 capabilities 或 grants。没有撤销这台 Mac。

设置窗口里点了「手机权限」，手机打开 `PermissionActivity`，返回后是 `MainActivity`。点了「远程访问范围」。正在运行的菜单栏再点「打开手机远程访问范围页」时，空的 `--es` 让 `am start` 失败，界面写着页面未能打开。源码在范围为空时不再带这个参数。用改过的命令打开了 `RemotePermissionsActivity`，再返回 `MainActivity`。没有点「开机自启」和「退出手机工位」。点击记录没有留在菜单栏程序里。

媒体用途的密封测试拒绝明文应用帧，且不把原正文退回去。中继转发的密封帧里没有明文。终端、shell、截屏和投屏各留一条链路回执，不另写 operation job。`lib/android/build.sh` 在修好事件测试后连续两轮通过，没有安装它写出的包。必须加密没有打开。`link-seal` 仍没有从外面写入的接口，没有改成静默明文。目录 fsync 没有在手机上用 `android.system.Os` 验证，没有打开应用私有目录。

本地投屏和录屏在有画面输入时走 native，没有画面时走 scrcpy，脚本和 `ScreenRoute` 各测了两轮。真机这一帧用 `scrcpy --time-limit=2` 看到 330×720，进程退出，没有留下 mirror 或 record。`stay-awake.sh` 和 `vibrate.sh` 各跑了一次；构建出的 `station shell` 应答丢失，息屏值仍是 2147483647，`stay_on_while_plugged_in` 仍是 7，震动没有确认。没有自动重试。没有调用 `SMAppService.agent.register()`。

`subscriptions/listen`、结构化输出、任务扩展和 `station mcp` 有本机测试。App 源码里的剪贴板、通知和投屏调用改用 `clipboard.exchange`、`notification.poll`、`screen.open` 这类无前缀入口；`station_` 仍转发，`clipboard_exchange` 这类旧的无前缀名字不再转发。正在运行的菜单栏没有换成这份源码。Claude Code 没有登录。没有 token 数。P5 的 2×2 没有做。

`remote-gateway.log` 是 0 字节，没有 `.1`。这次没有为了轮转重启网关。角色令牌条目没有删。远程 ping 的 result 是空对象。回执、中继 6 的注册表和归档目录没有动。没有写受信代理名单。Wi-Fi、蜂窝、后台保活和耗电没有测。`scripts/check.sh` 这次没有跑。没有提交，没有推送。HEAD 仍是 `ee4efaa`。

这些仍是外部挡路：Claude Code 未登录，没有 tokenizer，拍照解码，系统分享面板，第二台手机，第二台 Mac，P5 的 2×2，Developer ID，公证，自动更新，Secure Enclave 换钥，ad-hoc 的 LaunchAgent 登记。



## 2026-10-25 启动连接恢复与本地心跳

源码在 main 的未提交工作区。完整 `scripts/check.sh` 通过，含 52 个纯 Java 测试、26 个 Swift 测试、Go race、TLS 联合合约及 Mac 全量源码编译。完整 Mac 构建及其 Android APK 构建通过；没有提交、推送或运行云端 CI。

- 初始电脑 App 已启动，但 18765 没有监听。App 原来只订阅网关状态，没有启动缺失网关；手动启动后远程实际调用通过。现改为 App 专属 `mcp.sh watch` 在订阅前幂等启动网关，监听断开重试时也能恢复；普通命令行 watch 继续只读，不做 adb 连接或改手机开关。三项脚本回归验证启动顺序、失败退出和 NDJSON 不混入启动输出。
- 原工作区的无线重连修正保留并验证：设备列表不变化时，退避到期仍主动重试；已有无线配对记录不因含糊的连接失败暂停。集成回归用注入的连接命令验证静止设备列表也重试；测试不依赖本机配对文件，不调用真实连接入口。
- 无线初始有 mDNS 广播，但 adb 没有设备；TCP 和旧 adb 均报 `No route to host`，Mac 路由走 en0。用户确认手机工位已有本地网络权限。手机回程查询经过 tun0，但 ping 电脑成功；用户关闭手机 VPN 后仍失败，因此不能将 VPN 路由视为已证实根因。核实本机旧 adb 身份、运行约六天且没有在线设备后停止该服务，由手机工位重新启动并连接，PGT-AN20 进入 device。用户恢复 VPN 后仍在线。本次没有定位旧 adb 拒绝连接的底层原因，不自动重启共享 adb 服务。
- Android 的远程调用此前也会刷新本地心跳，手机会在 adb 为空时误报本地连接。现仅本地链路的认证请求刷新心跳；主会话远程 ping、远程文件查询和旧中继 RPC 都不刷新，`LinkHttpTest` 覆盖这些路径。真机未为验收主动断开现有本地连接，不能把双路在线检查当成仅远程显示验收。
- Mac 仍为 0.1.0 (37)，已退出旧 App 和网关、归档旧包并替换，整包摘要 `b113cdea224d4ce75c89f42b428fecbef56d6853d0f954b87ff9ea038dcc534d` 与安装目录一致，深层严格签名通过。旧包为 `手机工位-20261025-024655-10b516c0.zip`，位于用户应用支持目录的 Backups 下。启动新版时没有预先启动网关，随后确认网关从正式安装目录运行、远程往返在线。
- Android 仍为 72，已有无线 adb 时用正式 Mac 包的 `android.sh --adb` 保留数据升级，没有卸载。手机 APK 与随包 APK 的 SHA-256 都是 `93a1399f13eca6403db4ff8b22703d9b1505974b9c037ff5393452b4f3af13ed`。MCP 与保持服务均为前台运行；网关确认本地、远程都在线，VPN 已由用户恢复。公网中继未修改或重启。

本机独立回执为忽略目录下的 `build/.phone-station/connection-deployment-20261025.json`。本次没有截图、录屏、通知正文验收或无线开关关闭测试。
