# 三端发布、部署与回滚

一份源码提交不代表三端都已运行它。每次发布记录源码提交、组件版本、产物 SHA-256、检查结果、安装结果及服务恢复结果。真实部署回执保存在私有运维记录中，不提交凭据或个人配置。

## 本轮版本与状态

| 组件 | 当前源码目标 | 状态与证据 |
|---|---|---|
| Android | 60 | 已保留数据升级，APK 摘要、自动启动和本地/远程实际版本均已核实 |
| Mac / 本机网关 | 0.1.0 (21) | 已安装并启动，整包摘要与签名核对通过；本地/远程在线，安装后的远程命令验收通过 |
| 公网中继 | 2 / durable-epochs-v1 | 已部署并启用持久回执，TLS、角色隔离与只读 MCP 验收通过 |

本轮最终构建摘要和提交编号见下方验收记录。历史 Android 59 / Mac 17 的安装及旧中继上线事实见 [历史验证](history/README.md)，不能用来声明本轮版本已部署。

## 发布入口

```sh
./scripts/check.sh
./scripts/build-relay.sh
lib/android/build.sh
./scripts/build-mac-app.sh
```

中继发布包在 `build/.phone-station/relay/linux-amd64/`，包含程序与 `manifest.json`。完整 Mac 构建也自动生成 `build/.phone-station/android-manifest.json` 和 `mac-manifest.json`。回执标明源码提交、工作区是否有未提交改动、三端源码版本、产物大小和摘要，默认 `deployed: false`。正式发布应来自已提交的工作区；只构建 Android 或需要重新核对本地产物时也可用下面的内部工具生成回执，不会连接设备：

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
