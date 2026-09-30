# Mac 菜单栏 App

`app/mac/` 用 AppKit 创建状态栏图标和弹出面板，面板内容由 SwiftUI 绘制。`scripts/build-mac-app.sh` 用这台 Mac 已有的 Command Line Tools 把它编成 `build/.phone-station/手机工位.app`。构建目录隐藏，避免 Spotlight 把构建副本列为第二个 App；`build/` 不入库。构建时把 `connect.sh`、`disconnect.sh`、`status.sh`、`mirror.sh`、`record.sh`、`screenshot.sh`、`stay-awake.sh`、`torch.sh`、`common.sh`、`adb_mdns.py`、`torch.dex`、`torch_beat.py`、`torch-audio` 和 README 放到 App 的 Resources，脚本仍按自身位置查找 `lib/`。因此 App 复制到其他目录后，不再依赖原仓库位置。声音采集程序在构建时放进包里；源码比它新时，构建会重新编译，不在 App 运行期间往包里写文件。这个程序的部署目标是 macOS 14.2，process tap 从那里才有。构建时如果沿用 App 的 13.0，编译会失败。

构建时从 `app/mac/MakeIcon.swift` 绘制应用图标并生成 `AppIcon.icns`。图标放在 App 的 Resources，`Info.plist` 通过 `CFBundleIconFile` 引用它。

App 是菜单栏辅助程序（`LSUIElement`），没有 Dock 图标。应用内用 `Process` 传递参数数组调用随包脚本，不拼接 shell 命令；启动时给脚本补入 Homebrew、系统工具和 Android SDK 常见路径。`adb`、`scrcpy`、`python3` 仍由电脑提供。连接走 `connect.sh`，投屏、截图和录屏沿用现有脚本，确保单设备、mDNS 和 VPN 路由限制仍由同一套逻辑处理。

`NSApplication` 不会强持有 delegate。入口用静态属性保留 `PhoneStationApp`，否则进程可以运行而 `applicationDidFinishLaunching` 不执行，状态栏图标也不会建立。菜单栏入口只放模板手机符号（SF Symbol `iphone`，point size 16），不带标题。这个符号不要再设 `size`：12×12 的框会把字形裁掉，入口看起来是空的。

面板最上行的标题是手机名称。这台 PGT-AN20 显示为「荣耀 PGT-AN20」，下一行只写「无线连接」或「USB 连接」，右侧是连接状态。应用名不放在这个标题上。未连接时，下一行是原因。下面三个操作是图标在上、标题在下的方块，符号 16pt，直向留白比早先的一版短。macOS 26 的 SwiftUI `.bordered` 按钮高度是固定的，这样排时标题会被裁成一条线，所以这三个按钮自己画圆角底。投屏或录屏进行中时，对应的方块用自己的颜色标出来。面板高度跟着当前页的理想尺寸走（`NSHostingController.sizingOptions = .preferredContentSize`），不要再写死一个能装下所有页的高度。

`NSStatusItem.isVisible` 不能说明屏幕上看得到。要看按钮窗口落在不在菜单栏那一截，或看辅助功能里菜单栏项的纵坐标；这台机器上看得见的项大约是 y=5。启动日志的 subsystem 是 `com.qx0657.phonestation`。zsh 里要调用 `/usr/bin/log show`，内置的 `log` 不是这条命令。

这台 Mac 开着 Hidden Bar，自动隐藏是开的。分隔条的 preferred position 是 608，收起控制大约在 5624。本 App 曾经把 `NSStatusItem Preferred Position Item-0` 记成 5656，图标就停在收起区，窗口不在菜单栏上。控制中心只在进程创建第一个状态项时读这个值。创建之后再改偏好、再重建状态项，位置不会跟着动。启动时如果这个值缺失，或不在 80 到 600 之间，就先写成 400 再创建。400 在这台机器上落在看得见的一排里（大约 x=1257）。80 到 600 跟着这台 Hidden Bar 的分隔条；分隔条位置变了，这个范围一起改。

macOS 26 的控制中心另外按 bundle id 记一份允许名单。它在 `~/Library/Group Containers/group.com.apple.controlcenter/Library/Preferences/group.com.apple.controlcenter.plist` 的 `trackedApplications` 里，值本身又是一份二进制 plist，由 cfprefsd 缓存。直接改这个文件会被缓存写回去。2026-09-30 这台机器上，`com.qx0657.phonestation` 自己的记录是 `isAllowed` true，但 `com.openai.codex` 的记录是 `isAllowed` false，并且它的 `menuItemLocations` 里还有 `com.qx0657.phonestation`。控制中心日志会出现 `Moving host to blocked list`，窗口停在菜单栏外面（例如 `{{0, -17}}`），进程仍在。把本 App 的 bundle id 从这份已禁用记录的 `menuItemLocations` 里去掉，经带有 `group.com.apple.controlcenter` application-group 权限的进程用 `UserDefaults` 写回，再重启控制中心，图标才出现在菜单栏上。其他 App 的 `isAllowed` 保持原样。

状态读取使用 `adb devices -l`，恰好一台在线设备时再读 `ro.product.model`。只允许 `PGT-AN20` 执行设备操作。其他型号、无法识别型号、多台在线或等待 USB 授权时，面板显示原因并停用设备操作。无线连接既可能以 `IP:端口`、也可能以 `._adb-tls-connect._tcp` mDNS 名称出现在 adb 序列号里；这台 PGT-AN20 当前是后一种。打开面板时会重新读连接、息屏时间和闪光灯状态，这些读取不会改设置，也不会触发截屏、投屏、录屏或手机提醒。

投屏、录屏和「灯光跟随声音」由 App 持有，收起面板不影响它们。录屏停止时给 `scrcpy` 发 `SIGINT`，等它退出后再检查文件并显示结果；不要直接强杀，否则 MP4 可能没有完整收尾。跟随声音停止时同样发 `SIGINT`，脚本会把灯关掉。退出 App 时先结束录屏和跟随声音。截图和录屏分别保存在原脚本使用的 `~/Pictures/scrcpy/`、`~/Movies/scrcpy/`，在「更多工具与设置」里列出最近文件。

跟随声音的采集进程由这个 App 拉起，系统把权限算在 `com.qx0657.phonestation` 上。`Info.plist` 里的 `NSAudioCaptureUsageDescription` 不能去掉：没有它时采集调用会成功，但音量一直是 0，权限窗口也不出现。面板在几秒后仍没有声音时，会提示到「系统录音」，并可以打开那一页。允许之后要关掉再打开一次。细节在 [torch.md](torch.md)。

本机的 Command Line Tools 可以编译并临时签署这个 App；完整 Xcode 不在当前开发者目录中。此构建只供本机试用。给其他 Mac 分发时，还需要处理依赖安装、Developer ID 签名和公证。面板可以连接和断开无线、保持亮屏、开关闪光灯，以及让灯跟随这台 Mac 正在播放的声音。第一次无线配对、会话提醒、MT 管理器仍按 README 的脚本入口操作。

「更多工具与设置」里的开机自启走 `SMAppService.mainApp`。`enabled` 才算已经开着；`requiresApproval` 时开关仍显示为开，并可以打开「系统设置 › 通用 › 登录项与扩展」。登录项只认 Launch Services 能看见的包：放在「应用程序」或用户目录下的 Applications 里才能登记。`build/.phone-station/` 是隐藏的构建目录，从那里打开时登记会被拒绝，面板会提示先放到「应用程序」再打开那个副本。版本号读 `CFBundleShortVersionString` 和 `CFBundleVersion`，显示在「关于」，「更多工具与设置」的关于一行上也有。
