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
rounded:
  android-card: "20dp"
  android-mark: "11dp"
  android-address: "10dp"
  android-notice: "12dp"
  mac-group: "12pt"
  mac-action-tile: "10pt"
spacing:
  android-page-side: "18dp"
  android-card-gap: "14dp"
  android-content-side: "16dp"
  android-field-gap: "10dp"
  mac-page-side: "16pt"
  mac-main-group-gap: "12pt"
  mac-mcp-group-gap: "14pt"
  mac-relay-group-gap: "16pt"
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
---

# Design System: 手机工位

## Overview

本记录提取 Android 应用与 Mac 菜单栏面板已实现的原生界面。两端沿用现有平台控件、系统字体和明暗外观，以连接状态、功能分组、配置字段与操作反馈构成层级。各端尺寸分别使用 Android 的 dp/sp 与 macOS 的 pt；这些是原生单位，不代表浏览器 CSS 像素。Mac 的 `rgb()` 百分比逐项对应源码 sRGB 分量，Android 颜色来自资源文件。

实现来源为 [Android 共用界面](lib/android/src/dev/phonestation/adbkeep/StationChrome.java)、[首页](lib/android/src/dev/phonestation/adbkeep/MainActivity.java)、[远程中继页](lib/android/src/dev/phonestation/adbkeep/RemoteRelayActivity.java)、[浅色资源](lib/android/res/values/colors.xml)、[深色资源](lib/android/res/values-night/colors.xml)，以及 [Mac 共用组件](app/mac/StationWidgets.swift)、[面板容器](app/mac/StationView.swift)、[首页](app/mac/MainPage.swift)、[MCP 页](app/mac/McpPage.swift)、[远程中继页](app/mac/RemoteRelayPage.swift)、[连接页](app/mac/ConnectionPage.swift)。41 版补入 [常驻通知](lib/android/src/dev/phonestation/adbkeep/StationNotifications.java) 与 [通知布局](lib/android/res/layout/note_expanded.xml)。应用图标、会话提醒与其他工具子页不在这次提取范围。

2026-10-02 的依据包括源码、Android 实机画面与 Mac `NSHostingView` 对模拟数据的原生渲染。Android 38 已检查首页与抽屉的浅色/深色及深色 1.3 倍字号，以及中继开关与接入详情；范围见 [38 版界面验证](docs/adb-keep.md#38-版界面验证)。39 版已远程安装并核对 APK，侧栏结构及点击、边缘滑出、拖动收起、回弹与系统返回已通过实机远程输入和界面树检查；尚未采样新版系统栏的明暗截图，见 [39 版侧栏构建](docs/adb-keep.md#39-版侧栏构建)。此前远程表单已检查浅色/深色，以及深色 1.3 倍字号下的下部滚动区域。Mac 已检查配置、错误、双端配置、仅远程与等待手机状态，未用辅助功能点击验证已安装面板。Android 36 与 Mac 0.1.0 (2) 的安装和独立配置验证见 [两端配置界面与独立配置验证](docs/mcp.md#两端配置界面与独立配置验证)。评审图片在忽略的 `.impeccable/review/`，不是共享设计资产。此记录没有新增品牌方向。

42 版补入 [Android 共享剪贴板页](lib/android/src/dev/phonestation/adbkeep/ClipboardActivity.java) 与 [Mac 共享剪贴板页](app/mac/ClipboardPage.swift)，继续沿用各端共用组件。PGT-AN20 已检查浅色、深色和深色 1.3 倍字号；预览文字明确标为界面验证示例。Mac 浅色与深色图片是非活动 `NSHostingView` 原生渲染，灰色系统控件不能单独证明动作禁用；操作验证见 [共享剪贴板验证](docs/clipboard.md#验证)。本次只扩展页面与导航，没有新增 palette、字号角色或布局世界。

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

## Layout

Android 是充满视口的单列 `ScrollView`，内容宽度随可用窗口伸缩，卡片高度随内容增长。共用列上边距为 8dp、底部为 28dp，侧边距与卡片间距见 frontmatter。卡片内常见文字与字段沿同一内容边距对齐。只读状态行最小高度为 46dp；开关行、导航行最小高度为 56dp。返回热区与输入/操作控件最小高度为 48dp。系统栏、刘海与键盘 Insets 留在滚动容器的边距中，底部取系统栏与键盘的较大值。没有源码定义的网页断点或多列布局。

Mac 面板宽度固定为 360pt，主体垂直排列，高度随页面内容决定。首页三枚操作块等宽横排，间隔为 8pt；其下是开关组与导航组。MCP 页将本地 adb 与远程中继两条状态排在同一卡片，接入信息、服务操作与中继入口依次排列。

共享剪贴板沿用两端现有单列布局。Android 依次排列设置、手机预览、Mac 预览和使用说明四张卡片，预览随文字增长，最多显示八行。Mac 在开关组下依次排列两端预览与说明；每个预览组内是标题、76pt 高的文字滚动区和手动复制按钮，沿用现有页面侧边距、分组圆角与底色。

Mac 远程表单区域单独滚动：仅配置 Mac 时滚动区域高度为 316pt，勾选「同时配置手机」后为 400pt。保存区位于滚动区域下方，错误先于保存按钮排列；错误随内容换行，不被表单滚动带走。Android 远程页依次为连接概览、配置卡片、电脑端说明，错误/保存结果在配置说明之后、保存按钮之前。

## Elevation & Depth

Android 浅色卡片使用原生 elevation，源码设定为 `dp(1) + density × 0.5`（约 1.5dp，首项经过像素取整）；深色卡片不设置该 elevation，靠页面底、卡片底和图标底托的色差形成层级。系统按钮、输入框的深度效果由平台主题决定。

Mac 自定义分组和操作块没有自定义阴影。分组以底色、连续圆角和 `.primary` 8% 不透明度的 1pt 描边形成边界。分隔线同样用 `.primary` 8% 不透明度，厚度为 1pt。操作块启用时底色为状态色 16% 不透明度，描边为状态色 45% 不透明度。原生系统控件可有 macOS 提供的表面效果。

## Shapes

Android 分组卡片、图标底托、地址框与通知框的圆角分别由 frontmatter 记录。首页连接图标底托是直径 48dp 的圆形；可操作行的图标底托为 36dp 方形，内图标为 20dp，只读事实行没有底托。返回图标为 22dp，导航箭头为 18dp。按钮与输入框形状来自系统主题。

Mac 分组与操作块采用 continuous 圆角。分组内每行 hover 填充是矩形，由整个分组裁剪外角；状态胶囊采用 `Capsule`。表单采用系统 `.roundedBorder`，圆角不在项目中另行赋值。

## Components

### Android 分组、状态行与导航

`StationChrome.card()` 提供全宽卡片。`fact()` 与 `valueRow()` 展示只读状态；`switchRow()` 使用原生 `Switch`，整行可触发切换，并将文本关联到开关供辅助功能读取；`linkRow()` 使用图标底托、文字、右侧状态和箭头表达可进入的设置。可点击行的 ripple 来自系统主题，图标不作为重复的辅助功能元素。

Android 首页只有连接状态和「MCP 服务」两张卡片。连接卡片内是无线调试实际开关、自动保持开关。41 版把 Wi-Fi 与 USB 调试整合为卡片底部的两栏只读状态条，底色使用 `android-connection-strip-*`，由原卡片裁剪下方圆角，不另建卡片。状态条最小高度 64dp，左右内边距 16dp，图标 22dp；名称为 12sp 次要色，实际状态为 14sp medium。开启时图标绿、状态正文色，关闭或未连接时两者为次要色；文字与完整辅助功能名称同时表达状态。两栏等宽，中间有 1dp 分隔线。MCP 分组保留服务开关、远程状态入口和接入详情；本机地址与长说明移到详情页。远程通道开关移到中继页，未配置时禁用。

### Android 常驻通知

41 版保留系统通知外壳，收起时是 16sp medium 的连接标题与 12sp 补充。展开后连接类型位于标题右侧，简写为「无线 · 远程」等；下方隔 10dp 放三组 16dp 图标与 12sp 状态，名称「无线」「MCP」「保持」紧接「开／关」。标题和开启组文字是正文色，只有开启图标为连接绿；关闭组与连接类型使用 `android-notification-muted-*`。恢复问题只在自动保持开启时于底部出现，保持与连接事实分开，不把已连接标题换成错误描述。通知内容底部留 8dp，图标和状态没有额外底托或背景。辅助功能组使用完整功能名；系统主题、字号或密度改变时重新发布同一条通知以刷新外观。PGT-AN20 上已检查浅色 1.0 倍和深色 1.3 倍字号的主页、通知收起与展开，以及运行中主题切换，详见 [41 版验证](docs/adb-keep.md#41-版状态条与常驻通知验证)。

上方菜单与左侧边缘滑动打开页面内侧栏，宽度最多 320dp，保留至少 56dp 遮罩区域。顶部主标题是「手机工位」，14sp 次标题「设置」下依次为通知、共享剪贴板、权限和关于，共享剪贴板入口在 42 版加入；远程通道与接入说明保留首页入口。背景延伸到系统栏，内容避让 Insets，图标明暗沿用首页。关闭按钮热区为 48dp；侧栏与遮罩跟随手指，松手后按速度和位置选择终点，120–260ms 减速动画遵循系统动画开关。纵向滚动保留；展开时首页退出辅助功能遍历，关闭后恢复。系统返回可收起，Android 14+ 返回手势跟随进度。全手势导航的边缘冲突处理见 [侧栏交互](docs/adb-keep.md#界面)。开关和导航标签允许两行，开关本身也有 48dp 最小热区与明确辅助功能名称。缺权限时首页保留直接入口。

### Android 字段、操作与反馈

`field()` 提供显式标签与原生单行 `EditText`。地址/指纹使用 URI 输入类型，令牌使用密码输入类型，已有令牌不回填；字段按地址、指纹、令牌顺序前进，令牌的键盘完成动作提交。`action()` 使用原生 `Button`，主按钮取状态绿与对应前景色，禁用主按钮取 `well` 与 `muted`；次按钮沿用系统主题。

远程页显示「未配置」「已关闭」「已连接」「连接中」。保存期间字段与操作禁用，主按钮显示「正在保存…」；成功后清空令牌字段并显示连接结果说明。错误用错误色、成功用连接色，并作为 polite live region。清除配置通过原生确认对话框。

### Android 共享剪贴板

设置卡片使用现有 `switchRow()`，状态说明以文字区分共享关闭、等待 Mac、自动同步和手动共享。关闭共享时，「自动双向同步」保留保存的开关偏好，`Control.setEnabled()` 同时禁用开关与整行点击，并将整行不透明度设为 0.5；图标按当前实际可用状态绘制。配置期间两行均禁用。

两端预览使用可选择的正文文本，超出八行以省略号收尾。Mac 预览下方的「复制到手机剪贴板」沿用原生主按钮，只有共享开启、Mac 在线且预览为文字时可用；复制结果在按钮下方说明。共享关闭时两端显示「开启共享后显示」，锁屏、敏感内容、非文字与超长文字用状态文字说明。说明卡片保留 Shizuku 权限入口。

### Mac 分组、导航与操作块

`elevatedGroup()` 提供底色、裁剪与边界。`StationRows.navigationRow()` 使用 SF Symbols、可选辅助文字和右箭头；子页标题是返回按钮。`ActionTile` 使用图标上、文字下的排列，有默认、启用与禁用状态；禁用且非启用状态时整体不透明度为 0.4，启用状态仍可表达正在运行的操作。自定义按钮按下时不透明度为 0.62，hover 为系统 label 色 6% 的填充。

首页连接状态胶囊用对应状态色文字与 14% 不透明度的底色；电量与连接说明独立显示。远程在线且 adb 不可用时，首页保留 MCP 连通说明并解释屏幕操作需要 adb，相关操作按实际能力禁用。开关沿用原生 small switch；不可操作行整体不透明度为 0.4。

### Mac 共享剪贴板

首页导航组新增「共享剪贴板」与当前状态摘要，复用 `navigationRow()`；子页复用返回页头、`toggleRow()` 与 `elevatedGroup()`。未连接 MCP 时显示说明和原生主按钮「连接 MCP 服务」；共享关闭时显示开启说明，共享开启时显示两端预览。自动同步开关仅在共享开启且没有任务执行时可用，沿用现有整行禁用外观。

预览标题与正文沿用现有字段正文尺寸，标题为 semibold，文字可选择并在固定高度区域滚动。「复制到 Mac」「复制到手机」使用原生 borderless 按钮；手机未连接、任务执行中或对应内容不是文字时禁用。反馈沿用 `feedbackBanner()`，底部说明沿用系统 caption 与次要文字色；没有为预览新增色彩、阴影或自定义控件状态。

### Mac 字段、通道与配置反馈

MCP 通道行分别显示「正在使用」「可用」「未连通」，有文字状态配合颜色。可用时提供地址与 Authorization 的复制动作，令牌通过复制使用，不展示明文。停止手机 MCP 服务需要 adb；仅远程时按钮禁用并附原因。

远程设置使用原生 `TextField` / `SecureField`、系统圆边框、显式标签和 checkbox。默认只保存 Mac，无需 adb；「同时配置手机」显示第二枚密码字段和 adb 条件说明。主操作沿用 `.borderedProminent`，绑定默认键盘动作；仅当前配置存在时显示清除入口，并通过原生确认对话框处理。读取配置或执行任务期间字段与相关动作禁用，离开页面清空输入的秘密资料。错误固定在保存按钮前并使用更新提示辅助功能；通用反馈组件可带 small `ProgressView`。

Mac 操作块状态切换使用 0.16 秒 ease-out，hover wash 使用 0.12 秒 ease-out；没有本次提取可确认的其他自定义动效。
