# Mac 远程截屏、亮屏与手电筒

Mac 0.1.0 (14)、Android 58 起，首页这三项在只有已配置的远程中继时也可使用。`RemoteControlsSession` 只在没有已验证 adb 序列号时接管它们；本地操作继续使用原脚本。投屏、录屏、灯光跟随声音与 adb 命令仍需要本机 adb。

## 可用状态

`station_controls_status` 只读返回实际型号、保持亮屏、手电筒状态与各项是否可用。Mac 每 4 秒检查一次，不截屏、不开灯、不申请权限；手电筒状态读取可以连接只读的 Shizuku UserService。只有返回并核实为 PGT-AN20 的型号才启用设备操作，手机端也核实型号。旧手机版本不支持新工具时，控件禁用并提示更新。

保持亮屏继续调用 `station_stay_awake`，需要已授予写系统设置权限，不需要 Shizuku。开启和关闭仍写同一对息屏、充电常亮设置。截屏需要所有文件访问，以及已经运行、授权且具备 shell/root 身份的 Shizuku 13+。手电筒需要同样的 Shizuku；相机被占用时显示实际不可用原因。

控件忙时禁用，断线立即停止新操作。开关请求失败后丢弃未确认状态，只读核对实际值。没有自动重放，手机可能已执行但应答丢失时不会再次开关。一个请求中途出现「确认连接中」不取消正在执行的操作。

## 单次截图

`station_screen_capture` 要求新的 UUID `requestId`。手机用普通文件工具的路径规则创建 `Download/手机工位/.captures/<UUID>.png`，已存在文件时拒绝覆盖。Shizuku 执行固定的 `/system/bin/screencap -p`，不经电脑 adb。最多执行 15 秒；执行结束后核对 PNG 签名与 32 MiB 上限，返回绝对路径、size、targetVersion 和 SHA-256。手机执行失败会清理本次文件。

Mac 用 `station_file_read_bytes` 分块下载，每页最多 65536 字节，核对偏移、字节数、版本和最终 SHA-256，并确认可解码为 PNG。完整图片才原子写入 `~/Pictures/scrcpy/<时间>-remote-<编号前六位>.png`，打开预览、刷新最近文件，再用 `station_screen_capture_release` 删除本次手机临时文件。清理失败不会把已保存的图片报成截图失败。

截屏应答丢失或下载中断时不重拍，Mac 提示本次 UUID。可按上述固定目录只读查找并恢复下载，完成后清理该编号。未完成下载的临时文件保留用于核实；不得不加判断地删整个目录。系统安全界面仍可能返回黑屏，不绕过安全标记。

## 持续手电筒

`station_torch` 开关通过 `ShizukuTorch` 连接独立 `TorchUserService`，Binder 只允许手机工位的 UID 调用。服务用 shell/root 身份持有 CameraManager，选后置闪光灯并读取设备最大亮度，确认 TorchCallback 的实际状态后返回。相机状态回调跟踪系统或其他应用对灯的变化。

服务在开关调用结束后继续存活，开灯不会随着普通 shell 请求结束而灭。它不执行任意 shell，也不申请相机权限。MCP 服务停止时移除该 UserService；应用进程死亡、升级或 Shizuku 服务停止后，由系统关闭本进程持有的灯。网络断开本身不会关掉已经开启的灯，重连只读状态，不自动重新开灯。

`ShellRunner` 仍会清理单次 shell 的后台任务；不要用远程 shell 起一个后台 Torch 进程来代替这个接口。灯光跟随声音继续使用原来的本地 adb 连续输入链路。

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
