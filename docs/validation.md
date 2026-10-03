# 本机回归与验证范围

运行 `./scripts/check.sh`。依赖 Python 3.10+、zsh、Go 1.23+、JDK 17+，完整 Mac 检查还需要 macOS 26 或更新 SDK。可通过 `DEVELOPER_DIR` 选择兼容的 Xcode / Command Line Tools，入口先核对 SDK；编译目标仍是 macOS 13，macOS 26 API 保留运行时可用性判断。CI 从运行器已安装的 Xcode 中选择兼容 SDK，不依赖默认旧版。允许通过 `JAVA_HOME` 指定 JDK；作者机器上未指定时使用已有的 Homebrew OpenJDK。`--core` 明确跳过 Swift，其余依赖仍需要。不自动安装工具。

检查过程使用临时目录，包含：

- `AGENTS.md` 与 `CLAUDE.md` 镜像、Claude/Grok 项目 Skill 软链接，以及当前/历史文档的本地文件链接与锚点。
- shell 与 Python 语法；自动发现 `lib/` 和 `.agents/` 下的 Python 回归。
- 两个 Go 模块的 race 检查、真实中继持久化与 TLS 联合合约，以及临时网关构建。配对测试使用这次编出的网关和模拟钥匙串，不依赖旧构建产物。
- `lib/android/test/*Test.java` 的全部纯 Java 回归，不需要 Android SDK。
- Swift 测试与 Mac 全量源码编译。新增 `*Test.swift` 必须在 `lib/check.py` 登记依赖，漏登会失败。

任何一步失败都会返回非零；完整检查不会因为缺工具而静默略过。`.github/workflows/check.yml` 在 push、pull request 或手动触发时执行同一命令，只授予仓库读取权限，不带部署凭据。Action 配置依据 [checkout](https://github.com/actions/checkout)、[setup-python](https://github.com/actions/setup-python)、[setup-java](https://github.com/actions/setup-java) 和 [setup-go](https://github.com/actions/setup-go) 官方说明。

该入口不连接手机、不启动真实网关、不使用发布签名。Android XML 资源、Shizuku 依赖与完整 APK 用 `lib/android/build.sh` 验证；Mac 可安装包用 `scripts/build-mac-app.sh`。真机权限、系统生命周期、公网网络切换与实际安装恢复需要在对应设备操作的授权范围内另行验证。

## 当前验收与发布

本轮结果和三端状态见 [发布记录](releases.md)。历次检查见 [历史验证记录](history/validation.md)。新中继使用仓库内源码执行持久化单测、故障注入和 TLS 联合合约；检查器自测仍保留。生产实例的部署与恢复另行验收。
