# 三端发布、部署与回滚

一份源码提交不代表三端都已运行它。每次发布记录源码提交、组件版本、产物 SHA-256、检查结果、安装结果及服务恢复结果。真实部署回执保存在私有运维记录中，不提交凭据或个人配置。

## 本轮版本与状态

| 组件 | 当前源码目标 | 状态与证据 |
|---|---|---|
| Android | 67 | 已远程升级；原任务 completed / verified 为 true，安装 APK 摘要一致，本地与远程 MCP 均返回 67，总开关与 11 项子开关已部署 |
| Mac / 本机网关 | 0.1.0 (30) | 已整包安装；签名、安装目录摘要、安装版 App / 网关进程核实，原配对与本地、远程连接保留 |
| 公网中继 | 4 / durable-epochs-v1 | 已部署；实际运行程序摘要、持久目录、TLS、角色隔离及歧义请求拒绝核实 |

本轮功能开关、界面与安全改动在 main 集中维护；实际安装来自当时的工作区构建，保留原回执中的 dirtyWorkingTree 事实。总开关与功能选择的规则见 [功能开关](features.md)，实际部署与验证边界见 [功能开关验收](history/features.md)；已有安全边界的验收见 [安全验证记录](history/security.md)。中继 4 的新构建与现网字节相同，本轮未停启或替换公网程序。最新 Android 首页与 MCP 页面整理、安装摘要和自动恢复结果见 [界面验收](history/design.md)。最后一次仅更新 Android，Mac 30 随包 APK 仍为此前已记录的构建；下方早期版本记录不代替本轮验证。

## 发布入口

```sh
./scripts/check.sh
./scripts/build-relay.sh
lib/android/build.sh
./scripts/build-mac-app.sh
```

中继发布包在 `build/.phone-station/relay/linux-amd64/`，包含程序与 `manifest.json`。完整 Mac 构建也自动生成 `build/.phone-station/android-manifest.json` 和 `mac-manifest.json`。回执标明源码提交、工作区是否有未提交改动、三端源码版本、产物大小和摘要，默认 `deployed: false`。按用户授权部署未提交工作区时，清单如实保留 `dirtyWorkingTree: true`，Git 提交与推送需另有明确要求。只构建 Android 或需要重新核对本地产物时也可用下面的内部工具生成回执，不会连接设备：

```sh
python3 lib/release_manifest.py android build/adb-keep.apk build/.phone-station/android-manifest.json
python3 lib/release_manifest.py mac 'build/.phone-station/手机工位.app' build/.phone-station/mac-manifest.json
```

APK 和中继回执使用文件 SHA-256；Mac 回执列出整个 App 内每个文件的相对路径、权限、大小和摘要，bundle-tree 的 SHA-256 来自这份清单的规范 JSON，覆盖主程序、网关、APK 与签名。手机最终记录安装任务编号、已安装 APK 摘要、实际 MCP 版本和服务恢复；Mac 核对整包清单、签名和实际网关路径；服务器核对程序摘要、systemd 状态、持久目录和经过 TLS 的角色鉴权。分别记录本机回归、APK/App 打包、Git 推送、设备部署、生产服务恢复，不能互相替代。

## 兼容顺序与回滚

先安装 Mac 20 并确认它可与旧中继通信，再更新公网中继 2；Android 继续透传操作编号，无需为会话协议再次配对。新中继拒绝旧的无会话编号，旧 Mac 不能在服务器升级后继续调用。更新保留原证书与两枚角色令牌；地址、端口和对外范围不变。

公网切换前，准备新二进制、验证摘要和 systemd 模板，确认磁盘空间与状态目录权限；保留旧程序和旧 unit，配置与私钥按部署侧私密备份策略处理。停机窗口需要具体授权；停旧服务后才取得一致的状态副本，替换并启动新服务，再验收。通用步骤见 [中继 README](../server/relay/README.md)，实际主机操作由私有运维记录承载。

持久状态使用版本 1：`state.json` 保存配对身份摘要和当前会话代号；每个回执独立落盘，结果正文单独保存。配对凭据、状态目录和二进制版本必须一起考虑恢复。单实例文件锁防止两份服务写同一目录；损坏或不兼容时启动失败，不能静默生成新会话来掩盖损坏。

新服务一旦接收过操作，回滚仅能使用能够读取该状态格式、保留旧编号拒绝语义的版本。原内存版不属于安全回滚目标。首次启用失败且尚未接收任何新操作时，核实后可以恢复旧服务；接收过操作后优先修复或恢复兼容的持久版，不能丢弃回执。备份通过停服务后的完整目录副本保持一致，不单独复制正在更新的某个文件。

## 验收记录

2026-10-03：统一本机检查通过，包含文档镜像与 36 份 Markdown 链接、51 个 Python 用例、28 个纯 Java 测试、两个 Go 模块 race、真实中继 TLS 联合合约、12 个 Swift 测试及 Mac 全量源码编译。新增覆盖关闭/存储失效的等待轮询和结果正文/完成回执落盘失败。之前云端配对测试依赖未安装的 rg，已改用系统 grep；完整 Mac 编译使用 macOS 26 API，补充 SDK 前置核对并让 CI 选择已安装的新 Xcode。连接帧解码器显式声明初始化入口，兼容云端 Swift 对私有存储属性的访问控制；原分片解码回归保留。发布源码提交的 [Mac 全量与 Linux CI](https://github.com/qx0657/phone-station/actions/runs/37130545576) 均通过。

源码迁移保留原相关提交，并逐文件核对迁移前的未提交修改。最终发布包从干净提交 `551714e6ff67e6f812b3c50673cb4750f05fa30f` 构建，三份清单的 `dirtyWorkingTree` 与 `deployed` 均为 false；重新计算产物摘要一致，Mac 整包签名核对通过。结果缓存的读取窗口与空闲时物理清理边界见 [中继合约](relay-contract.md#会话编号与兼容)。

| 产物 | SHA-256 | 本地回执 |
|---|---|---|
| Linux/amd64 中继 2 | `018c2ab442a759d7bc6de3084d8c2f186d4468e7d91d5e8409b44de016625632` | `build/.phone-station/relay/linux-amd64/manifest.json` |
| Android 60 APK | `f5ad85e97096733e4efc21b9e9032478d121b5a7040df7619428d60bbe5afa70` | `build/.phone-station/android-manifest.json` |
| Mac 0.1.0 (20) 整包清单 | `0642738a1f5fe050a919d83365a82403110c79d6d718ae667810d541af7a63bf` | `build/.phone-station/mac-manifest.json` |

切换前，中继候选程序已在目标 Linux 上核对同一摘要、版本输出及 systemd unit 语法；旧 Mac 整包、旧中继程序和 unit 的恢复副本已准备。候选包核对与生产部署分别记录。

## 生产切换验收

2026-10-03 用户明确授权生产切换后，先安装 Mac 0.1.0 (20)，核对整包清单与签名，并验证它通过原中继完成初始化、工具列表及只读状态调用。新版钥匙串助手读取已有凭据时需要用户完成系统授权；处理见 [故障排查](troubleshooting.md)。原配对、证书指纹、凭据配置标识与本机网关身份均保留。

随后更新公网中继 2 的程序与 systemd unit，服务停启约 1 秒，恢复为 enabled / active / running。生产程序摘要与上述发布清单一致，持久目录 0700、文件 0600；已观察到完成回执与短期结果缓存。配置、证书和私钥摘要核对未变，端口与配对沿用。

公网 TLS、IP 主机名与原 SPKI 固定验证通过；无令牌、错误令牌、手机角色访问电脑接口、电脑角色提交手机结果均返回 401，正确电脑角色握手返回 200 并提供持久会话代号。Mac 经中继完成初始化、列出 43 个工具、读取设备及控件状态；统一入口的本地调用也通过，两通道均在线。

中继切换阶段手机保留 59，已验证可透传新编号；随后按用户要求远程升级到上述同一提交的 APK 60，安装任务回执为 `installed`、`verified: true`、`completed: true`。已安装 APK 的 SHA-256 与发布清单一致，助手记录服务与主页自动启动、进程更换及本次任务标记通过；本地与远程 MCP 均实际返回 60，原配对与 Shizuku 可用状态保留。

手机升级后曾观察到一条已派发请求仍为 running，远程队列阻塞。停止同一持久中继、备份完整状态后原版本恢复启动，旧请求转为 unknown 且回执保留；仅查询原安装任务，未重新安装或手动重放启动。手机服务与主页的自动拉起检查通过，远程通道则在此次中继恢复后通过验收；不能将本次情况描述为全程无需部署侧介入。处理见 [故障排查](troubleshooting.md)。

构建清单继续记录构建时的 `deployed: false`，独立部署回执记录 `deployed: true` 与三端验收结果，保存在私有运维记录及忽略的本地构建目录。持久服务已接收操作，后续恢复必须保留状态目录并使用兼容持久版本，不能回退原内存程序。

## Mac 21 远程命令

2026-10-04：命令页保存的 `shell …` 在只有远程连接时通过 Shizuku 后台任务执行，按机型与 Shizuku 状态逐条启用并说明不可用原因。本地仍优先使用 adb；交互式终端没有扩展到远程。提交前持久保存手机任务编号，应答丢失或 App 重启后只查询原编号，不重新提交，也不回退 adb。结果未知时暂停新远程命令，可查询原任务或结束本机跟进。

统一本机检查通过：51 项 Python 测试、28 个 Java 测试程序、两个 Go 模块 race、真实中继 TLS 联合合约、13 个 Swift 测试及 Mac 全量源码编译。完整 Mac 21 与随包 Android 60 APK 打包通过；本次只修改 Mac，手机与公网中继无需更新。

使用本次 `CommandSession` 源码注入固定的 `remote-call` 传输，保留现有本地连接，确认实际中继上的 PGT-AN20 与 Shizuku shell 身份。两个只读任务完成：前台应用管道查询、引号与机型查询；通过原任务状态查询取得结果。模拟回归另覆盖丢失提交应答、App 重启、未知/缺失回执、编号不匹配、连接变化和拒绝/超时结果。

Mac 安装包从干净提交 `387e8f3502719f1e6dc3a409c69cf920d598a167` 构建，整包 SHA-256 为 `0341853cd6decdda031fd7ed49a0290c9f1f161b38404f6c162dab414cfb8e8c`。已退出旧 App 与本机网关、保留 Mac 20 完整备份，再替换并启动 Mac 21；安装目录整包摘要与构建清单相同，签名核对通过。实际网关进程使用 `/Applications/手机工位.app/Contents/Resources/phone-station/lib/phone-relay-gateway`，本地与远程均已确认在线。安装后通过该网关再次完成上述两个远程只读命令。原配对与凭据沿用，没有更新手机 Android 60 或公网中继 2。构建清单仍为 `deployed: false`，独立安装回执 `build/.phone-station/mac-21-deployment.json` 为 `deployed: true` 并记录连接验收。

首次云端检查在已有的 `McpHttpTest` 慢请求用例触发 Broken pipe：服务器按绝对期限关闭连接后，客户端仍可能继续写入。后续提交 `187ce1978d8d2969395253ae76be47ba925000de` 只修正该回归测试，允许写端断开并继续严格验证实际 HTTP 408 与 1500 毫秒上限，未改变 Android 运行逻辑。该用例连续运行 12 次、统一本机检查及 Android 打包通过；[修正后的 Mac 与 Linux CI](https://github.com/qx0657/phone-station/actions/runs/37136601098) 均通过。


## Mac 22 远程交互终端

2026-10-04：Mac 22 和 Android 61 增加经现有 MCP 中继使用的 Shizuku PTY。只有远程连接时，Mac「adb 命令」页可在系统终端中打开远程 shell；本地 adb 在线时继续优先本地终端。远程会话保留目录和环境，支持 Ctrl-C、持续输出、UTF-8、方向键编辑和窗口尺寸同步。短暂断线或 Mac 客户端退出后按原 sessionId 恢复；输入应答丢失时只查询原输入序号，不重发。会话 60 秒无人读取或达到 1 小时时清理前台和后台进程，手机应用或 Shizuku 重启后不重建原 shell。

统一本机检查通过：59 项 Python 测试、29 个 Java 测试程序、三个 Go 模块 race、Android/arm64 交叉编译、真实中继 TLS 联合合约、13 个 Swift 测试及 Mac 全量源码编译。完整 APK 与 App 打包通过，[最终代码的 Mac 与 Linux CI](https://github.com/qx0657/phone-station/actions/runs/37140717519) 均通过；Linux 另执行真实 PTY 的目录、Ctrl-C 和尺寸回归。云端曾发现服务关闭会覆盖尚留在活跃列表中的已过期回执；已修正为只将 queued/running 标记未知，确定性回归能捕获原实现，修正后该测试连续运行 30 次通过。

两端最终产物均来自干净提交 `8b6f407c8ccccb003530f9421cbeb8be520fbe3b`，构建清单 `dirtyWorkingTree: false`。手机安装任务 `62fec1a81a31495aa301c2409653af38` 保留原数据，只恢复后台服务，未打开手机主页。首次电脑等待未确认；安装助手已有成功退出码，随后按原编号经远程查询得到 `installed`、`verified: true`、`completed: true`，没有重新安装该任务。服务记录确认进程更换、MCP 前台服务及相同 PID 的本次任务标记；最终 Mac 网关替换并启动后，本地与远程 MCP 均实际返回 61，两通道均在线。公网中继 2 保留原程序与持久回执，本轮没有停启公网服务。

| 实际安装产物 | SHA-256 | 独立部署回执 |
|---|---|---|
| Android 61 APK（与最终 Mac 随包 APK 相同） | `b95a4fa6f222ebcb49c71c3c41736081cbfcdfbd229296945f298bbc48e4b02f` | `build/.phone-station/android-61-deployment.json` |
| Mac 0.1.0 (22) 整包清单 | `0572e8967ed83b59021c6fa9ad787a4d8c60ad2970f77613d196d5cb2ff1e289` | `build/.phone-station/mac-22-deployment.json` |

Mac 安装保留旧 App 的完整备份，核对安装目录整包摘要、深层严格签名，以及实际 App 和网关均从 `/Applications/手机工位.app/` 运行。构建过程同时修正钥匙串助手的程序身份：旧输出名含 PID，源码未改也会改变模块名和临时签名。现在按源码、目标架构和二进制摘要复用未变化的已授权助手，新编译使用固定模块名与文件名；真实编译回归验证两个不同目录的产物摘要一致。最终包保留原已授权助手的相同字节和签名，沿用配对与凭据，两通道恢复已核实。

PGT-AN20 / Android 15 的实际远程验收确认 PTY、目录与环境保留、Ctrl-C 中断后 shell 继续运行、尺寸 110×37、中文输出、方向键编辑、持续输出及原会话读取。注入输入应答丢失后只查询原序号，命令仅执行一次。断读 64 秒后原会话为 expired，shell 与已记录的后台 sleep 子进程均已不存在。已安装 Mac 的随包终端客户端被强杀后，以同一编号恢复并核对原目录和环境；Ctrl-] 关闭成功，正常关闭后本机 TTY 模式恢复。原生 Android 提示符来自系统 mksh 配置，真机验收按实际提示符判断，不依赖自定义 PS1。

## 远程实时屏幕初始候选记录

以下保留首次候选阶段的计划与当时状态，正式部署结果见最终验收记录。

Android 62、Mac 0.1.0 (23)、公网中继 3 增加仅远程连接时的实时投屏、手机音频、鼠标/键盘输入和 Mac MP4 保存，详见 [远程屏幕](remote-screen.md)。视频连接与 MCP 队列分开；公网服务保留 durable-epochs-v1 状态格式、原回执与配对。中继 2 能继续提供原 MCP 功能，但不能承载新屏幕连接。

候选产物先由 `scripts/check.sh`、`lib/android/build.sh`、`scripts/build-mac-app.sh` 和 `scripts/build-relay.sh` 验证。公网切换需要覆盖中继 3 停启的具体授权，再保留原程序与完整持久目录并升级，随后通过远程安装 Android 62、替换 Mac 23，验收仅远程投屏、声音、控制、录屏和关闭后的进程清理。切换失败时恢复能读取同一状态格式的中继 2；不删除回执、不回到内存版。尚未完成部署和真机屏幕验收，不能将候选源码与产物描述为已上线。

## Android 63 / Mac 24 真机修复候选

2026-10-04：仅远程真机部署时发现 Android 62 的采集 UserService 引用了桌面 JDK 的 `ProcessBuilder.Redirect.DISCARD`，Android 15 没有该字段，服务退出后返回 DeadObjectException。改为独立线程排空错误流，资产读取使用有界循环，并区分 Binder 身份错误与连接中断。修复后的临时 APK 已在完全断开 adb 时完成实时 H.264/AAC 收流、18.3 秒 MP4 保存与会话结束；音频当时为静音，实际声音与控制验收继续进行。正式修复版本为 Android 63、Mac 24；视频默认 2 Mbps，为远程控制与 MCP 留出带宽，打开投屏时沿用 scrcpy 的唤醒行为。正式产物安装与三端恢复另记验收回执。

## Mac 25 录屏音轨修复

2026-10-04：真机录屏发现连接建立较慢时，音频元数据的等待期限在收到画面前已耗尽，MP4 可能只保存视频。Mac 25 从第一帧画面开始计算等待期限，增加延迟 AAC 元数据的原生回归。在仅蜂窝、adb 离线的 PGT-AN20 上，修复代码完成 81.5 秒 H.264/AAC 录制，48 kHz 双声道解码后具有非零声音；原生窗口点击、Home 与返回键生效，关闭窗口保存成功，采集进程退出，Wi-Fi 已恢复。最终整包部署见下方验收回执。

## 远程实时屏幕最终部署验收（2026-10-04）

用户明确要求真机部署后，Android 63 与中继 3 使用干净提交 `880a07312a447a98cce4b1272d2067c0b0192c49`，Mac 25 使用干净提交 `a37d9d8b6f196e579798b7dd8cdbb169b6e69c69`。完整本机检查通过：59 个 Python、29 个 Java、4 个 Go 模块 race、真实 TLS 中继联合合约、15 个 Swift 回归与 Mac 全量编译；三端正式构建通过。Android 安装任务 `565599af376d45cb83f62fe1ea14d3d0` 原编号查询确认 `verified: true`、`completed: true`，保留数据、后台服务自动恢复，实际远程 MCP 返回 63。

| 实际部署产物 | SHA-256 |
|---|---|
| Android 63 APK | `9c60f4c715f634c7a5bcb08d63f6cb58d3613e990901c1857d2bf5b7cb20d21b` |
| Mac 0.1.0 (25) 整包清单 | `f62296354b84845c09933a7480a6e11f22b03b70f4e678765e546e93c04101a4` |
| 中继 3 Linux/amd64 | `9a9f77acbe0db43f77023de221eede03d199f0e3c78f9d1e6512f7505e355214` |

Mac 完整安装目录的整包清单与深层严格签名匹配，已授权钥匙串助手保留相同字节，随包 APK 与实际安装的 Android 63 相同，并在清单单独登记其源码提交。App 和网关均从正式安装目录运行，本地与远程通道同时在线。公网中继最终 stop/start 为 0.091 秒，实际执行文件摘要、active/running 与零自动重启核实；原配置、证书、私钥、unit 和 durable-epochs-v1 回执全部保留并完成持久目录备份。初次候选的进程就绪竞态及 Android 临时修复恢复单独保留记录，没有重放安装或删除回执。

PGT-AN20 / Android 15 的仅蜂窝、adb 离线验收确认原生实时画面、鼠标点击、Home/返回键与 81.5 秒 MP4；H.264 736×1600、AAC 48 kHz 双声道，音频峰值 -24.6 dB，两个流时间戳严格递增且解码通过。最终干净 Mac 代码再次完成 15.3 秒仅远程 MP4 保存与解码。停止录屏保留投屏、关闭窗口保存已有录像，结束后采集进程退出。普通 Wi-Fi 已恢复，无线调试保持开启，临时手机测试文件已清理。

独立三端回执在本机忽略目录 `build/.phone-station/remote-screen-deployment/final-deployment.json`，实际主机记录留在个人运维仓库，手机画面与录像只留本机。未验证其他机型、手机重启后的 Shizuku 自动恢复或一小时持续会话。

两份实际代码提交的 CI 均通过：[Android / 中继构建提交](https://github.com/qx0657/phone-station/actions/runs/37207629488)、[Mac 25 修复提交](https://github.com/qx0657/phone-station/actions/runs/37209193835)。后续部署回执只更新文档，不改变上述实际安装产物。
