# remote-controls 历史验证记录

以下为迁移前的日期、版本、测试与部署记录，叙述中的“当前”“本轮”均指当时。不能据此判断今天运行的版本或把模拟测试当成生产验收。当前规则见 [remote-controls.md](../remote-controls.md)，部署状态见 [发布记录](../releases.md)。

## 验证

构建包含 `ScreenCaptureTest`（UUID、重复编号、路径链接、失败清理）、MCP 工具派发与参数检查，以及 Mac 的断线、未知机型、应答丢失后只读核实、分块版本变化和图片损坏检查。

2026-10-03，在荣耀 PGT-AN20（Android 15）、没有本地 adb 的远程模式下验证：

- Android 58 经远程安装任务 `2451f05696054019b8bc77c3c20b3e8e` 完成 APK 校验、自身替换和后台恢复，实际远程版本为 58，`completed: true`。Shizuku 为已授权的 shell UID 2000。
- `station_controls_status` 返回三项可用、实际机型 PGT-AN20、闪光灯上限 4；只读状态没有开灯或截屏。
- 单次截屏传回 251896 字节 PNG，逐页核对版本与 SHA-256，通过图片查看器确认是手机当前界面；release 后原文件已不存在。
- 亮屏开关关闭后读到 60000 / 0，重新开启后恢复原来的亮屏状态。
- 手电筒开启后，调用结束、等待 3 秒和一次独立 shell 执行完成后仍是开着；最后恢复为原来的关闭状态。
- `/Applications/手机工位.app` 已更新为 Mac 0.1.0 (14)，旧 App 保存在忽略目录 `build/.phone-station/backups/remote-controls-before-58-20261003-141517.app`。重新启动网关后，原生 AX 与视觉检查确认远程首页三个控件可用，投屏、录屏与跟随声音仍禁用。通过首页分别操作亮屏与手电筒，实际开关与完成提示正确；截图保存到 Mac 的 Pictures/scrcpy，显示「截图已保存，并在预览中打开」。测试结束后保持亮屏开、手电筒关。
- Mac 全量构建通过 Go race、Python 既有检查、Android 25 项测试、Swift 既有测试与新增远程控件测试；zsh 语法、差异空白和 AGENTS/CLAUDE 镜像检查通过。

最终打包后，以任务 `99a81991be6f4671b79d273b48ac8805` 对齐手机实装 APK 与 Mac 随包 APK，SHA-256 均为 `0a94b0b377a7d5a3298f7eea5c377e66cda873e2023cfc73582433998093865a`，后台启动与远程恢复确认完成。最后只读核对三项仍可用，保持亮屏开、手电筒关。
