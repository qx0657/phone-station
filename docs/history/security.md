# 2026-10-04 远程安全加固验证

源码版本为 Android 64、Mac 0.1.0 (26)、中继 4。修改留在 main 工作区，构建回执的 `dirtyWorkingTree` 为 true；未提交、未推送。最初完成构建时尚未安装或部署，用户随后明确要求「安装部署」，实际结果见下方。

## 完成本机验证

- `scripts/check.sh` 通过：文档镜像、Skill 入口、语法、Python、四个 Go 模块 race、Android/arm64 交叉编译、真实 TLS 中继联合合约、30 个 Java 测试、15 个 Swift 测试和 Mac 全量源码编译。
- 后续收尾改动由 `scripts/build-mac-app.sh` 重新运行对应回归、完整 `lib/android/build.sh`、原生控件回归与 Mac 全量源码编译，成功生成 Android 64 APK 和 Mac 26 整包；新增文件锁等待后的权限撤销测试单独再次通过。
- `scripts/build-relay.sh` 的 race 回归和 Linux/amd64 发布包构建通过，保留 durable-epochs-v1 与 screen-v1。
- 四个 Go 模块分别用 `govulncheck` v1.8.0 扫描，均返回 `No vulnerabilities found.`。这是当次数据库与源码可达性的扫描结果，不代表没有未知漏洞；WebSocket 上游公开信息见 [安全公告](https://github.com/coder/websocket/security)。
- `apksigner verify` 与 Mac `codesign --verify --deep --strict` 通过；APK 元数据核实为 64。逐项重算产物摘要与回执一致，Mac 内置 APK 和网关与独立产物一致。

攻击场景覆盖手机默认拒绝、六项权限独立、未知工具、隐藏工具列表、混合批次在副作用前拒绝、转义同名字段、跨类型任务输出拒绝、文件事务锁等待期间撤销、排队终端撤销与旧编号不重开、撤销截屏时不创建文件、HTTP 307 不重发操作和 Bearer、中继角色分离、重复认证头、浏览器 Origin、URL 凭据、重复查询编号、重复 JSON 字段、无效 UTF-8、过深嵌套及冲突结果不改变回执。

## 产物摘要

| 产物 | SHA-256 |
| --- | --- |
| `build/adb-keep.apk` | `97ee462e5adbb68d4b9d69d92029d4dcfc2177e723a7b8662bef1cd7055ae638` |
| `build/.phone-station/手机工位.app` 的完整包目录回执 | `58a1cfd1420dbe258fac4b042861b59e99b96604ca3df24c18ab8ecd51c19032` |
| `build/.phone-station/relay/linux-amd64/phone-relay` | `ea11bfb218c3e1d53f9a7b0dc41fccc226f334049d1ef8894dc705250e4f91fd` |

对应 JSON 回执位于 `build/.phone-station/android-manifest.json`、`mac-manifest.json` 和 `relay/linux-amd64/manifest.json`，三份均标记 `deployed: false`。构建使用既有发布签名，由签名助手内部读取，未输出凭据。

## 构建阶段的验证边界

没有调用真实手机 MCP、adb、截屏、剪贴板、通知或手机安装入口，没有读取生产中继配置或令牌。未验证手机权限页的实际操作、SharedPreferences/Keystore 真机行为、撤销时 Binder 和系统进程清理、真实网络切换，以及生产代理透传与恢复。中继仍是可信方，已开启权限的风险见 [当前安全规则](../security.md)；旧手机上的远程行为保持旧版状态，候选构建完成不表示防护已上线。

## 授权后的安装部署（2026-10-04）

用户要求安装部署后，再次核对三份候选摘要，安装 Mac 26、切换中继 4，再通过原远程通道原地升级 Android 64。部署时仅远程在线，手机状态确认 PGT-AN20；未新建 adb 连接、重新配对或代替用户开启任何远程权限。

Mac 保留完整 Mac 25 备份，退出旧 App 和网关后替换整包。安装目录清单摘要与上述候选一致，深层严格签名通过，实际 App 和网关均从 `/Applications/手机工位.app` 运行。已授权钥匙串助手字节一致，配对配置摘要未变，远程通道恢复。

公网中继使用独立 systemd 任务切换，停止原服务后备份完整持久目录（625 个文件），再替换程序。stop/start 为 0.391 秒，实际执行文件摘要与候选一致，enabled / active / running，自动故障重启次数 0。配置、证书、私钥、unit 摘要未变，状态格式 1、目录 0700 / 文件 0600 保持。TLS 证书与主机名验证、电脑角色状态接口、缺失/错误令牌、双向角色隔离、浏览器 Origin、重复认证头、URL 参数、重复 JSON 及无效 UTF-8 检查通过；没有删除回执或退回内存版。

Android 安装任务 `ff07cf3018fb4f5d90679d52f717f58c` 上传后校验候选 APK 摘要，提交一次保留数据安装。电脑短暂等待未确认完整回执，随后实际远程 MCP 返回 64、`remoteConnected: true`；此期间没有手动打开手机 App、重新安装或再次停启中继。原任务的只读 Shell 查询被新权限明确拒绝，因此没有核实设备已安装 APK 的摘要、安装助手 `completed` 和主页自动拉起标记，不能将运行版本与连接恢复描述为完整安装回执通过。

真机返回六项 `remotePermissions` 全部 false，Shell / 终端、投屏、截屏、控件均不可远程使用。工具列表只保留四个状态工具和投屏/终端关闭；对 Shell、投屏和文件写入的直接调用在参数处理前被拒绝，验证没有触发这些操作。配对保持，Shizuku 已有授权保留但远程 Shell 不开放。

独立回执位于本机忽略目录 `build/.phone-station/security-deployment/`：`mac-installed.json`、`cutover-result.json`、`relay-acceptance.json`、`android-install-task.json`、`android-original-receipt-query.json`、`android-acceptance.json` 和 `final-deployment.json`。三份原构建清单继续保留 `deployed: false`，部署回执分别记录实际层次；实际主机与备份位置只写个人运维仓库。

尚未验证手机权限页手动开启/撤销的真实操作、撤销时 Binder 与系统进程清理、切网、手机重启恢复，以及其他机型。仅远程通道核实在线，本地 adb 未连接；没有为验证开启屏幕会话、接收通知正文或交换剪贴板。中继仍是可信方，已开启能力的风险见 [当前安全规则](../security.md)。

## 权限入口整理与后续升级（2026-10-04）

Android 65 将六项远程授权独立放到「权限与检查 → 远程权限」，远程通道保留摘要和跳转，已保存的中继配置默认折叠。手机剪贴板与通知在已启用、仅远程在线且缺少授权时给出具体原因和入口，共用 personal 权限的影响只计一项。Mac 27 为投屏、截屏、控件、剪贴板、通知、Shell 与安装提供缺项说明和授权步骤，已有本地 adb 时可定位手机权限项。安装与截图下载在提交前检查完整权限组合，返回授权页只刷新，不补执行旧操作。

最终 `scripts/check.sh` 通过：62 个 Python 用例、30 个 Java 测试、15 个 Swift 测试、四个 Go 模块 race、真实 TLS 中继联合合约与 Mac 全量源码编译。Android 与 Mac 完整构建、签名核对通过。新增用例覆盖未授权安装不分配或覆盖任务编号、不回退 adb、截图缺文件读取时不创建截图、通知只查无正文状态、剪贴板不读本机内容、途中撤销不应用返回数据、恢复只建立基线，以及旧状态字段兼容。授权状态在 Mac 面板关闭后仍在后台只读刷新，避免同步因状态过期停止。

| 产物 | SHA-256 |
| --- | --- |
| Android 65 APK | `e02591ce719b761089014420e3c2dd740f32041039d340b0c66fca6df9631d22` |
| Mac 27 整包目录清单 | `697271431d3fb78f24b4dbfb95dd3fe47b067069e9bd3f72f89e5c2be78db4e1` |

此次开始时手机六项权限已全部开启，执行过程没有替用户修改授权。先查询上一版 Android 64 的原任务 `ff07cf3018fb4f5d90679d52f717f58c`，其 installed / verified / completed、APK 摘要、服务恢复与主页拉起均确认；这补齐了上节因权限默认关闭而未能核实的安装回执，不改变当时的默认拒绝验收事实。

Android 65 只提交一次安装任务 `fa46ad8ecfab4a69bf1f4fb10ed44229`，原回执 verified / completed 为 true；自动恢复为 ready，服务与主页标记通过，实际远程 MCP 返回 65。独立状态调用返回 PGT-AN20、remotePermissionPageVersion 1、remoteConnected true，六项既有选择全部保留。更新期间等待恢复，仅查询原编号，没有重装。

Mac 27 替换前保留 Mac 26 整包备份并退出旧程序；严格签名、完整目录摘要、原配置字节摘要、同一钥匙串助手均核对一致，实际 App 与网关均从 /Applications 的新包运行。统一网关远程在线且已验证，本地 adb 仍未连接。中继 4 沿用上一节部署，没有再次切换公网服务或修改持久回执。

本次回执在本机忽略目录 `build/.phone-station/permission-deployment/`：validation.json、两份构建清单、android-install.json 和 acceptance.json；上一版补验为 `build/.phone-station/security-deployment/android-original-receipt-resolved.json`。构建清单仍标记 deployed false，部署结果另记；没有提交或推送。

界面视觉验收尚未完成：桌面 CUA 读取已安装 App 多次超时，未能截取新页面；手机权限页继续使用 FLAG_SECURE，未绕过屏幕保护。入口、授权提示和布局已通过源码与编译检查，真机手动开关、定位滚动、撤销时 Binder 清理及其他机型仍未验证。未为验收截屏、交换剪贴板、接收通知正文或调整远程授权。
