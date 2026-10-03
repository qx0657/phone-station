# 远程 shell 与安装升级

已配置的远程中继负责传输，手机上的 Shizuku 13+ 提供 shell 权限。以下入口经本机网关共享队列固定调用中继，不运行 `adb connect`、`adb shell` 或本地 adb 转发，也不需要菜单栏 App 保持打开。复用已有 HTTPS 地址、SPKI 指纹及钥匙串凭据，不打印令牌。手机工位需要已有 MCP 文件工具和 `station_shell_*`（37 版起）。

## 使用

仓库检出后先按 [Mac 构建说明](mac-app.md) 运行 `./scripts/build-mac-app.sh`，生成 `build/.phone-station/phone-relay-gateway` 与钥匙串助手。构建不安装到手机。已安装的 Mac App 自带这些入口，使用随包脚本时不需要重新构建。随后配置两端中继，见 [MCP 配置](mcp.md#启动)；配置完成后，远程脚本自动启动本机网关（若未运行），所有 CLI 与菜单栏请求共用有界队列；无需 App 窗口保持打开。网关更新后需重启旧网关进程，旧版不支持共享 CLI 入口。

在仓库根目录运行：

```bash
# 只读查看 Shizuku 状态
./scripts/shell.sh --status

# 写手机上的命令，不带 adb shell 前缀
./scripts/shell.sh 'pm list packages'
# 查询原 shell 后台任务，不重新执行
./scripts/shell.sh --job-status <32位任务编号>
./scripts/shell.sh --json --timeout-ms 60000 'dumpsys package dev.phonestation.adbkeep'

# 上传前只检查远程能力，不安装；通道未就绪返回 75，未验证机型返回 1
./scripts/install-apk.sh --check

# 安装新应用，或保留应用数据升级
./scripts/install-apk.sh /电脑上的路径/app.apk

# 一次提交完整的 base + split APK 集合
./scripts/install-apk.sh /电脑上的路径/base.apk /电脑上的路径/split_config.arm64_v8a.apk

# 构建并远程更新手机工位自身
./scripts/android.sh --remote

# 更新手机工位时只恢复后台，不打开主页
./scripts/install-apk.sh --no-open /电脑上的路径/adb-keep.apk

# 安装或启动结果未确认时，只查询原任务
./scripts/install-apk.sh --status '<32位安装任务编号>'
```

`shell.sh` 保留命令退出码，超时返回 124；`--json` 给出 stdout、stderr、执行身份、超时和输出截断等完整结果。默认超时 10 秒、最多 60 秒。标准输入关闭，没有交互终端。`adb install`、`adb push` 等电脑端命令不是手机 shell 命令；安装用 `install-apk.sh`，普通文件仍走 MCP 文件工具。投屏、录屏、交互式 adb 终端仍使用原有 adb 通道。

`android.sh` 默认先只读检查远程安装能力，可用就构建并远程更新；远程能力不可用且尚未开始上传时，才走原有本地 adb 安装。`--remote` 强制只用远程，`--adb` 强制本地安装。遇到未验证机型时停止，不回退 adb；上传或提交开始后发生失败，也不会转到 adb 重装。首次安装手机工位需要已有本地安装途径。手机工位的远程更新沿用已有权限和配对；原有 adb 安装流程还会授系统设置、通知、所有文件访问等权限。

## 安装过程与结果

电脑用 Python 标准库读取 APK manifest 中的包名、版本和 split；无需额外安装 APK 解析库。AAB、APKS 压缩包不能直接传入，先生成或解包得到完整 APK 集合。一次接受 1 到 100 个 APK，必须属于同一个包名和版本，有且仅有一个 base，各 split 不重复。

上传每块最多 512 KiB，写入 `Download/手机工位/inbox/install-<任务编号>/`，追加时带上一次返回的 `targetVersion`。手机用 Shizuku 将文件复制到权限为 0700 的 `/data/local/tmp/phone-station-install/<任务编号>/`，逐个核对 SHA-256，同时校验安装脚本和任务记录，全部通过才提交。安装走当前 Android 用户的 `pm install-create -r`、`install-write`、`install-commit`，单个 APK 和 split APK 共用同一个安装 session。

包管理器负责检查签名、版本、ABI 和系统限制。签名冲突或版本回退会返回原始安装错误，不卸载应用、不清空数据，也不默认开启降级或批量授权。未提交成功的 session 会尽量 abandon。

安装任务用 `setsid` 脱离单次 shell 的进程组，最长运行 180 秒。因此手机工位替换自身时，即使 Android 停止应用进程、Shizuku 清理旧 UserService，安装仍可完成。启动端等待任务的 `started` 标记后才返回，避免任务来不及脱离进程组就被清理。替换成功后，助手限时请求启动 MCP 前台服务，并默认执行 `am start -W` 打开手机工位主页；`--no-open` 只启动后台。服务启动请求、主页启动请求分别记录退出码；助手还核对应用 PID 已更换、MCP 已进入前台服务状态，连续两次观察到同一新 PID 才记录恢复成功。启动 Intent 带本次任务编号，40 版应用在进程内分别确认服务与界面收到该编号；助手通过不返回令牌的 `--install-recovery` 诊断核对，普通手动打开或其他任务的标记不算本次自动拉起。40 版 MCP 启动入口也会按原偏好恢复无线调试保持服务，不依赖先打开主页；`MY_PACKAGE_REPLACED` 广播仍作为另一路恢复入口。完整自动恢复确认要求目标手机工位为 40 版或更新版。主应用恢复后，电脑只查询原任务，不再次安装或重放启动命令。其他应用的 APK 仅安装和校验，不自动打开。

完成后清理 APK 和共享上传目录，保留任务记录、安装输出和退出码。最终把 `pm path` 返回的已安装 APK 逐个计算 SHA-256，与电脑的 APK 集合比较；即使版本号相同也能核对实际安装的内容。`verified: true` 只表示 APK 内容一致；整个流程完成要看 `completed: true`。手机工位自更新还通过已配置中继核对 MCP 实际返回的版本和远程在线状态，不能只凭启动命令返回 0 或用户手动打开后连通判定自动恢复成功。

- `installed` 且 `completed: true`：安装校验和所需恢复检查都完成。其他应用不要求恢复手机工位。
- `running`：包管理器尚未完成；`recovering`：APK 已核对，仍在等待自动启动或远程恢复。
- `installed_restart_failed`：APK 已安装，助手记录的启动检查失败；保留 `recovery` 中的请求退出码、进程及服务结果。
- `installed_restart_unconfirmed`：APK 已核对，但启动记录缺失、旧任务没有恢复验证，或等待远程恢复超时。之后手动打开不会把助手已记录的失败改成自动成功。
- `failed`：包管理器安装失败，附原始输出；`unconfirmed`：已安装 APK 与提交内容尚未一致。

电脑等待安装结果默认 210 秒，`--wait-seconds <秒>` 可设为 1–600 秒；这个等待时间不延长手机助手的 180 秒运行上限。失败或未确认时保留原任务编号，先查询。CLI 只有 `completed: true` 才返回 0；运行或恢复中的只读 `--status` 返回 75，其余不完整结果返回非 0。Mac 安装入口也区分“安装完成”和“应用已启动、远程已恢复”。

进程被强杀、手机重启或中继断线可能让结果无法确认；超时不会撤销包管理器已经接受的提交，也不会自动重装。Mac 19 起，只在 429 带有 `X-Phone-Station-Not-Queued: 1` 时以相同操作 ID 和请求体等待空闲；网络应答丢失不再自动重传，防止依赖尚未核实的外部中继去重。电脑不在结果未知时生成新 ID 重放安装。

## 维护

实现为 `lib/remote_ops.py` 和网关的 `remote-call` 子命令。`scripts/build-mac-app.sh` 将两个入口及 Python 实现一并打入菜单栏 App；App 的安装按钮沿用 `android.sh` 的远程优先策略。

```bash
python3 lib/test_remote_ops.py
python3 -m py_compile lib/remote_ops.py
go -C lib/remote-gateway test -race ./...
zsh -n scripts/android.sh scripts/install-apk.sh scripts/shell.sh scripts/build-mac-app.sh
```

非 root 手机重启后仍需重新启动 Shizuku；这不是远程中继能代替的权限。此流程继续只在荣耀 PGT-AN20（Android 15）上开放已验证的设备操作。

## 2026-10-02 真机验证

全程使用 `remote-call` 直连现有中继，没有运行 adb 连接、转发或安装命令，也没有改无线调试开关或配对资料：

- `shell.sh --status` 返回已授权，`id` 实际返回 `uid=2000(shell)`。实机暴露了后台探测与业务请求竞争导致的 429，现以同一操作 ID 等待空闲。
- 隔离测试包成功新装 1 版，上传 1307039 字节 APK 后升级到 2 版，再以 base + feature split 升级到 3 版。每次 `verified: true`，安装结果的 SHA-256 集合与电脑一致。
- 尝试用 1 版更新已安装的 3 版，返回 `INSTALL_FAILED_VERSION_DOWNGRADE`；之后只读核对仍为 3 版，没有卸载解决错误。测试应用随后通过同一远程 shell 卸载。
- 手机工位 38 版经远程完成自身替换，应用 PID 从 29420 变为 21261；远程恢复后 `phonestation_mcp`、`phonestation_remote` 仍为 1，Shizuku 为 granted、UID 2000。已安装 APK 与这次提交的包逐字节一致。安装任务编号 `741f20176a674712b17036d7fc6a6c4e` 可用 `--status` 复核。
- Go race 测试、7 项远程操作测试、Mac 全量构建及 Android 构建中现有测试通过。测试覆盖 UTF-8/UTF-16 manifest、split 集合、分块写入的版本检查、断线后只查询原任务、相同版本下的文件核对、失败 session 清理，以及未知机型不得回退 adb。

本次未重启手机；重启后的 Shizuku 启动限制仍按官方行为说明处理。

## 40 版自动恢复验证

2026-10-02，修复安装完成后启动检查的缺口。39 版曾由用户手动重开，因此它的 APK 校验与随后连通不能作为自动恢复证据。40 版使用本次任务标记、新 PID、MCP 前台状态和实际远程版本分别核对；默认打开主页，`--no-open` 只恢复后台。电脑只读查询不会再次安装或启动，旧任务没有恢复记录时也不会报整个流程完成。

在 PGT-AN20 上经现有中继进行了两轮，不使用 adb 安装，也没有在安装助手之外执行打开主页命令：

- 后台模式，任务 `76495326fe744d8eaa0bdd63c7190582`：39 升级到 40，PID 从 27391 变为 14564。`serviceSeen: true`、`activitySeen: false`，MCP 进入前台，保持服务也按原偏好恢复。远程实际返回版本 40、在线状态为 true，APK 核对一致，`completed: true`。
- 默认模式，任务 `4a02972283d64f2a9e04e98a3a245001`：以同一 40 版 APK 完成自身替换，PID 从 14564 变为 15707。助手 `am start -W` 返回 `Status: ok` 和主页组件，服务与界面都确认本次任务标记，远程恢复为版本 40；APK 核对一致，`completed: true`。

MCP 和远程开关均保持 1，Shizuku 授权沿用，已有中继资料保留。12 项远程操作测试、20 项 Android 测试、Go race 检查、Mac 全量构建、Python 编译检查、zsh 语法与差异空白检查通过。测试覆盖被拒绝的启动、未更换的进程、无本次任务标记的手动打开、错误的运行版本、远程仍离线、恢复超时，以及只查询原任务、不重放安装或启动。

安装助手写入启动 PID、boot ID、开始时间与 180 秒单调期限。状态查询只有在同次开机、期限内且进程命令确为该任务助手时才报运行中；助手被杀、手机重启或旧任务缺记录时返回结果未知，并核对当前已安装 APK 的 SHA-256。启动恢复未确认且助手已结束时保留“已安装”事实，不永久等待。正常超时先 TERM 并给 5 秒清理窗口，再 KILL；启动锁阻止同一任务再次执行。

Mac 在启动安装前保存唯一任务编号（`--job-id`），重启后仍可在「更多」查询原任务或复制编号。查询只检查状态，不重新安装；本地 adb 安装成功后清除不适用的远程编号。服务不可达时保留编号和未知状态。

新版手机提供 `station_shell_start` / `station_operation_status`，CLI 自动协商使用后台任务，旧版仍可用同步工具完成升级。提交前生成唯一编号；提交应答丢失后只查询同一编号。执行结果与 shell 编号一同返回，未知结果提示 `--job-status`。后台任务不把常规子进程变成常驻服务，仍有同样的执行期限、输出上限与进程组清理。
