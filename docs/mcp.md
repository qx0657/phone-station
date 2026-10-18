# 手机工位 MCP

当前调用走统一链路 `/v2/link`。已安装手机上的 `127.0.0.1:8765/mcp` 返回 404。电脑仍访问本机网关 `127.0.0.1:18765/mcp`，adb 转发使用 `18766`。主令牌 `tools/list` 是 18 个没有 `station_` 前缀的名称。

2026-10-18 把当时的完整服务说明转入 [history/mcp-service.md](history/mcp-service.md)。中继 5 合约、控制长连接和独立屏幕 WSS 分别在 [history/relay-contract.md](history/relay-contract.md)、[history/remote-control.md](history/remote-control.md)、[history/remote-screen.md](history/remote-screen.md)。当前协议见 [link-v2.md](link-v2.md)。下面只留仍在使用的入口和边界。

## 启动

在仓库根目录运行 `./scripts/mcp.sh`。它启动本机网关 `127.0.0.1:18765`。有 adb 时整理现有连接并完成握手；没有 adb 时，已在线的远程链路仍可返回网关地址。`mcp.sh status --safe` 只读已有网关且不输出令牌。普通 `status` 会刷新 adb 转发和手机令牌，不是无副作用采集。默认启动、普通 status 和内部 snapshot/watch 可能包含授权头，不能贴到聊天、日志或仓库。

`./scripts/mcp.sh stop` 要求 adb 在线，并要手机确认后才停服务。仅远程时在手机上关闭。配置保存成功与实际连接确认分开。

两端中继地址、指纹和令牌分别保存。令牌通过隐藏字段或 stdin 传入，已有令牌不回填。角色令牌、手机临时令牌、网关令牌和签名密钥留在私密配置、钥匙串或 Keystore。

## 监听和令牌

手机本地工具只绑定 `127.0.0.1`。本机网关只绑定 `127.0.0.1:18765`。

手机本地令牌只经受 DUMP 权限保护的诊断读取，不从 logcat 读取，也不写入 `Settings.Secure`。网关生产路径走设备密钥和 `/v2/link`，不再把手机临时令牌当成调用凭证。远程凭据留在钥匙串。令牌不出现在界面明文里。

## Shizuku shell

Shell 使用已运行并授权的 Shizuku 13+。没有服务或授权时拒绝，不回退到应用 UID，也不擅自启动或申请权限。非 root 手机重启后要重新启动 Shizuku，原授权可保留。

`station_shell_status` 只读。新客户端用 `station_shell_start` 提交后台任务，再用原编号查询；`station_shell_exec` 留给旧客户端。单次命令默认 10 秒，最长 60 秒，标准输入关闭。提交应答丢失后只查询原编号，不重新执行。优先查询原编号；`missing` 或 `result_unknown` 不证明没执行。

链路里的 `shell.start` 跑完后直接带回 `exitCode` 和输出。这条结果本身就是完成，不能再按旧任务库查询，否则会得到 `missing` 并在上传前停下。旧任务库里真正的 `missing` 仍然停下，不改提交第二次。参数表和进程组规则见 [历史服务说明](history/mcp-service.md#shizuku-shell)。

没有本地 adb 连接时，远程安装走 `install-apk.sh` 或 `android.sh --remote`。adb 列表里已经有 device 时走本地 `android.sh` / `android.sh --adb`。远程上传或提交开始后失败，不改走 adb 再装一次。

## 请求协调与结果恢复

后台任务使用原编号。同编号同参数返回原回执，不同参数拒绝。查询不触发执行。应用重起后，未完成任务是 `result_unknown`，不自动重提。文件修改在本地链路没有单独回执；远程侧的旧回执与本地编号不通用。结果不明时先核实，不自动重放。

剪贴板交换、通知正文接收、打开文件、保持亮屏和设置修改都不作为只读探测，也不自动重放。`station_notification_poll` 是正文接收与心跳。

网关队列、回执保留时间和旧工具名的细节见 [历史服务说明](history/mcp-service.md#请求协调与结果恢复)。
