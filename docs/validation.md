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

剩余设计边界：公网中继服务端源码仍在仓库之外，其操作去重和重启恢复需要纳入同一交付或独立合约测试；客户端目前共用能力完整的 Bearer，按客户端区分文件读取、修改和 shell 权限尚未实现；保持亮屏关闭仍固定写回 60 秒与充电掩码 0，未保存用户原值。这些没有被本轮测试证明或改变。后续若恢复原息屏设置，需要先统一 adb 脚本和 MCP 的所有权与持久化，避免两个入口互相覆盖快照。
