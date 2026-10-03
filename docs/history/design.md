# design 历史验证记录

以下为迁移前的日期、版本、测试与部署记录，叙述中的“当前”“本轮”均指当时。不能据此判断今天运行的版本或把模拟测试当成生产验收。当前规则见 [DESIGN.md](../../DESIGN.md)，部署状态见 [发布记录](../releases.md)。

2026-10-02 的依据包括源码、Android 实机画面与 Mac `NSHostingView` 对模拟数据的原生渲染。Android 38 已检查首页与抽屉的浅色/深色及深色 1.3 倍字号，以及中继开关与接入详情；范围见 [38 版界面验证](adb-keep.md#38-版界面验证)。39 版已远程安装并核对 APK，侧栏结构及点击、边缘滑出、拖动收起、回弹与系统返回已通过实机远程输入和界面树检查；尚未采样新版系统栏的明暗截图，见 [39 版侧栏构建](adb-keep.md#39-版侧栏构建)。此前远程表单已检查浅色/深色，以及深色 1.3 倍字号下的下部滚动区域。Mac 已检查配置、错误、双端配置、仅远程与等待手机状态，未用辅助功能点击验证已安装面板。Android 36 与 Mac 0.1.0 (2) 的安装和独立配置验证见 [两端配置界面与独立配置验证](mcp.md#两端配置界面与独立配置验证)。评审图片在忽略的 `.impeccable/review/`，不是共享设计资产。此记录没有新增品牌方向。

42 版补入 [Android 共享剪贴板页](../../lib/android/src/dev/phonestation/adbkeep/ClipboardActivity.java) 与 [Mac 共享剪贴板页](../../app/mac/ClipboardPage.swift)，继续沿用各端共用组件。PGT-AN20 已检查浅色、深色和深色 1.3 倍字号；预览文字明确标为界面验证示例。Mac 浅色与深色图片是非活动 `NSHostingView` 原生渲染，灰色系统控件不能单独证明动作禁用；操作验证见 [共享剪贴板验证](clipboard.md#验证)。本次只扩展页面与导航，没有新增 palette、字号角色或布局世界。

47 版通知设置页沿用 `StationChrome` 的标题、开关、链接行和段落，按「手机通知 → Mac」与「电脑提醒 → 手机」分成两组；应用白名单页使用同一开关行与应用真实图标，搜索字段沿用共用字段样式。Mac 首页新增「手机通知」，子页沿用页头、开关行、分组与反馈条。没有新增颜色或字号角色。2026-10-03 已检查 PGT-AN20 的浅色 1.0 倍、深色 1.3 倍字号及滚动下部，应用列表与搜索也已实机检查；Mac 的未授权、就绪、拒绝授权状态以模拟数据做浅色/深色原生渲染。47 版评审未验证真实系统通知展示，见 [通知同步验证](notify.md#构建与验证)。

Android 48 与 Mac 0.1.0 (6) 补入 [手机应用图标导出](../../lib/android/src/dev/phonestation/adbkeep/NotificationAppIcon.java)、[Mac 应用图标横幅](../../app/mac/NotificationBanner.swift)、[通知设置页](../../app/mac/NotificationPage.swift) 与 [通知交付](../../app/mac/NotificationDelivery.swift)。6 版当时使用 macOS popover 材质，横幅沿用既有系统字体与语义文字色；新增字号角色只用于横幅，没有新增 palette 或品牌方向。

6 版阶段在 2026-10-03 逐张核对横幅浅色、深色、长文本、缺图和设置页浅色/深色六张布局图：`.impeccable/review/mac-banner-light.png`、`mac-banner-dark.png`、`mac-banner-long.png`、`mac-banner-fallback.png`、`mac-notifications-6-light.png`、`mac-notifications-6-dark.png`，均在同一评审目录。它们是离屏 `NSHostingView` 原生渲染，真实微信图标来自当前手机；不能证明实际 `NSPanel` 窗口材质、出现/收起、悬停、点击、关闭或队列交互。当时 finish reviewer 的 ship 结论只覆盖这些截图与源码，没有 material fixes；实机窗口与交互尚待用户打开菜单栏「手机通知」页面。该结论保留为历史，不代表后续玻璃材质已验收。

6 版阶段两端版本已安装，Mac 签名与安装后的可执行文件匹配；手机通知使用权与微信选择保留。实际 MCP 图标接口读取微信成功，未选择的包被拒绝。这些历史安装与接口检查不替代横幅的实机交互验证。

Mac 0.1.0 (7) 按用户要求改用 Apple 原生 Liquid Glass：macOS 26 及以上使用 `NSGlassEffectView` 的 regular 样式，通过 `contentView` 嵌入透明内容，圆角仍为 12pt，由系统绘制玻璃轮廓，不叠加自绘边框。旧系统沿用 popover 材质，但补入可拉伸圆角 alpha 遮罩，使背景材质与窗口阴影保持同一轮廓；宿主内容单独裁剪。6 版实机截图暴露了仅裁剪内容图层时圆角外的白色矩形，因此此前离屏布局检查不能作为窗口材质通过的证据。

7 版已在本机 macOS 26.6.2 通过 CUA 检查浅色、深色和长消息的真实 AppKit 玻璃窗口，使用生产 `NotificationBannerSurface` 与示例文字：圆角、文字截断和关闭按钮布局正常，未见露白直角或叠加边框。验证窗口是忽略目录中的独立界面 fixture，不接收手机或系统通知；该结果证明材质窗口的绘制，不证明已安装菜单栏 App 的完整通知交互。Mac 7 已安装并核对签名、编译代码与构建包；实际菜单栏横幅的点击与计时仍以实机反馈为准。

用户的后续实机截图表明 7 版仍缺少明显通透感。Mac 0.1.0 (8) 改为原生 clear 玻璃，并在其下增加独立 underWindowBackground/behindWindow 背景采样层（alpha 0.65）；玻璃与 `contentView` 中的文字、图标保持完整不透明度。8 版的真实 AppKit 窗口内部色块背景已检查浅色、深色与长消息：背景颜色透入、背景文字模糊、消息清楚，圆角与截断正常。该证据证明组件对窗口内部背景的材质处理，仍不代表跨应用桌面采样或已安装菜单栏横幅已经验收。8 版已安装、启动并核对签名与构建代码，见 [背景采样验证](notify.md#mac-8-版背景采样)。

用户反馈 8 版实机效果仍与之前相同，未通过桌面验收。Mac 0.1.0 (9) 改由完整强度的 behindWindow 材质直接承载窗口，原生 clear 玻璃降为 0.24 不透明度的装饰；内容独立保持全不透明，增加轻量白色边缘反光。非激活浮窗在窗口内部背景上的玻璃仍能工作，因此不能归因为缺少窗口焦点。9 版已安装、启动并核对签名与编译代码；浅深色布局、队列与透明圆角通过。CUA 单窗口捕获不证明桌面合成，9 版当时的实机效果仍待确认，见 [桌面材质记录](notify.md#mac-9-版桌面材质根)。用户后续实际画面也否定了 9 版；7、8、9 的局部检查与待确认叙述保留为历史，均不算独立桌面效果通过。

当前生产源码继续按用户指定的 Apple 原生液态玻璃方向修正：macOS 26 及以上将 `NSGlassEffectView` 直接作为 `NSPanel.contentView`，`style = .clear`，alpha 保持默认 1，`cornerRadius = 12`；透明 `NSHostingView` 放入 `glass.contentView`。已移除 `NSVisualEffectView` 底层、0.24 alpha 的玻璃装饰与自绘白色渐变高光。macOS 13–25 的 popover/behindWindow/active/maskImage 回退保持不变。本轮热修复已安装并启动为 Mac 0.1.0 (11)，版本与主进程路径已确认，严格签名校验及构建代码核对通过，现有偏好未动；10 版曾由其他工作安装。这些安装检查不代表独立桌面效果已验收，见 [Mac 11 版原生 Clear 根修正](notify.md#mac-11-版原生-clear-根修正)。

根因对照使用真实 AppKit child-panel 的受控窗口合成，在完全相同的蓝紫绿色与文字背景上比较旧 9 版材质和新的 clear 玻璃根：旧层次偏灰白，新实现透出背景颜色并软化背景文字，前景消息可读。它证明受控合成中的差异，不能证明独立浮窗对其他应用和桌面的实际效果。Fresh reviewer `glass_finish_review_10` 的 disposition 为 `recapture`，独立跨应用桌面验收仍未关闭。

CUA 原生窗口捕获排除了其他独立窗口；已尝试通过 Preview 菜单截取整个桌面，但 `.impeccable/review/mac-banner-10-invalid-desktop-privacy-shield.png` 实际显示工具隐私遮罩，因此是无效证据，不能用于材质验收。本机系统 Liquid Glass 已选择 Clear，「降低透明度」与「增加对比度」均为 false，本轮没有改动系统开关。当前状态仍是等待有效的独立跨应用桌面证据与用户实机验收。
