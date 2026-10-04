# AGENTS.md

本文件是 ChatGPT / Codex 在本仓库工作时的指引。标记区内的主体内容与 `CLAUDE.md` 互为镜像，修改任意一方都必须同步另一方。

<!-- agent-docs-sync:start -->

## 项目与阅读入口

本仓库是手机工位，公开仓库名 `phone-station`，作者检出目录为 `~/dev/phone`。电脑通过本地 adb 或可配置公网中继访问 Android 应用自己的 MCP。脚本按自身目录寻找 `lib/`，不依赖检出路径。完整 adb 和灯光跟随声音仍要求本机 adb；Mac 23、手机 62、中继 3 起，远程实时投屏与录屏使用独立 WSS，见 `docs/remote-screen.md`。

给人看的用法在 [README.md](README.md)，组件、身份和能力矩阵在 [docs/architecture.md](docs/architecture.md)。按当前任务读取对应文档，不把全部历史或全站自检当成前置步骤：

| 任务 | 入口 |
|---|---|
| 普通手机文件、剪贴板、Shizuku shell、APK 安装、远程控件 | [.agents/skills/phone-mcp/SKILL.md](.agents/skills/phone-mcp/SKILL.md)，细节按其引用读取 |
| 安装/卸下全局会话提醒 | [.agents/skills/agent-notify/SKILL.md](.agents/skills/agent-notify/SKILL.md)，事件见 [docs/notify.md](docs/notify.md) |
| 图库截图的信息价值与去重 | [.agents/skills/screenshot-cleanup/SKILL.md](.agents/skills/screenshot-cleanup/SKILL.md) |
| Mac 构建与进程、Android 权限与无线调试保持 | [docs/mac-app.md](docs/mac-app.md)、[docs/adb-keep.md](docs/adb-keep.md) |
| 中继服务端、协议与持久恢复 | [server/relay/README.md](server/relay/README.md)、[docs/relay-contract.md](docs/relay-contract.md) |
| 状态异常、任务中断、版本与发布 | [docs/troubleshooting.md](docs/troubleshooting.md)、[docs/releases.md](docs/releases.md) |
| 统一本机检查与验证边界 | [docs/validation.md](docs/validation.md) |

`.claude/skills/` 与 `.grok/skills/` 的同名目录是指向 `.agents/skills/` 的软链接，不另写一份。Android CLI 是独立开发工具，安装见 [docs/android-cli.md](docs/android-cli.md)，不是手机操作通道的依赖。`~/dev/skills/reverse-skill` 是另一份仓库，不放进本仓，也不运行其总控。

## 执行与完成

- 只使用 `main` 分支，日常修改、提交和推送都直接在 main。除非用户明确要求，不新建开发分支或 worktree；已有临时分支确认全部合入并推送 main 后才删除。
- 结合本任务与已有授权连续完成必要步骤。用户已要求修复、验证或安装时，不为同一范围重复确认；只审阅或提问时不改文件。代码检查本身不包含手机通知、截屏、剪贴板交换或安装。
- 中断后核对工作区、提交、任务回执和实际结果，再继续未完成部分。聊天标题和旧计划不能替代完成记录；有副作用的应答丢失时查询原编号，不重新提交。
- 报告分别说明源码、测试、构建、推送、设备部署与服务恢复。测试通过不代表已部署，安装内容一致不代表后台服务与远程通道已恢复；只报告已核实层次。
- 公网停机/切流先准备具体版本、影响、验证与回滚条件，再在覆盖具体动作的授权内执行。持久版接收过操作后，不能删除回执或回滚到遗忘旧编号的内存版。实际主机和私密部署资料留在个人运维仓库，通用中继源码只在 `server/relay/` 维护。

## 设备操作与授权

目前只在荣耀 PGT-AN20 / Android 15 验证。操作前机型未知时，本地用只读 `scripts/status.sh`，远程用返回 model 的 `station_controls_status` 或对应入口；不为核实机型强行连接 adb。其他型号暂停依赖机型的设备操作，说明未验证差异；源码检查、本机构建和测试可以继续。

普通文件使用 `station_file_*`；`Android/data`、`Android/obb` 和其他应用私有目录不在范围内，不能主动用 shell 绕过文件工具边界探索。删除不进回收站，修改已有文件须使用本次 stat/读取取得的 targetVersion。电脑路径不能作为手机路径。

没有明确操作要求时只调用读取类工具。打开文件、保持亮屏、提醒、写/交换剪贴板、通知正文接收及设置修改都须有对应要求。`station_notification_poll` 是正文接收与心跳，不是只读探测；应用白名单与系统通知使用权由用户手动选择，不替用户授权。`station_clipboard_exchange` 可能写入手机，不当只读探测，也不自动重放。

只有用户或上游任务明确要求本次提醒才调用 `notify.sh` / `vibrate.sh`。一轮完成、权限确认与中途询问由已安装的全局 hook 负责，不在任务末尾补跑。手动提醒与全局 hook 的区别见 README。

`mirror.sh` / `record.sh` / `screenshot.sh` / `stay-awake.sh` / `torch.sh` 会投屏、录屏、截屏、改设置或开灯；有对应要求时运行对应入口，改了某个脚本要验证时可以运行那一个。不要为查看环境运行。安全界面截屏可能黑屏，不绕过 FLAG_SECURE，也不修改系统框架。

Shell 使用已运行并授权的 Shizuku 13+，不回退到应用 UID，不擅自启动或申请权限。非 root 手机重启后须重新启动 Shizuku；原授权可保留。优先提交后台任务并查询原编号；missing/result_unknown 不证明没执行。APK 更新优先 `install-apk.sh`、`android.sh --remote`，保留数据、不自动卸载处理签名冲突；上传或提交后失败不回退 adb 重装。完整规则见 [docs/remote-ops.md](docs/remote-ops.md)。

## 连接与凭据

手机 MCP 只监听手机 `127.0.0.1:8765/mcp`；电脑固定访问网关 `127.0.0.1:18765/mcp`，adb 转发使用 18766。中继承载 MCP 和独立 WSS 屏幕会话，不能代替完整无线 adb。手机工具都在本应用服务中扩展。

AI 优先使用已连接 MCP 工具，或 `mcp.sh call` 从 stdin 提交 JSON-RPC；进程内部读取凭据。`mcp.sh status --safe` 只读已有网关且不输出令牌。需要启动服务时用 `mcp.sh >/dev/null`。默认启动、普通 status 和内部 snapshot/watch 可能包含授权头，不能直接贴到聊天、日志或仓库。普通 status 还会刷新 adb 转发与手机令牌，不是无副作用状态采集。

只有当前任务要求读取/修改手机文件、使用 MCP、配对或验证服务时才运行相关 MCP 入口。pair/unpair 通过 adb 修改两端资料；desktop/forget-desktop 只改 Mac。停止手机服务要求 adb 在线且手机确认；仅远程时在手机上关闭。配置保存成功与实际连接确认分开。

角色令牌、手机临时令牌、网关令牌、签名密钥与密码都留在私密配置/钥匙串/Keystore，不写仓库、日志或界面明文。令牌通过 stdin 或隐藏字段传入，已有令牌不回填。手机本地令牌仅经受 DUMP 权限保护的诊断读取，不从 logcat 读取。文件权限与轮换语义见 [docs/mcp.md](docs/mcp.md)。

本机无线连接地址以 mDNS 为准，路由不能走 utun；不将手机页面 VPN 地址 `172.19.0.1` 传给 adb pair/connect。PGT-AN20 已配对过，配对端口与连接端口不同，连接端口会变。已有在线设备（USB/无线）时只整理重复连接，不再建第二条；不要为验证把 adb_wifi_enabled 写成 0，以免无线会话立刻断开。

## 布局与验证

`scripts/` 是用户入口，`lib/` 是内部实现，`app/mac/` 是原生 Mac，`lib/android/` 是手机应用，`server/relay/` 是公网服务端。新用户脚本用 zsh、`set -euo pipefail`、按自身目录 source `../lib/common.sh`，支持 `-h`；不用 zsh 中与 PATH 绑定的变量名 path。需要 adb 的操作先 connect.sh，再 online_serial 获取唯一在线设备；远程 shell/安装不 connect。mDNS 发现统一用 `lib/adb_mdns.py`。

统一本机检查 `scripts/check.sh`：镜像、Skill 软链接、文档链接、shell/Python 语法、Python/Java/两个 Go 模块 race、真实中继 TLS 联合合约、Swift 回归与 Mac 全量源码编译。非 Mac 用 `--core` 并明确排除 Swift。它使用临时测试数据，不连接手机、不启动运行中的真实网关、不读发布签名。新增 Swift 测试要在 `lib/check.py` 登记；漏登失败。CI 使用同一入口。

改 Android Java/资源后运行 `lib/android/build.sh`，只在电脑构建并测试，不碰手机。完整 Mac 包用 `scripts/build-mac-app.sh`，公网包用 `scripts/build-relay.sh`。android.sh 是设备安装入口，不用于单纯构建。改 notify-sound/PlayPcm.java 或 torch/Torch.java 时运行各自 build.sh，不手改 dex，不把 R8 jar 放仓库。灯光跟随声音的机型限制、持灯与暂停规则见 [docs/torch.md](docs/torch.md)，通知与媒体音量差异见 [docs/notify.md](docs/notify.md)。

## 沉淀与镜像

验证过且下次会用到的结论写回对应位置：重复运行的步骤收为 scripts 并写 README；命令表面看不出的机制、机型差异和失败事实补到 docs 并登记索引；确需一两句话再执行的流程写项目 Skill，长事实留 docs。不为已有文档的一个坑另建 Skill，不将无关个人 Skill 放本仓。

当前规则写主文档，日期/版本限定的验证写 `docs/history/`，三端完成状态写发布回执。会变的地址、端口写查询方法，真实主机配置不复制进通用文档。只改本次结论直接相关的内容，保留未完成验证的明确边界。

修改 AGENTS/CLAUDE 前检查差异，只编辑一侧，使用 agent-docs-sync 显式方向同步镜像段，不手工复制、不按 mtime 覆盖双侧未合并内容。用户此次只审阅或明确不改文件时，结论留在回复。

<!-- agent-docs-sync:end -->
