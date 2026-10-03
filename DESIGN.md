---
name: 手机工位
description: Android 应用与 Mac 菜单栏面板的原生界面事实记录
colors:
  android-held-light: "#2E8552"
  android-held-dark: "#76B189"
  android-on-held-light: "#FFFFFF"
  android-on-held-dark: "#101114"
  android-waiting-light: "#B36B1F"
  android-waiting-dark: "#EDA85C"
  android-error-light: "#BA3833"
  android-error-dark: "#ED736B"
  android-page-light: "#F2F3F5"
  android-page-dark: "#101114"
  android-card-light: "#FFFFFF"
  android-card-dark: "#1C1E24"
  android-well-light: "#EEEFF3"
  android-well-dark: "#2A2D34"
  android-muted-light: "#5E6370"
  android-muted-dark: "#B7BBC4"
  android-connection-strip-light: "#F7F8FA"
  android-connection-strip-dark: "#23262C"
  android-notification-muted-light: "#5E6370"
  android-notification-muted-dark: "#AEAEB2"
  mac-connected-light: "#216B40"
  mac-connected-dark: "rgb(46.3% 69.4% 53.7%)"
  mac-caution-light: "rgb(70% 42% 12%)"
  mac-caution-dark: "rgb(93% 66% 36%)"
  mac-recording-light: "rgb(74% 22% 20%)"
  mac-recording-dark: "rgb(93% 45% 42%)"
  mac-background-light: "rgb(95% 95% 96%)"
  mac-background-dark: "#252527"
  mac-tile-light: "#FFFFFF"
  mac-tile-dark: "#323234"
typography:
  android-headline:
    fontFamily: "sans-serif-medium"
    fontSize: "26sp"
  android-page-title:
    fontFamily: "sans-serif-medium"
    fontSize: "22sp"
  android-group-title:
    fontFamily: "sans-serif-medium"
    fontSize: "20sp"
  android-body:
    fontSize: "16sp"
  android-value:
    fontSize: "15sp"
  android-note:
    fontSize: "14sp"
  android-field-label:
    fontFamily: "sans-serif-medium"
    fontSize: "14sp"
  android-status-label:
    fontSize: "12sp"
  android-notification-title:
    fontFamily: "sans-serif-medium"
    fontSize: "16sp"
  android-notification-status:
    fontSize: "12sp"
  android-address:
    fontFamily: "monospace"
    fontSize: "13sp"
  mac-page-title:
    fontFamily: "macOS system"
    fontSize: "16pt"
  mac-section-title:
    fontFamily: "macOS system"
    fontSize: "15pt"
  mac-field:
    fontFamily: "macOS system"
    fontSize: "13pt"
  mac-label:
    fontFamily: "macOS system"
    fontSize: "12pt"
  mac-status:
    fontFamily: "macOS system"
    fontSize: "11pt"
  mac-address:
    fontFamily: "macOS system monospaced"
    fontSize: "12pt"
  mac-banner-app:
    fontFamily: "macOS system"
    fontSize: "12pt"
    fontWeight: 500
  mac-banner-title:
    fontFamily: "macOS system"
    fontSize: "14pt"
    fontWeight: 600
  mac-banner-body:
    fontFamily: "macOS system"
    fontSize: "13pt"
    fontWeight: 400
rounded:
  android-card: "20dp"
  android-mark: "11dp"
  android-address: "10dp"
  android-notice: "12dp"
  mac-group: "12pt"
  mac-action-tile: "10pt"
  mac-notification-banner: "12pt"
spacing:
  android-page-side: "18dp"
  android-card-gap: "14dp"
  android-content-side: "16dp"
  android-field-gap: "10dp"
  mac-page-side: "16pt"
  mac-main-group-gap: "12pt"
  mac-mcp-group-gap: "14pt"
  mac-relay-group-gap: "16pt"
  mac-banner-inset: "14pt"
  mac-banner-column-gap: "12pt"
  mac-banner-text-gap: "4pt"
components:
  android-card-light:
    backgroundColor: "{colors.android-card-light}"
    rounded: "{rounded.android-card}"
  android-card-dark:
    backgroundColor: "{colors.android-card-dark}"
    rounded: "{rounded.android-card}"
  android-primary-light:
    backgroundColor: "{colors.android-held-light}"
    textColor: "{colors.android-on-held-light}"
    typography: "{typography.android-body}"
  android-primary-dark:
    backgroundColor: "{colors.android-held-dark}"
    textColor: "{colors.android-on-held-dark}"
    typography: "{typography.android-body}"
  mac-group-light:
    backgroundColor: "{colors.mac-tile-light}"
    rounded: "{rounded.mac-group}"
  mac-group-dark:
    backgroundColor: "{colors.mac-tile-dark}"
    rounded: "{rounded.mac-group}"
  mac-action-tile-light:
    backgroundColor: "{colors.mac-tile-light}"
    rounded: "{rounded.mac-action-tile}"
    typography: "{typography.mac-label}"
  mac-action-tile-dark:
    backgroundColor: "{colors.mac-tile-dark}"
    rounded: "{rounded.mac-action-tile}"
    typography: "{typography.mac-label}"
  mac-notification-banner:
    rounded: "{rounded.mac-notification-banner}"
    padding: "{spacing.mac-banner-inset}"
    width: "360pt"
  mac-notification-banner-icon:
    size: "42pt"
  mac-notification-banner-close:
    size: "24pt"
---

# Design System: 手机工位

## Overview

本记录提取 Android 应用与 Mac 菜单栏面板已实现的原生界面。两端沿用现有平台控件、系统字体和明暗外观，以连接状态、功能分组、配置字段与操作反馈构成层级。各端尺寸分别使用 Android 的 dp/sp 与 macOS 的 pt；这些是原生单位，不代表浏览器 CSS 像素。Mac 的 `rgb()` 百分比逐项对应源码 sRGB 分量，Android 颜色来自资源文件。

实现来源为 [Android 共用界面](lib/android/src/dev/phonestation/adbkeep/StationChrome.java)、[首页](lib/android/src/dev/phonestation/adbkeep/MainActivity.java)、[远程中继页](lib/android/src/dev/phonestation/adbkeep/RemoteRelayActivity.java)、[浅色资源](lib/android/res/values/colors.xml)、[深色资源](lib/android/res/values-night/colors.xml)，以及 [Mac 共用组件](app/mac/StationWidgets.swift)、[面板容器](app/mac/StationView.swift)、[首页](app/mac/MainPage.swift)、[MCP 页](app/mac/McpPage.swift)、[远程中继页](app/mac/RemoteRelayPage.swift)、[连接页](app/mac/ConnectionPage.swift)。41 版补入 [常驻通知](lib/android/src/dev/phonestation/adbkeep/StationNotifications.java) 与 [通知布局](lib/android/res/layout/note_expanded.xml)。应用自身品牌图标、会话提醒与其他工具子页不在这次提取范围。

2026-10-02 的依据包括源码、Android 实机画面与 Mac `NSHostingView` 对模拟数据的原生渲染。Android 38 已检查首页与抽屉的浅色/深色及深色 1.3 倍字号，以及中继开关与接入详情；范围见 [38 版界面验证](docs/adb-keep.md#38-版界面验证)。39 版已远程安装并核对 APK，侧栏结构及点击、边缘滑出、拖动收起、回弹与系统返回已通过实机远程输入和界面树检查；尚未采样新版系统栏的明暗截图，见 [39 版侧栏构建](docs/adb-keep.md#39-版侧栏构建)。此前远程表单已检查浅色/深色，以及深色 1.3 倍字号下的下部滚动区域。Mac 已检查配置、错误、双端配置、仅远程与等待手机状态，未用辅助功能点击验证已安装面板。Android 36 与 Mac 0.1.0 (2) 的安装和独立配置验证见 [两端配置界面与独立配置验证](docs/mcp.md#两端配置界面与独立配置验证)。评审图片在忽略的 `.impeccable/review/`，不是共享设计资产。此记录没有新增品牌方向。

42 版补入 [Android 共享剪贴板页](lib/android/src/dev/phonestation/adbkeep/ClipboardActivity.java) 与 [Mac 共享剪贴板页](app/mac/ClipboardPage.swift)，继续沿用各端共用组件。PGT-AN20 已检查浅色、深色和深色 1.3 倍字号；预览文字明确标为界面验证示例。Mac 浅色与深色图片是非活动 `NSHostingView` 原生渲染，灰色系统控件不能单独证明动作禁用；操作验证见 [共享剪贴板验证](docs/clipboard.md#验证)。本次只扩展页面与导航，没有新增 palette、字号角色或布局世界。

47 版通知设置页沿用 `StationChrome` 的标题、开关、链接行和段落，按「手机通知 → Mac」与「电脑提醒 → 手机」分成两组；应用白名单页使用同一开关行与应用真实图标，搜索字段沿用共用字段样式。Mac 首页新增「手机通知」，子页沿用页头、开关行、分组与反馈条。没有新增颜色或字号角色。2026-10-03 已检查 PGT-AN20 的浅色 1.0 倍、深色 1.3 倍字号及滚动下部，应用列表与搜索也已实机检查；Mac 的未授权、就绪、拒绝授权状态以模拟数据做浅色/深色原生渲染。47 版评审未验证真实系统通知展示，见 [通知同步验证](docs/notify.md#构建与验证)。

Android 48 与 Mac 0.1.0 (6) 补入 [手机应用图标导出](lib/android/src/dev/phonestation/adbkeep/NotificationAppIcon.java)、[Mac 应用图标横幅](app/mac/NotificationBanner.swift)、[通知设置页](app/mac/NotificationPage.swift) 与 [通知交付](app/mac/NotificationDelivery.swift)。6 版当时使用 macOS popover 材质，横幅沿用既有系统字体与语义文字色；新增字号角色只用于横幅，没有新增 palette 或品牌方向。

6 版阶段在 2026-10-03 逐张核对横幅浅色、深色、长文本、缺图和设置页浅色/深色六张布局图：`.impeccable/review/mac-banner-light.png`、`mac-banner-dark.png`、`mac-banner-long.png`、`mac-banner-fallback.png`、`mac-notifications-6-light.png`、`mac-notifications-6-dark.png`，均在同一评审目录。它们是离屏 `NSHostingView` 原生渲染，真实微信图标来自当前手机；不能证明实际 `NSPanel` 窗口材质、出现/收起、悬停、点击、关闭或队列交互。当时 finish reviewer 的 ship 结论只覆盖这些截图与源码，没有 material fixes；实机窗口与交互尚待用户打开菜单栏「手机通知」页面。该结论保留为历史，不代表后续玻璃材质已验收。

6 版阶段两端版本已安装，Mac 签名与安装后的可执行文件匹配；手机通知使用权与微信选择保留。实际 MCP 图标接口读取微信成功，未选择的包被拒绝。这些历史安装与接口检查不替代横幅的实机交互验证。

Mac 0.1.0 (7) 按用户要求改用 Apple 原生 Liquid Glass：macOS 26 及以上使用 `NSGlassEffectView` 的 regular 样式，通过 `contentView` 嵌入透明内容，圆角仍为 12pt，由系统绘制玻璃轮廓，不叠加自绘边框。旧系统沿用 popover 材质，但补入可拉伸圆角 alpha 遮罩，使背景材质与窗口阴影保持同一轮廓；宿主内容单独裁剪。6 版实机截图暴露了仅裁剪内容图层时圆角外的白色矩形，因此此前离屏布局检查不能作为窗口材质通过的证据。

7 版已在本机 macOS 26.6.2 通过 CUA 检查浅色、深色和长消息的真实 AppKit 玻璃窗口，使用生产 `NotificationBannerSurface` 与示例文字：圆角、文字截断和关闭按钮布局正常，未见露白直角或叠加边框。验证窗口是忽略目录中的独立界面 fixture，不接收手机或系统通知；该结果证明材质窗口的绘制，不证明已安装菜单栏 App 的完整通知交互。Mac 7 已安装并核对签名、编译代码与构建包；实际菜单栏横幅的点击与计时仍以实机反馈为准。

用户的后续实机截图表明 7 版仍缺少明显通透感。Mac 0.1.0 (8) 改为原生 clear 玻璃，并在其下增加独立 underWindowBackground/behindWindow 背景采样层（alpha 0.65）；玻璃与 `contentView` 中的文字、图标保持完整不透明度。8 版的真实 AppKit 窗口内部色块背景已检查浅色、深色与长消息：背景颜色透入、背景文字模糊、消息清楚，圆角与截断正常。该证据证明组件对窗口内部背景的材质处理，仍不代表跨应用桌面采样或已安装菜单栏横幅已经验收。8 版已安装、启动并核对签名与构建代码，见 [背景采样验证](docs/notify.md#mac-8-版背景采样)。

用户反馈 8 版实机效果仍与之前相同，未通过桌面验收。Mac 0.1.0 (9) 改由完整强度的 behindWindow 材质直接承载窗口，原生 clear 玻璃降为 0.24 不透明度的装饰；内容独立保持全不透明，增加轻量白色边缘反光。非激活浮窗在窗口内部背景上的玻璃仍能工作，因此不能归因为缺少窗口焦点。9 版已安装、启动并核对签名与编译代码；浅深色布局、队列与透明圆角通过。CUA 单窗口捕获不证明桌面合成，9 版当时的实机效果仍待确认，见 [桌面材质记录](docs/notify.md#mac-9-版桌面材质根)。用户后续实际画面也否定了 9 版；7、8、9 的局部检查与待确认叙述保留为历史，均不算独立桌面效果通过。

当前生产源码继续按用户指定的 Apple 原生液态玻璃方向修正：macOS 26 及以上将 `NSGlassEffectView` 直接作为 `NSPanel.contentView`，`style = .clear`，alpha 保持默认 1，`cornerRadius = 12`；透明 `NSHostingView` 放入 `glass.contentView`。已移除 `NSVisualEffectView` 底层、0.24 alpha 的玻璃装饰与自绘白色渐变高光。macOS 13–25 的 popover/behindWindow/active/maskImage 回退保持不变。本轮热修复已安装并启动为 Mac 0.1.0 (11)，版本与主进程路径已确认，严格签名校验及构建代码核对通过，现有偏好未动；10 版曾由其他工作安装。这些安装检查不代表独立桌面效果已验收，见 [Mac 11 版原生 Clear 根修正](docs/notify.md#mac-11-版原生-clear-根修正)。

根因对照使用真实 AppKit child-panel 的受控窗口合成，在完全相同的蓝紫绿色与文字背景上比较旧 9 版材质和新的 clear 玻璃根：旧层次偏灰白，新实现透出背景颜色并软化背景文字，前景消息可读。它证明受控合成中的差异，不能证明独立浮窗对其他应用和桌面的实际效果。Fresh reviewer `glass_finish_review_10` 的 disposition 为 `recapture`，独立跨应用桌面验收仍未关闭。

CUA 原生窗口捕获排除了其他独立窗口；已尝试通过 Preview 菜单截取整个桌面，但 `.impeccable/review/mac-banner-10-invalid-desktop-privacy-shield.png` 实际显示工具隐私遮罩，因此是无效证据，不能用于材质验收。本机系统 Liquid Glass 已选择 Clear，「降低透明度」与「增加对比度」均为 false，本轮没有改动系统开关。当前状态仍是等待有效的独立跨应用桌面证据与用户实机验收。

## Colors

### Primary

Android 的 `android-held-*` 用于已连接、已开启、成功反馈及表单主按钮；`android-on-held-*` 是主按钮文字。Mac 的 `mac-connected-*` 用于连通状态、正在使用的 MCP 通道和启用中的投屏操作。两端状态含义一致，浅色连接绿分别由各端源码定义。

### Secondary

`android-waiting-*` 与 `mac-caution-*` 表示等待、提醒或需要注意的状态。`android-error-*` 表示手机表单错误；`mac-recording-*` 同时用于录屏操作与远程表单错误、清除配置文字。它们是语义状态色。

### Neutral

Android 的 `android-page-*`、`android-card-*` 与 `android-well-*` 分别用于页面底、分组卡片和图标底托/禁用主按钮；`android-muted-*` 用于说明、字段提示与非在线状态。地址框使用页面底色。细分隔线使用资源 `hairline`，保留其 Android alpha 语义。

Mac 的 `mac-background-*` 与 `mac-tile-*` 分别用于面板底和分组/操作块。正文与辅助文字使用 SwiftUI 的 `.primary`、`.secondary`、`.tertiary`；按钮强调色、输入框底/边线与开关外观由系统提供。Android 正文色解析主题的 `textColorPrimary`，输入框底线、次按钮及点击涟漪也来自当前系统主题。这些系统颜色没有固定的项目色值。

## Typography

Android 使用系统默认文本字体；标题和字段标签明确选择 `sans-serif-medium`，地址用原生 `monospace`。首页连接标题、子页标题、卡片标题、行正文、右侧状态、说明和地址分别对应 frontmatter 的 Android 字体角色。说明行距倍数为 1.2，通知说明为 1.25；其余行高由系统字体度量决定。字号使用 sp，随系统字号设置变化。

Mac 使用原生系统字体，地址使用 `.system(..., design: .monospaced)`。页头/首页连接标题为 semibold，MCP/连接页的主要标题为 semibold；字段标签与分组标题为 medium，状态胶囊为 medium。具体尺寸见对应 Mac 字体角色。常规导航、说明和按钮还使用 `.body`、`.subheadline`、`.caption` 语义样式，其度量交给 macOS；不以推测的数值替换。

通知横幅的应用名、标题与正文分别使用 `mac-banner-app`、`mac-banner-title` 与 `mac-banner-body`，权重依次为 medium、semibold 与 regular。应用名带「· 手机」来源并限一行，标题限两行，正文限三行，正文额外行距为 2pt；空标题使用应用名，空正文不保留正文行。

## Layout

Android 是充满视口的单列 `ScrollView`，内容宽度随可用窗口伸缩，卡片高度随内容增长。共用列上边距为 8dp、底部为 28dp，侧边距与卡片间距见 frontmatter。卡片内常见文字与字段沿同一内容边距对齐。只读状态行最小高度为 46dp；开关行、导航行最小高度为 56dp。返回热区与输入/操作控件最小高度为 48dp。系统栏、刘海与键盘 Insets 留在滚动容器的边距中，底部取系统栏与键盘的较大值。没有源码定义的网页断点或多列布局。

Mac 面板宽度固定为 360pt，主体垂直排列，高度随页面内容决定。首页三枚操作块等宽横排，间隔为 8pt；其下是开关组与导航组。MCP 页将本地 adb 与远程中继两条状态排在同一卡片，接入信息、服务操作与中继入口依次排列。

通知横幅在指针所在屏幕的可用区域右上方纵向排列，正常宽度与内边距见 `mac-notification-banner`。窄屏按 `min(360, max(240, visible.width - 32))` 调整宽度；顶部与右侧分别留 12pt、16pt，横幅间隔 8pt，高度随内容增长且最小为 94pt。图标、文字和关闭按钮顶对齐，列间距与文字组内间距分别使用 `mac-banner-column-gap`、`mac-banner-text-gap`。超出可用屏幕底部的横幅不显示，没有网页断点。

共享剪贴板沿用两端现有单列布局。Android 依次排列共享开关、同步状态和使用说明三张卡片；Mac 在开关组下排列状态与使用说明，不再为正文预览固定高度。两端沿用现有页面侧边距、分组圆角与底色。

Mac 远程表单区域单独滚动：仅配置 Mac 时滚动区域高度为 316pt，勾选「同时配置手机」后为 400pt。保存区位于滚动区域下方，错误先于保存按钮排列；错误随内容换行，不被表单滚动带走。Android 远程页依次为连接概览、配置卡片、电脑端说明，错误/保存结果在配置说明之后、保存按钮之前。

## Elevation & Depth

Android 浅色卡片使用原生 elevation，源码设定为 `dp(1) + density × 0.5`（约 1.5dp，首项经过像素取整）；深色卡片不设置该 elevation，靠页面底、卡片底和图标底托的色差形成层级。系统按钮、输入框的深度效果由平台主题决定。

Mac 自定义分组和操作块没有自定义阴影。分组以底色、连续圆角和 `.primary` 8% 不透明度的 1pt 描边形成边界。分隔线同样用 `.primary` 8% 不透明度，厚度为 1pt。操作块启用时底色为状态色 16% 不透明度，描边为状态色 45% 不透明度。原生系统控件可有 macOS 提供的表面效果。

通知横幅由非激活 `NSPanel` 承载。macOS 26 及以上由原生 `NSGlassEffectView` 直接承载窗口内容根，使用 clear 样式和默认完整 alpha，透明 `NSHostingView` 嵌入其 `contentView`；玻璃下方没有 `NSVisualEffectView`，也没有 0.24 alpha 装饰或自绘白色渐变高光。macOS 13–25 保留 `.popover` 的 `NSVisualEffectView`、behindWindow、active 状态与圆角 `maskImage`，宿主内容另行裁剪；旧系统回退与原生玻璃都使用系统窗口阴影。当前源码符合原生玻璃方向，child-panel 受控合成只证明相同背景下的材质差异；独立跨应用桌面效果仍待有效捕获与用户验收。

## Shapes

Android 分组卡片、图标底托、地址框与通知框的圆角分别由 frontmatter 记录。首页连接图标底托是直径 48dp 的圆形；可操作行的图标底托为 36dp 方形，内图标为 20dp，只读事实行没有底托。返回图标为 22dp，导航箭头为 18dp。按钮与输入框形状来自系统主题。

Mac 分组与操作块采用 continuous 圆角。分组内每行 hover 填充是矩形，由整个分组裁剪外角；状态胶囊采用 `Capsule`。表单采用系统 `.roundedBorder`，圆角不在项目中另行赋值。

通知横幅在 macOS 26 及以上由 `NSGlassEffectView.cornerRadius` 使用 `mac-notification-banner` 圆角；macOS 13–25 用可拉伸 alpha `maskImage` 定义材质和窗口阴影的轮廓，透明宿主层单独裁剪。应用图标与关闭按钮使用 frontmatter 中各自的方形尺寸；应用图标按原始比例适配，没有新增图标底托或项目绘制的图标外框。

## Components

### Android 分组、状态行与导航

`StationChrome.card()` 提供全宽卡片。`fact()` 与 `valueRow()` 展示只读状态；`switchRow()` 使用原生 `Switch`，整行可触发切换，并将文本关联到开关供辅助功能读取；`linkRow()` 使用图标底托、文字、右侧状态和箭头表达可进入的设置。可点击行的 ripple 来自系统主题，图标不作为重复的辅助功能元素。

Android 首页依次由连接状态、共享剪贴板与通知协同分组、「MCP 服务」三张卡片组成。51 版把通知从侧栏移入共享剪贴板所在卡片，两个功能分别用状态导航行和简短说明呈现，以分隔线分开。剪贴板显示同步中、等待 Mac、暂停、关闭或依赖问题；通知显示手机同步的实际状态，并在说明中交代电脑提醒方向。同步已开启但权限、应用选择、服务或 Mac 会话尚未就绪时，不显示同步中。连接卡片内是无线调试实际开关、自动保持开关。45 版将 Wi-Fi 与 USB 调试改为连接标题右侧的两枚只读图标，移除卡片底部状态条。图标 22dp，两枚之间 12dp；与标题间隔 12dp，组合区域 56×48dp，可长按查看完整状态。开启或已连接时图标为连接绿，关闭或未连接时使用次要色与带斜线的图标，组合区域保留完整辅助功能描述。没有图标底托，连接类型仍在标题下方并使用完整可用宽度。MCP 分组保留服务开关、远程状态入口和接入详情；远程实际在线、MCP 实际监听时，对应导航图标及底托使用与开启开关一致的连接绿及淡绿，其他状态使用次要色与中性底托；本机地址与长说明移到详情页。远程通道开关移到中继页，未配置时禁用。

### Android 常驻通知

41 版保留系统通知外壳，收起时是 16sp medium 的连接标题与 12sp 补充。展开后连接类型位于标题右侧，简写为「无线 · 远程」等；下方隔 10dp 放三组 16dp 图标与 12sp 状态，名称「无线」「MCP」「保持」紧接「开／关」。标题和开启组文字是正文色，只有开启图标为连接绿；关闭组与连接类型使用 `android-notification-muted-*`。恢复问题只在自动保持开启时于底部出现，保持与连接事实分开，不把已连接标题换成错误描述。通知内容底部留 8dp，图标和状态没有额外底托或背景。辅助功能组使用完整功能名；系统主题、字号或密度改变时重新发布同一条通知以刷新外观。PGT-AN20 上已检查浅色 1.0 倍和深色 1.3 倍字号的主页、通知收起与展开，以及运行中主题切换，详见 [41 版验证](docs/adb-keep.md#41-版状态条与常驻通知验证)。

上方菜单与左侧边缘滑动打开页面内侧栏，宽度最多 320dp，保留至少 56dp 遮罩区域。顶部主标题是「手机工位」，14sp 次标题「设置」下依次为权限和关于；共享剪贴板入口在 50 版、通知入口在 51 版移到首页，远程通道与接入说明保留首页入口。背景延伸到系统栏，内容避让 Insets，图标明暗沿用首页。关闭按钮热区为 48dp；侧栏与遮罩跟随手指，松手后按速度和位置选择终点，120–260ms 减速动画遵循系统动画开关。纵向滚动保留；展开时首页退出辅助功能遍历，关闭后恢复。系统返回可收起，Android 14+ 返回手势跟随进度。全手势导航的边缘冲突处理见 [侧栏交互](docs/adb-keep.md#界面)。开关和导航标签允许两行，开关本身也有 48dp 最小热区与明确辅助功能名称。缺权限时首页保留直接入口。

### Android 字段、操作与反馈

`field()` 提供显式标签与原生单行 `EditText`。地址/指纹使用 URI 输入类型，令牌使用密码输入类型，已有令牌不回填；字段按地址、指纹、令牌顺序前进，令牌的键盘完成动作提交。`action()` 使用原生 `Button`，主按钮取状态绿与对应前景色，禁用主按钮取 `well` 与 `muted`；次按钮沿用系统主题。

远程页显示「未配置」「已关闭」「已连接」「连接中」。保存期间字段与操作禁用，主按钮显示「正在保存…」；成功后清空令牌字段并显示连接结果说明。错误用错误色、成功用连接色，并作为 polite live region。清除配置通过原生确认对话框。

### Android 共享剪贴板

50 版与 Mac 0.1.0 (10) 将共享页改为自动同步状态页。手机首页的共享剪贴板入口复用 `statusLinkRow()`；侧栏移除重复入口。详情页只保留一个共享开关，开启同时启用自动同步。下方显示同步状态、Mac 会话及必要的权限/MCP处理入口；旧版或 MCP 的暂停模式显示恢复按钮。第三组给出复制后到另一端粘贴、首次连接重新复制、支持范围与不保存历史的说明。两端剪贴板正文与手动复制按钮已移除，页面打开不额外读取系统剪贴板。

Mac 共享页沿用页头、开关行与分组：一个共享开关、一组同步状态，以及使用与隐私说明。同步就绪使用既有连接绿；依赖阻塞使用提醒色，并提供手机权限入口。只有实际完成同步才显示最近方向和时间，空闲心跳与首次核对不更新。关闭共享后不读取 Mac 剪贴板。此轮没有新增配色、字号或组件世界。

### Mac 分组、导航与操作块

`elevatedGroup()` 提供底色、裁剪与边界。`StationRows.navigationRow()` 使用 SF Symbols、可选辅助文字和右箭头；子页标题是返回按钮。`ActionTile` 使用图标上、文字下的排列，有默认、启用与禁用状态；禁用且非启用状态时整体不透明度为 0.4，启用状态仍可表达正在运行的操作。自定义按钮按下时不透明度为 0.62，hover 为系统 label 色 6% 的填充。

首页连接状态胶囊用对应状态色文字与 14% 不透明度的底色；电量与连接说明独立显示。远程在线且 adb 不可用时，首页保留 MCP 连通说明并解释屏幕操作需要 adb，相关操作按实际能力禁用。开关沿用原生 small switch；不可操作行整体不透明度为 0.4。

### Mac 共享剪贴板

首页导航组保留「共享剪贴板」与当前状态摘要，复用 `navigationRow()`；子页复用返回页头、`toggleRow()` 与 `elevatedGroup()`。只有一个共享开关，开启时自动同步；未连接 MCP 时显示说明和原生主按钮「连接 MCP 服务」。状态组显示同步就绪、暂停或具体依赖阻塞，有必要时提供权限处理或恢复自动同步按钮。最近同步的方向和时间使用系统 caption，时间使用等宽数字。反馈沿用 `feedbackBanner()`，底部说明沿用系统 subheadline/caption 与次要文字色；不再展示两端正文与手动复制按钮。

### Mac 字段、通道与配置反馈

MCP 通道行分别显示「正在使用」「可用」「未连通」，有文字状态配合颜色。可用时提供地址与 Authorization 的复制动作，令牌通过复制使用，不展示明文。停止手机 MCP 服务需要 adb；仅远程时按钮禁用并附原因。

远程设置使用原生 `TextField` / `SecureField`、系统圆边框、显式标签和 checkbox。默认只保存 Mac，无需 adb；「同时配置手机」显示第二枚密码字段和 adb 条件说明。主操作沿用 `.borderedProminent`，绑定默认键盘动作；仅当前配置存在时显示清除入口，并通过原生确认对话框处理。读取配置或执行任务期间字段与相关动作禁用，离开页面清空输入的秘密资料。错误固定在保存按钮前并使用更新提示辅助功能；通用反馈组件可带 small `ProgressView`。

### Mac 手机通知与应用图标横幅

通知设置页复用返回页头、`toggleRow()`、`elevatedGroup()` 与反馈条。接收开关下方新增「显示应用图标横幅」，默认开启；开启时显示原生「预览横幅」按钮，预览正在执行、Mac 通知权限未授权或暂停接收时禁用。说明明确指出悬停保持、点击横幅打开本页、系统通知中心保留记录，以及自定义横幅独立于系统专注模式；关闭此项恢复系统横幅。

手机图标接口导出已选择应用的 96×96px PNG，Mac 用横幅图标尺寸显示；缺图或解码失败时使用次要色的 SF Symbol `app.fill`，不丢弃通知。应用名和关闭符号使用 `.secondary`，标题与正文沿用默认语义文字色。关闭符号为 10pt semibold，按钮有明确辅助功能标签；图标不重复进入辅助功能遍历。

源码规定横幅 6 秒后收起，悬停时保持，移开后重新计 6 秒。队列最多三条，最新在上；同一包名和通知 key 的更新替换原横幅。点击主体收起当前横幅并打开 Mac「手机通知」页，关闭按钮只收起当前条目；关闭自定义横幅或暂停接收会清空正在显示的横幅。屏幕休眠、会话失活或屏幕配置变化也会收起。自定义模式下系统通知中心只保留 passive 记录，系统预览设为永不显示时横幅使用「收到一条新通知」并隐藏正文。这些行为由源码定义，当前截图评审没有完成实机交互验证。

Mac 操作块状态切换使用 0.16 秒 ease-out，hover wash 使用 0.12 秒 ease-out。通知横幅首次进入时以原生 `NSAnimationContext` 在 0.18 秒内从右侧偏移 12pt 回到原位；系统减少动态效果开启时跳过进入动画。
