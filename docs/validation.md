# 本机回归与验证范围

运行 `./scripts/check.sh`。依赖 Python 3.10+、zsh、Go 1.23+、JDK 17+ 和 macOS Command Line Tools。允许通过 `JAVA_HOME` 指定 JDK；作者机器上未指定时使用已有的 Homebrew OpenJDK。`--core` 明确跳过 Swift，其余依赖仍需要。不自动安装工具。

检查过程使用临时目录，包含：

- `AGENTS.md` 与 `CLAUDE.md` 镜像、Claude/Grok 项目 Skill 软链接。
- shell 与 Python 语法；自动发现 `lib/` 和 `.agents/` 下的 Python 回归。
- Go race 和临时网关构建。配对测试使用这次编出的网关和模拟钥匙串，不依赖旧构建产物。
- `lib/android/test/*Test.java` 的全部纯 Java 回归，不需要 Android SDK。
- Swift 测试与 Mac 全量源码编译。新增 `*Test.swift` 必须在 `lib/check.py` 登记依赖，漏登会失败。

任何一步失败都会返回非零；完整检查不会因为缺工具而静默略过。`.github/workflows/check.yml` 在 push、pull request 或手动触发时执行同一命令，只授予仓库读取权限，不带部署凭据。Action 配置依据 [checkout](https://github.com/actions/checkout)、[setup-python](https://github.com/actions/setup-python)、[setup-java](https://github.com/actions/setup-java) 和 [setup-go](https://github.com/actions/setup-go) 官方说明。

该入口不连接手机、不启动真实网关、不使用发布签名。Android XML 资源、Shizuku 依赖与完整 APK 用 `lib/android/build.sh` 验证；Mac 可安装包用 `scripts/build-mac-app.sh`。真机权限、系统生命周期、公网网络切换与实际安装恢复需要在对应设备操作的授权范围内另行验证。

## 2026-10-03 第二轮检查

本机完整检查通过：47 项 Python 测试、28 个 Java 测试程序、12 个 Swift 测试程序、Go race，以及 Mac 全量源码编译。GitHub 上的托管 CI 尚未运行，不能用本机结果代替。另通过 Android 59 APK 与 Mac 18 完整打包，Mac 包签名及随包网关、MCP 脚本一致性核对通过。此次没有部署或操作手机。

修正了三类已确认问题：

- 截图去重不再沿相似链合并；直接匹配最终保留图，跳过损坏/未知界面文件，并验证候选归档。详情见 [截图文档](screenshot-cleanup.md)。
- Mac 有限时任务不再被 stdin 管道写入卡住，超时清理同一进程组内的子进程。详情见 [Mac 文档](mac-app.md)。
- MCP 增加不输出令牌的状态与调用入口；Skill 改为后台任务优先、按原编号恢复，并纠正通知显示方式和截图计算位置的旧说明。详情见 [MCP 文档](mcp.md)。

第二轮留下的三个设计边界是中继去重与恢复、客户端权限和亮屏原值恢复。后续整改如下。

## 2026-10-03 设计边界整改

- Android 60 统一 adb 广播与 MCP 的亮屏控制，手机先持久保存原设置，再写系统；重复开启、手动修改、部分失败和下次恢复有明确语义。Mac 19 本地状态同时核对息屏时间和充电掩码。见 [亮屏恢复](remote-controls.md#亮屏原设置恢复)。
- Mac 网关新增独立令牌的状态、文件读、文件写与 shell 权限，按整个 RPC 批次预检，过滤工具发现，撤销即时作用于后续鉴权。主令牌和既有 Mac 功能保留；未自动签发真实令牌。见 [客户端权限](mcp-clients.md)。
- 远程应答丢失不再自动重发，429 必须明确确认未入队才重试；两端禁止中继重定向。新增隔离中继的可执行验收及其离线自测，能识别模拟重启丢失回执。见 [中继合约](relay-contract.md)。

完整回归通过：50 项 Python 测试、28 个 Java 测试程序、12 个 Swift 测试程序、Go race、隔离中继检查器自测及 Mac 全量源码编译。Android 60 APK 与 Mac 19 完整打包通过，Mac 签名验证通过；phone-mcp Skill 结构校验通过。未运行 GitHub 托管 CI。

本轮另修正 HTTP 回归测试的临时目录清理竞态：先等待后台任务的回执完成，再删除测试目录，避免后台原子替换与测试删除并发。

公网服务端源码和隔离部署配置尚未取得，因此未验证或修改现网的去重、持久化和重启行为。服务端还需故障注入覆盖强杀、磁盘写满、保留期和多端竞争。亮屏恢复需要真机测试权限撤销、进程重启与保留数据升级；本次只做本机回归和打包，不安装、不改变手机状态。
