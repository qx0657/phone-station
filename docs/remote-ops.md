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

`shell.sh` 保留命令退出码，超时返回 124；`--json` 给出 stdout、stderr、执行身份、超时和输出截断等完整结果。默认超时 10 秒、最多 60 秒。标准输入关闭，没有交互终端。Mac 0.1.0 (21) 起，「adb 命令」菜单也支持远程执行保存的 `shell …`，命令期限固定为 15 秒；机型、Shizuku 状态及任务回执规则与 CLI 一致，应答丢失后在菜单中查询原任务。`adb install`、`adb push` 等电脑端命令不是手机 shell 命令；安装用 `install-apk.sh`，普通文件仍走 MCP 文件工具。本地投屏、录屏和 adb 终端使用原有 adb 通道；Mac 23 的远程实时投屏与录屏见 [remote-screen.md](remote-screen.md)；远程交互终端使用下面的独立会话入口。

`android.sh` 默认先只读检查远程安装能力，可用就构建并远程更新；远程能力不可用且尚未开始上传时，才走原有本地 adb 安装。`--remote` 强制只用远程，`--adb` 强制本地安装。遇到未验证机型时停止，不回退 adb；上传或提交开始后发生失败，也不会转到 adb 重装。首次安装手机工位需要已有本地安装途径。手机工位的远程更新沿用已有权限和配对；原有 adb 安装流程还会授系统设置、通知、所有文件访问等权限。

## 远程交互终端

手机工位 61 与 Mac 22 起，只有远程连接时可在「adb 命令 → 在终端中打开远程 shell」进入系统「终端」。本地 adb 在线时该入口仍打开已有的 adb shell。CLI 固定使用远程中继：

```bash
./scripts/terminal.sh
./scripts/terminal.sh --resume <32位会话编号>
./scripts/terminal.sh --close <32位会话编号>
```

手机使用独立 Shizuku UserService 和 PTY，保留当前目录、环境变量与交互输入，支持方向键、Tab、Ctrl-C、持续日志和窗口缩放。初始目录 `/`，`TERM=xterm-256color`。Ctrl-]、关闭终端窗口或输入 `exit` 结束会话；关闭会清理同一终端会话的前台与后台进程，主动用 `setsid` 脱离会话的进程不在清理范围内。没有 Shizuku 授权时拒绝操作，不启动 Shizuku、不请求权限、不回退应用 UID 或 adb。

创建前将唯一编号保存到 Mac `~/.phonestation/terminals/`，目录 0700、回执 0600，不保存令牌、输入正文或输出。手机复用私有的后台任务回执去重创建；`station_terminal_read` 查询原编号，不绑定或创建服务。短暂网络中断时持续查询同一会话，Mac 重启后可 `--resume`；手机应用或 Shizuku 重启后会话终止，不重新创建或重发命令。`missing` 或 `lost` 不能证明之前的输入没有执行。

输入携带从 0 连续递增的序号，Mac 发送前持久保存待确认序号。应答丢失时只读取 `nextInputSequence`，确认收到后才继续；未确认时暂停输入，可 Ctrl-] 关闭。手机也拒绝跳号或把同一序号用于不同输入。输出按原始字节传输，读取不消耗缓冲区；每次最多 32 KiB，手机每会话保留最近 256 KiB，超过缓冲会明确显示遗漏字节。窗口尺寸为 20–500 列、5–200 行。

最多同时 2 个会话。定期读取维持租期；没有请求 60 秒，或会话达到 1 小时后终止并清理，不作为后台常驻服务使用。原有中继协议无需更新，输入和输出都使用快速返回的 MCP 请求，统一排队；传输延迟会影响逐键反馈，其他长操作可能暂时阻塞终端。全部 `station_terminal_*` 工具要求网关的 `shell` 客户端权限，不能用状态或文件只读令牌查看终端输出。

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

## 任务中断与恢复

安装助手写入启动 PID、boot ID、开始时间与 180 秒单调期限。状态查询只有在同次开机、期限内且进程命令确为该任务助手时才报运行中；助手被杀、手机重启或旧任务缺记录时返回结果未知，并核对当前已安装 APK 的 SHA-256。启动恢复未确认且助手已结束时保留“已安装”事实，不永久等待。正常超时先 TERM 并给 5 秒清理窗口，再 KILL；启动锁阻止同一任务再次执行。

Mac 在启动安装前保存唯一任务编号（`--job-id`），重启后仍可在「更多」查询原任务或复制编号。查询只检查状态，不重新安装；本地 adb 安装成功后清除不适用的远程编号。服务不可达时保留编号和未知状态。

新版手机提供 `station_shell_start` / `station_operation_status`，CLI 自动协商使用后台任务，旧版仍可用同步工具完成升级。提交前生成唯一编号；提交应答丢失后只查询同一编号。执行结果与 shell 编号一同返回，未知结果提示 `--job-status`。后台任务不把常规子进程变成常驻服务，仍有同样的执行期限、输出上限与进程组清理。

## 维护

实现为 `lib/remote_ops.py` 和网关的 `remote-call` 子命令。`scripts/build-mac-app.sh` 将两个入口及 Python 实现一并打入菜单栏 App；App 的安装按钮沿用 `android.sh` 的远程优先策略。

```bash
python3 lib/test_remote_ops.py
python3 -m py_compile lib/remote_ops.py
go -C lib/remote-gateway test -race ./...
zsh -n scripts/android.sh scripts/install-apk.sh scripts/shell.sh scripts/build-mac-app.sh
```

非 root 手机重启后仍需重新启动 Shizuku；这不是远程中继能代替的权限。此流程继续只在荣耀 PGT-AN20（Android 15）上开放已验证的设备操作。

## 历史验证

历次真机安装与自动恢复验证见 [历史验证记录](history/remote-ops.md)。
