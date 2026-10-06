# 本机回归与验证范围

运行 `./scripts/check.sh`。依赖 Python 3.10+、zsh、Go 1.23+、JDK 17+，完整 Mac 检查还需要 macOS 26 或更新 SDK。可通过 `DEVELOPER_DIR` 选择兼容的 Xcode / Command Line Tools，入口先核对 SDK；编译目标仍是 macOS 13，macOS 26 API 保留运行时可用性判断。CI 从运行器已安装的 Xcode 中选择兼容 SDK，不依赖默认旧版。允许通过 `JAVA_HOME` 指定 JDK；作者机器上未指定时使用已有的 Homebrew OpenJDK。`--core` 明确跳过 Swift，其余依赖仍需要。不自动安装工具。

检查过程使用临时目录，包含：

- `AGENTS.md` 与 `CLAUDE.md` 镜像、Claude/Grok 项目 Skill 软链接，以及当前/历史文档的本地文件链接与锚点。
- shell 与 Python 语法；自动发现 `lib/` 和 `.agents/` 下的 Python 回归。
- 网关、中继、Android PTY、屏幕媒体四个 Go 模块的 race 检查与 Android/arm64 交叉编译、真实中继持久化与 TLS 联合合约，以及临时网关构建。Linux CI 另验证真实 PTY、目录保持、Ctrl-C、中文与窗口尺寸。配对测试使用这次编出的网关和模拟钥匙串，不依赖旧构建产物。
- `lib/android/test/*Test.java` 的全部纯 Java 回归，不需要 Android SDK。
- 实际 Java WSS 客户端与 Go 中继的本机 TLS 联合验证，覆盖错误 SPKI、原操作执行与结果确认；证书、令牌、状态目录均为临时测试数据。Java-WebSocket 与 SLF4J 固定依赖首次从 Maven Central 获取并校验 SHA-256，缓存放在忽略的 `build/third_party/websocket/`，后续复用同摘要缓存。
- Swift 测试与 Mac 全量源码编译。新增 `*Test.swift` 必须在 `lib/check.py` 登记依赖，漏登会失败。

任何一步失败都会返回非零；完整检查不会因为缺工具而静默略过。`.github/workflows/check.yml` 在 push、pull request 或手动触发时执行同一命令，只授予仓库读取权限，不带部署凭据。Action 配置依据 [checkout](https://github.com/actions/checkout)、[setup-python](https://github.com/actions/setup-python)、[setup-java](https://github.com/actions/setup-java) 和 [setup-go](https://github.com/actions/setup-go) 官方说明。

该入口不连接手机、不启动真实网关、不使用发布签名。Android XML 资源、Shizuku 依赖与完整 APK 用 `lib/android/build.sh` 验证；Mac 可安装包用 `scripts/build-mac-app.sh`。真机权限、系统生命周期、公网网络切换与实际安装恢复需要在对应设备操作的授权范围内另行验证。

控制长连接的验收要区分手机 WSS 就绪、实际 MCP 调用、事件主题被接受与真实变化到达。空订阅或未知 clientId 被拒绝可以验证传输和授权边界，不能证明系统剪贴板回调或通知即时同步已通过；相关实测边界见 [远程控制与事件](remote-control.md#发布和验证边界)。

## 界面改动验收

按受影响范围选取下列情形，状态逻辑用回归验证，外观和点击路径另行核对；不把每次文案或间距调整扩大成全功能验收。

| 改动范围 | 容易遗漏的情形与预期 |
| --- | --- |
| 连接与 MCP 状态 | 仅本地、仅远程、双通道、单路故障、确认中；暂停／关闭覆盖旧在线状态，未配置且未使用的远程不报故障 |
| 通知聚合 | 两个方向分别开启、全部关闭、单方向缺项、等待 Mac 接收；不能因另一方向正常而掩盖问题 |
| 开关与恢复 | 缺权限仍保留选择，保存失败不报成功；暂停中修改与恢复、独立切换本地／远程不误改其他偏好 |
| 入口与反馈 | 从首页、设置、缺项提示和旧深链进入，处理后返回实际上一层并刷新；保存反馈紧邻操作，恢复不重放旧任务 |
| 常驻通知 | 收起保留单路故障／确认提示，展开区分连接、MCP 可用性与保持选择；问题操作直达对应设置，正常状态没有无用按钮；主题／字号重建与划掉恢复不改变服务选择 |
| 布局 | 浅深色、大字号／窄屏、长状态文案和折叠重建；关键名称与处理入口可见，图标有文字或辅助功能名称 |
| 语言与主题 | 两端分别核对中英 × 浅深色、跟随系统、重启后选择保留；返回旧页面、折叠与非敏感表单草稿保留，通知文案刷新；切换不改变功能选择、授权或任务编号，文件名与通知正文保留原文 |
| 应用弹窗 | 选择、权限提示与清除确认的中英 × 浅深色；勾选可读、键盘焦点可见，大字号／窄屏下能到达所有按钮；取消／返回／遮罩不执行操作，Escape 先关闭弹窗；页面切换、窗口关闭与 Activity 重建不残留弹窗，底层页面不能穿透操作，清除只在确认后执行 |

这些是后续验收情形，不是已通过的记录。具体设备操作仍在对应授权内执行；构建、安装校验和连接恢复不替代真机视觉与点击验收，实际结果和未验收项写入相应历史记录。

## 当前验收与发布

本轮结果和三端状态见 [发布记录](releases.md)。历次检查见 [历史验证记录](history/validation.md)。新中继使用仓库内源码执行持久化单测、故障注入和 TLS 联合合约；检查器自测仍保留。生产实例的部署与恢复另行验收。
