# Mac 菜单栏 App

`app/mac/` 用 AppKit 创建状态栏图标和弹出面板，面板内容由 SwiftUI 绘制。`scripts/build-mac-app.sh` 用带 macOS 26 或更新 SDK 的 Xcode / Command Line Tools 和 Go 把它编成 `build/.phone-station/手机工位.app`。构建目录隐藏，避免 Spotlight 把构建副本列为第二个 App；`build/` 不入库。构建需要 Go 1.23 或更新版、Python 3，以及 Android 构建所用的 OpenJDK 17+、Android 35 SDK 和 build-tools，见 [adb-keep.md](adb-keep.md#构建)。构建只生成电脑上的产物，不安装手机应用。构建时把 `connect.sh`、`disconnect.sh`、`host-state.sh`、`status.sh`、`mirror.sh`、`record.sh`、`screenshot.sh`、`stay-awake.sh`、`torch.sh`、`mcp.sh`、`pair-code.sh`、`android.sh`、`shell.sh`、`install-apk.sh`、`remote_ops.py`、`common.sh`、`adb_mdns.py`、`torch.dex`、`torch_beat.py`、`torch-audio`、`notify.sh`、`notify_mcp.py`、`notify-sound.dex`、MCP 网关和钥匙串助手、编好的 `phone-app.apk` 和 README 放到 App 的 Resources，脚本仍按自身位置查找 `lib/`。因此 App 复制到其他目录后，不再依赖原仓库位置。声音采集程序在构建时放进包里；源码比它新时，构建会重新编译，不在 App 运行期间往包里写文件。这个程序的部署目标是 macOS 14.2，process tap 从那里才有。构建时如果沿用 App 的 13.0，编译会失败。

构建时从 `app/mac/MakeIcon.swift` 绘制应用图标并生成 `AppIcon.icns`。图标放在 App 的 Resources，`Info.plist` 通过 `CFBundleIconFile` 引用它。

Mac 0.1.0 (5) 新增首页「手机通知」与 `NotificationSession`，使用手机同一 MCP 的通知接收工具，连接通道由网关选择。手机同步开关与应用白名单存在手机上，手机端默认关闭、默认不选择应用；Mac 系统授权只在点击「允许 Mac 显示通知」时申请。未授权、暂停或离线时不拉取正文。同一手机 key 的更新替换同一 Mac 条目，首次和断线后不补发旧事件。退出 App 后停止，按钮与清除不联动。0.1.0 (6) 默认显示真实手机应用图标的自定义横幅，并静默保存系统通知中心记录；本机额外保存「显示应用图标横幅」偏好，关闭后恢复系统横幅。自定义窗口不激活 App，支持自动收起、悬停、关闭与点击进入通知页；它独立于系统专注模式，页面有明确说明与暂停开关。完整流程与验证见 [notify.md](notify.md#手机通知同步到-mac)。构建使用 UserNotifications framework，测试覆盖 Mac 接收状态与横幅队列。CLI 测试没有 App bundle 时不初始化系统通知中心。

App 是菜单栏辅助程序（`LSUIElement`），没有 Dock 图标。应用内用 `Process` 传递参数数组调用随包脚本，不拼接 shell 命令；启动时给脚本补入 Homebrew、系统工具和 Android SDK 常见路径。`adb`、`scrcpy`、`python3` 仍由电脑提供。连接走 `connect.sh`，投屏、截图和录屏沿用现有脚本，确保单设备、mDNS 和 VPN 路由限制仍由同一套逻辑处理。面板按页拆开：连接、投屏和录屏、闪光灯、最近文件、adb 命令、登录项、MCP、配对和安装各自一份状态。`Station` 只负责把它们接上，并记住当前页。构建在编译 App 之前跑电脑上的测试：Go race、配对与独立配置、MCP 通知、远程安装恢复、Android 全量测试，以及 Swift 的重连间隔、adb 参数与电量解析、远程表单、剪贴板同步、连接状态与图标更新。

`NSApplication` 不会强持有 delegate。入口用静态属性保留 `PhoneStationApp`，否则进程可以运行而 `applicationDidFinishLaunching` 不执行，状态栏图标也不会建立。菜单栏入口不带标题。已连接时是模板符号 `iphone`，其余状态是 `iphone.slash`，都是 point size 16、字重 medium。这两个符号不要设 `size`：12×12 的框会把字形裁掉，入口看起来是空的。两个符号外框一样大，切换时菜单栏项不会左右挤。提示是「手机工位：」加上和面板相同的状态字：检查中、已连接、未验证、未连接、正在重新连接。adb 在线且型号为 PGT-AN20，或网关已探测到远程通道在线，就用前一个符号。两者都不满足时用带斜线的。远程在线时，后台 adb 重连不会把图标或状态字改回未连接。App 一启动就通过 `adb track-devices -l` 监听列表变化，掉线立即清掉旧序列号；另在上次复查完成后隔 1 秒核实 `adb devices -l` 和实际应答。序列号仍是上次确认过的那台 PGT-AN20 时，不再读型号；无线序列号仍用有 2 秒超时的 `shell true` 核实，避免旧 TCP 被 adb 列为在线。在面板里连接或断开后，图标跟着那次结果变。网关通过鉴权状态流推送通道变化；adb 与本地 MCP 分开判断，MCP 服务关闭或转发失败不会把仍可应答的 adb 报成断线。菜单栏提示同时显示已确认的连接类型。这次定时检查不读息屏时间、电量，也不跑 `torch.sh`。

没有在线设备、并且这次打开后没有成功断开过无线时，同一次检查会运行 `connect.sh`。已经有一台在线设备时不跑 `connect.sh`，避免再连出第二条；这次检查会跑 `host-state.sh mark`，把手机上的连接配置写成当前时间。手机怎么读这个值、断开后怎么复位，见 [adb-keep.md](adb-keep.md)。多台设备、等待授权、找不到 adb，或暂时读不出型号时也不跑。第一次马上找。没找到之后，要等过 5 秒、15 秒、30 秒，再往后每 60 秒，由下一次设备检查再找。设备检查上一次完成后隔 1 秒再查一次。这次查找不占面板上的操作状态。点「连接手机」会立刻再找。点「断开无线连接」成功之后，直到再点「连接手机」，这次打开期间不再自动接。退出后再打开会重新开始。自动接上之后会读一次息屏、闪光灯和电量。

面板最上行的标题是手机名称。adb 识别到的 PGT-AN20 显示为「荣耀 PGT-AN20」，下一行写「无线连接」或「USB 连接」。只有远程可用时，保留本次已识别的手机名称；未识别过则显示「已配对手机」，下一行是「远程连接」。右侧是连接状态；已经读到电量时，状态左边是百分比和电池图标。充电中在百分比后加闪电。这台系统没有 `battery.25.bolt` 这一类不满电的符号，只有满电带闪电，所以闪电单独画。低于 20% 且没在充电时用提醒色。应用名不放在这个标题上。未连接时，下一行是原因。下面三个操作是图标在上、标题在下的方块，符号 16pt，直向留白比早先的一版短。macOS 26 的 SwiftUI `.bordered` 按钮高度是固定的，这样排时标题会被裁成一条线，所以这三个按钮自己画圆角底。分组里的可点行，悬停时加深的底铺满整行，贴住卡片左右边缘；卡片圆角靠外框裁切，行自己不要再画一块更小的圆角底，否则靠分隔线的一侧和左右会露出原来的底。投屏或录屏进行中时，对应的方块用自己的颜色标出来。面板高度跟着当前页的理想尺寸走（`NSHostingController.sizingOptions = .preferredContentSize`），不要再写死一个能装下所有页的高度。面板是 `NSPopover` 里的自定义界面，上沿那支箭头是系统给弹出面板画的锚点。系统菜单栏菜单是 `NSMenu`，贴在图标下面，没有这支箭头。这里有开关、方块和多页，放不进系统菜单。

`popover.behavior` 是 `.transient`，但本 App 是 `LSUIElement`。点到桌面或其他应用时，这次点击到不了面板，`.transient` 就不会收。面板显示后另外听两类鼠标按下：别的进程里的，以及本进程里落在面板和菜单栏图标以外的。Esc 同样收起。菜单栏图标那一下仍由按钮自己开关，不在这次监听里收，否则同一次点击会先关上再打开。收起后回到主页。录屏和灯光跟随声音不因为收起面板而停。

`NSStatusItem.isVisible` 不能说明屏幕上看得到。要看按钮窗口落在不在菜单栏那一截，或看辅助功能里菜单栏项的纵坐标；这台机器上看得见的项大约是 y=5。启动日志的 subsystem 是 `com.qx0657.phonestation`。zsh 里要调用 `/usr/bin/log show`，内置的 `log` 不是这条命令。

这台 Mac 开着 Hidden Bar，自动隐藏是开的。分隔条的 preferred position 是 608，收起控制大约在 5624。本 App 曾经把 `NSStatusItem Preferred Position Item-0` 记成 5656，图标就停在收起区，窗口不在菜单栏上。控制中心只在进程创建第一个状态项时读这个值。创建之后再改偏好、再重建状态项，位置不会跟着动。启动时如果这个值缺失，或不在 80 到 600 之间，就先写成 400 再创建。400 在这台机器上落在看得见的一排里（大约 x=1257）。80 到 600 跟着这台 Hidden Bar 的分隔条；分隔条位置变了，这个范围一起改。

macOS 26 的控制中心另外按 bundle id 记一份允许名单。它在 `~/Library/Group Containers/group.com.apple.controlcenter/Library/Preferences/group.com.apple.controlcenter.plist` 的 `trackedApplications` 里，值本身又是一份二进制 plist，由 cfprefsd 缓存。直接改这个文件会被缓存写回去。2026-09-30 这台机器上，`com.qx0657.phonestation` 自己的记录是 `isAllowed` true，但 `com.openai.codex` 的记录是 `isAllowed` false，并且它的 `menuItemLocations` 里还有 `com.qx0657.phonestation`。控制中心日志会出现 `Moving host to blocked list`，窗口停在菜单栏外面（例如 `{{0, -17}}`），进程仍在。把本 App 的 bundle id 从这份已禁用记录的 `menuItemLocations` 里去掉，经带有 `group.com.apple.controlcenter` application-group 权限的进程用 `UserDefaults` 写回，再重启控制中心，图标才出现在菜单栏上。其他 App 的 `isAllowed` 保持原样。

状态读取使用 `adb devices -l`，按完整序列号解析（包括 mDNS 名称里的空格），有重复记录时按 `ro.serialno` 核实是否同一台设备；确认同一台才合并显示，同时由 `connect.sh` 整理重复通道。真正有多台设备时停用 adb 操作。首次识别还会读 `ro.product.model`。只允许 `PGT-AN20` 执行设备操作。其他型号、无法识别型号、多台在线或等待 USB 授权时，adb 设备操作停用；远程也不可用时，面板显示原因并用未连接图标。无线连接既可能以 `IP:端口`、也可能以 `._adb-tls-connect._tcp` mDNS 名称出现在 adb 序列号里；这台 PGT-AN20 当前是后一种。打开面板时会重新读连接、息屏时间、闪光灯状态和电量，这些读取不会改设置，也不会触发截屏、投屏、录屏或手机提醒。电量来自 `dumpsys battery` 的 `level`、`scale` 和 `status`；`status` 为 2 时算充电中。菜单栏那次定时检查只决定图标，不代替打开面板时的这几项读取。MCP 状态通过 `mcp.sh watch` 接收网关推送；远程最近 6 秒有成功应答才同步为首页和菜单栏的已连接，长操作没有新应答时显示确认中；只返回地址和令牌时还不能确认远程在线，启动成功后会再读一次状态。投屏、录屏、灯光跟随声音和 adb 命令要求已验证的 adb 序列号。截屏、保持亮屏和手电筒在只有远程连接时使用 MCP，按机型、权限和实际状态分别启用，见 [remote-controls.md](remote-controls.md)。

主页在「连接与配对」和「更多工具与设置」之间有「adb 命令」。一条命令是名称，加上 `adb -s 序列号` 后面的参数。点一行就在当前这台已验证的手机上执行；铅笔进入编辑。参数在 App 里按引号拆开，交给 `adb` 的参数数组，不经过本机 shell，所以不会展开 `$` 和通配符。管道写在一对引号里才会在手机上执行，例如 `shell "dumpsys window | grep mCurrentFocus"`。参数里不要再写 `-s` 或 `--serial`。超过 15 秒会停，输出只留末尾 12 行，可以复制。列表存在本机偏好 `adbCommands.v1`。第一次使用放了「前台应用」和「唤醒」；删掉之后不会再自动加回来。电量不在这条列表里。「在终端中打开 shell」启动「终端」，用 AppleScript 的 `quoted form` 把 adb 路径和序列号放进 `adb -s <序列号> shell`。未连接或未验证时，执行和打开终端变淡并且不能点；编辑和新建仍然可以。

投屏、录屏和「灯光跟随声音」由 App 持有，收起面板不影响它们。点「打开投屏」时本 App 还是活动应用，scrcpy 窗口要接着到前台。这个 App 是 `LSUIElement`，macOS 14 起 SDL 也不会默认抢前台，所以窗口会留在后面。窗口一旦出现，就 `yieldActivation` 给该进程，再 `activate(from:)`。大约每 0.25 秒看一次，最多约 30 秒；进程退出或已经在前台就停。不要设 `SDL_MAC_BACKGROUND_APP=0`：那条会先把 Dock 激活到前面。从终端跑 `mirror.sh` 不用这段逻辑。录屏停止时给 `scrcpy` 发 `SIGINT`，等它退出后再检查文件并显示结果；不要直接强杀，否则 MP4 可能没有完整收尾。跟随声音停止时同样发 `SIGINT`，脚本会把灯关掉。退出 App 时先结束录屏和跟随声音。截图和录屏分别保存在原脚本使用的 `~/Pictures/scrcpy/`、`~/Movies/scrcpy/`。首页「最近文件」列出最近 8 个，不放在「更多工具与设置」里。每一行左边是文件自己的缩略图，录屏取约 0.2 秒处的一帧。鼠标停在一行上时，大图出现在面板旁边，不占面板高度，也不接鼠标点击。点这一行把内容复制到剪贴板：PNG 同时放上图片数据和文件地址，录屏放上文件地址和路径文字。复制后按钮改成「已复制」，大约 1.2 秒后回到「复制」。行尾箭头仍在 Finder 中显示该文件。面板收起时，旁边的预览一起消失。

跟随声音的采集进程由这个 App 拉起，系统把权限算在 `com.qx0657.phonestation` 上。`Info.plist` 里的 `NSAudioCaptureUsageDescription` 不能去掉：没有它时采集调用会成功，但音量一直是 0，权限窗口也不出现。面板在几秒后仍没有声音时，会提示到「系统录音」，并可以打开那一页。允许之后要关掉再打开一次。细节在 [torch.md](torch.md)。

本机的 Command Line Tools 可以编译并临时签署这个 App；完整 Xcode 不在当前开发者目录中。此构建只供本机试用。给其他 Mac 分发时，还需要处理依赖安装、Developer ID 签名和公证。面板可以连接和断开无线，没有设备在线时自动重连，显示电量、保持亮屏、开关闪光灯、让灯跟随这台 Mac 正在播放的声音，以及运行保存的 adb 命令。

「连接与配对」里可以做第一次的 6 位配对码。手机先打开「无线调试 → 使用配对码配对」，停在那个页面，再把 6 位数字填进来。地址仍由 `pair-code.sh` 用 mDNS 找，不要填页面上的 `172.19`。二维码配对仍用仓库里的 `pair-qr.sh`，不放进菜单栏：那个脚本会往仓库里装 npm 依赖。「更多工具与设置」里的「安装或更新手机应用」跑随包的 `android.sh`，装的是构建时放进包里的 APK，已有中继与 Shizuku 时优先远程更新，首次安装走 adb 并授权限；远程结果未确认时查询原安装任务，不再次安装，见 [remote-ops.md](remote-ops.md)。无线调试保持仍在手机上的「手机工位」里。会话提醒仍按 README 安装一次。

主页有「MCP 服务」，副标题显示当前通道或「等待手机」「未启动」。打开和停止走 `mcp.sh`。它启动本机 MCP 网关 `127.0.0.1:18765`，把 adb 转发放在 `18766`，或在 adb 不可用时使用已配置的远程中继。网关独立探测本地（每秒一次）和远程（每 2 秒一次），只剩一条时立即切换；两条都在线时默认 adb，本地延迟连续三次高于 500 毫秒且远程至少快 40% 才切到远程。本地断线后的恢复需三次稳定低延迟探测和 3 秒冷却；因延迟选择远程时，仍需六次低于 150 毫秒和 60 秒冷却。页面通过 `mcp.sh watch` 的 NDJSON 状态流更新，网关每 100 毫秒检查状态差异并立即推送；流中断自动重连，并以 `snapshot` 兜底，adb 转发与令牌另在后台刷新，其他操作进行中也会继续更新。令牌可以复制成 `Authorization: Bearer …`。手机上的 MCP 开关仍控制本地服务，手机界面上的地址只在手机本机。停止需要 adb 在线；如果 adb 已断开，只能在手机上关掉服务。

「MCP 服务」分开显示本地 adb、远程中继的可用状态与当前通道。接入地址保持固定，地址和 Authorization 分别复制，界面不直接展示令牌。只有远程可用时，停止手机服务按钮说明 adb 要求。首页与「连接与配对」也提供远程 MCP 的边界和入口。

「MCP 服务 → 远程中继」默认只配置此 Mac，填写服务器地址、SPKI SHA-256 指纹和电脑令牌即可保存，无需 adb。勾选「同时配置手机」才展开手机令牌与 adb 要求，使用 `pair … --stdin` 一次配置两端。默认使用 `desktop … --stdin`，只送一行电脑令牌。保存与清除 Mac 配置固定在滚动表单下方；清除前需确认，不修改手机。地址与指纹回填，令牌不回填、保存或离开后清空。配置成功与实际连接分别显示。配置要求见 [mcp.md](mcp.md)。

更新已安装 App 时先退出旧 App 和本机网关、保留旧完整目录，再复制新包、检查签名并重新启动；不要原地覆盖正在运行的 Mach-O 文件。网关与钥匙串助手使用暂存文件原子替换构建产物。

当前 MCP 调度与剪贴板慢响应语义见 [clipboard.md](clipboard.md#mac-16-的请求调度与慢响应)。

「更多」会保留上次远程安装编号并提供「查询原任务」和「复制编号」。安装中断后先查询；任务编号在执行前保存，关闭窗口或重启 App 不丢失。

`StationRunner.capture` 的有限时任务使用权限 `0700` 的临时目录保存输出，返回时清理。标准输入保留内存管道，改成非阻塞写入并共用执行期限，避免子进程不读取 stdin 时，大于管道容量的输入卡在超时计时之前；凭据不落临时文件。macOS Foundation 启动子进程后，先核实它具有独立进程组；超时时终止整个组，宽限期后强制结束仍存活的成员。主动脱离进程组的守护进程不在这项清理范围内；长期投屏等仍由 `startScript` 的既有生命周期管理。2026-10-03 的本机测试覆盖了 1 MiB 输入、拒读 stdin、忽略 TERM 的子进程及不误杀其他进程，未启动真实手机操作。

## 历史验证

历次版本安装、短时在线采样和界面 fixture 验证见 [历史验证记录](history/mac-app.md)。
