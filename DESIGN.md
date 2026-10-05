---
name: 手机工位
description: Android 应用与 Mac 菜单栏面板的原生界面事实记录
colors:
  android-held-light: "#2E8552"
  android-held-dark: "#76B189"
  android-on-held-light: "#FFFFFF"
  android-on-held-dark: "#101114"
  android-waiting-light: "#995A18"
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

本页同时维护后续开发约束与当前组件实现。历次原生渲染、真实窗口和桌面材质验证见 [设计历史](docs/history/design.md)。macOS 26 及以上使用原生 clear 玻璃根；独立跨应用桌面材质验收仍未关闭，不能以离屏截图或安装核对代替。

## 开发约束

简化操作时仍要让核心能力清楚可见。以下保留后续容易回退的产品约束；具体组件沿用下文，功能与权限语义以 [功能开关](docs/features.md) 为准。

| 页面 | 职责 |
| --- | --- |
| Android 首页 | 连接概览、暂停／恢复，以及 MCP 服务、剪贴板、通知等核心入口；不堆配置，也不为精简隐藏核心能力 |
| 连接设置 | 管理本地／远程两种连接方式，按需查看诊断；不重复首页的大连接状态卡 |
| MCP 服务 | 展示服务可用性、能力入口与接入方式；地址和技术说明按需展开 |
| 功能详情与设置 | 详情处理当前功能及缺项，设置集中低频偏好与完整功能管理 |

Mac 按电脑端的高频操作组织页面，手机侧负责功能选择、授权和状态查看。两端统一概念与状态含义，不要求照搬布局。新增能力先明确归属，再决定是否增加首页入口或新页面。

- **连接方式、服务和能力分开。** 本地／远程是连接方式，MCP 是服务，文件、命令、屏幕等是能力。不要再用含糊的分组混放；本地 MCP 与远程接入独立选择，启用远程不顺带开启本地，关闭本地不打断仍在使用的远程客户端。
- **开关表示选择，状态表示事实。** 缺权限保留开启选择并给出处理入口；保存成功不等于连接成功，服务运行不等于功能可用。整体暂停／关闭优先于旧在线缓存；仅远程运行也要计入 MCP 可用状态。
- **聚合状态覆盖完整对象。** 通知入口同时考虑两个方向，MCP 同时考虑两种接入；一个正常项不能掩盖另一个故障。未使用的功能不报错，正常等待 Mac 与需要处理的缺项分开显示；同一原因不重复报警。
- **一个控制只表达一件事。** 分组级开关随标题放在右侧，省去重复开关行；独立子功能保持独立。页面重组保留原有选择、授权和恢复语义，不增加作用重叠的总开关，不用开关联动掩盖依赖关系。
- **正常时收起说明，异常时给出下一步。** 低频配置、诊断和长说明按需展开；当前阻塞、保存反馈和正在执行的操作保持可见。提示直接进入对应功能、权限或接入页，返回后刷新状态，不自动补执行旧操作。
- **入口迁移要完整。** 同时维护首页、深链、通知入口、缺项跳转、返回路径及用户文档。返回实际上一层，不固定跳回首页；连接图标保留短文字状态，不能只靠高亮表达含义。

改动时按 [界面改动验收](docs/validation.md#界面改动验收) 选择相关情形核对。这里维护长期规则；版本、安装摘要与实际验收结果留在历史和发布回执中。

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

Mac 面板宽度固定为 360pt，主体垂直排列，高度随页面内容决定。首页由顶栏设置入口、可点击的连接概览、三枚等宽操作块（间隔 8pt）、可展开的手机控制和常用导航组成。设置页使用有界滚动，手机管理、高级工具与 Mac 偏好分组。MCP 页显示聚合服务状态、分组能力入口与连接设置；地址和服务操作默认收起。连接页按本地／远程管理，配对说明与连接检查默认收起。

通知横幅在指针所在屏幕的可用区域右上方纵向排列，正常宽度与内边距见 `mac-notification-banner`。窄屏按 `min(360, max(240, visible.width - 32))` 调整宽度；顶部与右侧分别留 12pt、16pt，横幅间隔 8pt，高度随内容增长且最小为 94pt。图标、文字和关闭按钮顶对齐，列间距与文字组内间距分别使用 `mac-banner-column-gap`、`mac-banner-text-gap`。超出可用屏幕底部的横幅不显示，没有网页断点。

共享剪贴板沿用两端现有单列布局。Android 依次排列共享设置、同步状态和默认收起的使用说明；Mac 在开关组下排列状态与使用说明，不再为正文预览固定高度。两端沿用现有页面侧边距、分组圆角与底色。

Mac 已配置的远程页默认只显示连接概览，滚动区域最高为 160pt并随内容收拢；点「修改连接配置」展开表单。首次配置直接显示表单。仅配置 Mac 时滚动区域最高为 316pt，勾选「同时配置手机」后为 400pt。保存区位于滚动区域下方，错误先于保存按钮排列；错误随内容换行，不被表单滚动带走。Android 远程页依次为连接概览、配置卡片、电脑端说明，错误/保存结果在配置说明之后、保存按钮之前。

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

通知页的「手机通知 → Mac」与「电脑提醒 → 手机」使用 `groupSwitch()`，总开关位于卡片标题右侧，省去重复的独立开关行。标题保持分组标题字号并可换行，原生开关保留至少 48×48dp 的点击范围和完整功能名供辅助功能读取；应用选择、实际状态和提醒细项位于标题下方。正常权限和完整规则进入默认收起的「使用说明与权限」；缺项仍在对应方向下直接给出处理入口，保存反馈紧邻开关。

Android 首页展示连接概览，以及 MCP 服务、共享剪贴板、通知三个核心入口。MCP 副标题说明文件、命令、屏幕与手机控制；右侧服务摘要合并本地与远程状态，不把服务运行等同于功能权限就绪。顶栏使用 48×48dp 原生齿轮按钮进入设置，保留「设置」辅助功能名称和长按提示，不再使用侧栏。连接概览保留整体连接标题，下方以图标和短标签分别显示本地与远程通道，真实在线时使用状态绿，断开时使用灰色带斜线图标，确认中的远程通道有等待色图标和文字状态；本地类型按实际通道显示无线、USB 或本地，不把无线调试开关当成连接事实。

连接卡片下方提供「连接设置」与「暂停／恢复使用」两枚等宽原生按钮，使用 `well` 浅底、12dp 圆角、20dp 功能图标及系统点击涟漪，至少 48dp 高。大字号或不足 340dp 的窄屏会把通道指示与操作改为纵向排列。暂停保留选择与配置，不关闭系统无线调试；保存期间禁用按钮并显示进度。协同行将名称、使用说明和实际同步状态放在同一行组，状态不以开关选择代替。

连接设置 `ConnectionActivity` 不重复首页的连接概览，按「本地连接」「远程连接」呈现。本地组管理无线调试和自动保持，并显示只读 USB 调试状态；远程组展示带状态的远程接入入口。底部「连接检查」默认收起，展开后逐项显示 Wi-Fi 网络、本地电脑实际连接、本地 MCP、远程通道与调试写入权限，并提供 MCP 与权限检查入口。检查项不把调试开关等同于电脑连接，大字号或不足 340dp 的窄屏使用上下排列；折叠状态随页面重建恢复。

MCP 服务页 `McpHelpActivity` 从首页直接进入，上方用紧凑状态行区分运行中、部分可用、连接中、需设置、未开启与已暂停。状态由 `McpStatus` 合并本地监听、远程配置与实时连接；总开关关闭优先于延迟到达的在线事实。能力按文件访问、命令与安装、屏幕与手机控制分组，点击进入仅含该组的功能管理，保留缺权限的直接处理入口。接入方式中管理本地 MCP，远程连接独立配置；关闭本地监听不重启已运行的远程客户端。保存反馈紧邻开关，失败不伪报成功，暂停中明确恢复后生效。地址和长说明默认折叠，令牌不显示。原有 `EXTRA_PAGE=mcp` 及剪贴板、通知、权限处理入口统一打开此页。

设置页保留完整功能管理、权限与检查、远程访问范围、连接设置和关于。单项功能选择独立保存；页面分组不会扩大功能或远程授权范围。

### Android 常驻通知

保留系统通知外壳、应用标识、展开按钮和点击主体打开首页的行为。收起时使用 16sp medium 的连接标题与 12sp 补充；补充优先显示当前问题，另一路在线不能掩盖已选择通道的异常或确认状态。超过 1.3 倍字号时，收起区改用系统标题／正文模板，避免把两行自定义内容硬塞进受限高度。

展开后标题独占一行区域，下方两列分别显示本地与远程连接，再用一行两列显示 MCP 实际状态和自动保持选择。每格使用 16dp 图标，文字与图标隔 6dp；12sp 名称和 12sp medium 状态上下排列，隔 2dp，不再挤在三个等宽的短句中。两行间隔 10dp，列间隔 12dp，底部留 8dp；文字随可用宽度换行，图标没有底托或背景。本地按实际通道显示无线、USB 或本地，不把无线调试开启当成连接。未使用的远程与关闭的功能使用次要色；在线图标使用连接绿，确认／缺项使用提示色，自动保持已开启仍使用中性色。

MCP 复用首页的本地／远程服务汇总，区分运行中、部分可用、连接中、需设置、未开启和暂停。自动保持显示已开启／已关闭的选择，恢复问题单列在底部；问题不覆盖仍在线的连接标题。手动处理项提供原生通知操作，进入 MCP、连接、权限或系统 Wi-Fi 设置；自动恢复和退避不添加无用按钮，不显示倒计时。每格辅助功能使用完整名称和实际状态。系统主题、字号或密度改变时重新发布同一条通知刷新外观，不因纯外观变化叫回已划掉的通知。旧版实机结果见 [41 版验证](docs/history/adb-keep.md#41-版状态条与常驻通知验证)，本次代码与待验收边界见 [68 版验证](docs/history/adb-keep.md#68-版常驻通知分层)。

页面保持原生系统返回、滚动与系统栏避让。首页设置与连接操作保留最小 48dp 的点击范围；开关和导航标签允许两行。首页权限问题保留可点击处理入口。旧侧栏的手势验证留在 [设计历史](docs/history/design.md)，不作为当前导航规则。

### Android 字段、操作与反馈

`field()` 提供显式标签与原生单行 `EditText`。地址/指纹使用 URI 输入类型，令牌使用密码输入类型，已有令牌不回填；字段按地址、指纹、令牌顺序前进，令牌的键盘完成动作提交。`action()` 使用原生 `Button`，主按钮取状态绿与对应前景色，禁用主按钮取 `well` 与 `muted`；次按钮沿用系统主题。

远程页显示「未配置」「已关闭」「已连接」「连接中」。保存期间字段与操作禁用，主按钮显示「正在保存…」；成功后清空令牌字段并显示连接结果说明。错误用错误色、成功用连接色，并作为 polite live region。清除配置通过原生确认对话框。

### Android 共享剪贴板

50 版与 Mac 0.1.0 (10) 将共享页改为自动同步状态页。手机首页的共享剪贴板入口复用 `statusLinkRow()`；侧栏移除重复入口。详情页在「自动同步」标题右侧提供共享开关，并保留独立的 Mac 图片存入相册选项。下方显示同步状态及必要的权限／接入处理入口；旧版或 MCP 的暂停模式显示恢复按钮。「使用说明与权限」默认收起，包含 Mac 连接状态、首次连接重新复制、图片限制与隐私说明。两端剪贴板正文与手动复制按钮已移除，页面打开不额外读取系统剪贴板。

Mac 共享页沿用页头、开关行与分组：一个共享开关、一组同步状态，以及使用与隐私说明。同步就绪使用既有连接绿；依赖阻塞使用提醒色，并提供手机权限入口。只有实际完成同步才显示最近方向和时间，空闲心跳与首次核对不更新。关闭共享后不读取 Mac 剪贴板。此轮没有新增配色、字号或组件世界。

### Mac 分组、导航与操作块

`elevatedGroup()` 提供底色、裁剪与边界。`StationRows.navigationRow()` 使用 SF Symbols、可选辅助文字和右箭头；子页标题是返回按钮。`ActionTile` 使用图标上、文字下的排列，有默认、启用与禁用状态；禁用且非启用状态时整体不透明度为 0.4，启用状态仍可表达正在运行的操作。自定义按钮按下时不透明度为 0.62，hover 为系统 label 色 6% 的填充。

首页连接概览展示设备名称、连接状态、连接方式与电量，整块可点击进入连接与配对；不再重复显示状态胶囊。右上角齿轮直接进入设置，有待处理问题时在齿轮旁显示提醒图标。亮屏、手电筒和声音灯光位于可展开的手机控制，正在使用的项目在收起时也有文字摘要，进入首页时自动展开。远程屏幕操作按实际能力启用，只有依赖缺失才显示直接处理入口。开关沿用原生 small switch，不可操作行整体不透明度为 0.4。

### Mac 共享剪贴板

首页导航组保留「共享剪贴板」与当前状态摘要，复用 `navigationRow()`；子页复用返回页头、`toggleRow()` 与 `elevatedGroup()`。只有一个共享开关，开启时自动同步；未连接 MCP 时显示说明和原生主按钮「连接 MCP 服务」。状态组显示同步就绪、暂停或具体依赖阻塞，有必要时提供权限处理或恢复自动同步按钮。最近同步的方向和时间使用系统 caption，时间使用等宽数字。反馈沿用 `feedbackBanner()`，底部说明沿用系统 subheadline/caption 与次要文字色；不再展示两端正文与手动复制按钮。

### Mac 字段、通道与配置反馈

MCP 服务摘要合并已选择接入的真实状态；仅远程运行仍计为可用，暂停和关闭覆盖旧在线事实。通道详情在连接设置的「连接检查」中展开，使用文字配合状态色。可用时在默认收起的「MCP 接入信息与服务操作」中复制地址与 Authorization，令牌不展示明文。停止手机 MCP 服务需要 adb；仅远程时按钮禁用并附原因。

远程设置使用原生 `TextField` / `SecureField`、系统圆边框、显式标签和 checkbox。默认只保存 Mac，无需 adb；「同时配置手机」显示第二枚密码字段和 adb 条件说明。主操作沿用 `.borderedProminent`，绑定默认键盘动作；仅当前配置存在时显示清除入口，并通过原生确认对话框处理。读取配置或执行任务期间字段与相关动作禁用，离开页面清空输入的秘密资料。错误固定在保存按钮前并使用更新提示辅助功能；通用反馈组件可带 small `ProgressView`。

### Mac 手机通知与应用图标横幅

通知设置页复用返回页头、`toggleRow()`、`elevatedGroup()` 与反馈条。接收开关下方新增「显示应用图标横幅」，默认开启；开启时显示原生「预览横幅」按钮，预览正在执行、Mac 通知权限未授权或暂停接收时禁用。说明明确指出悬停保持、点击横幅打开本页、系统通知中心保留记录，以及自定义横幅独立于系统专注模式；关闭此项恢复系统横幅。

手机图标接口导出已选择应用的 96×96px PNG，Mac 用横幅图标尺寸显示；缺图或解码失败时使用次要色的 SF Symbol `app.fill`，不丢弃通知。应用名和关闭符号使用 `.secondary`，标题与正文沿用默认语义文字色。关闭符号为 10pt semibold，按钮有明确辅助功能标签；图标不重复进入辅助功能遍历。

源码规定横幅 6 秒后收起，悬停时保持，移开后重新计 6 秒。队列最多三条，最新在上；同一包名和通知 key 的更新替换原横幅。点击主体收起当前横幅并打开 Mac「手机通知」页，关闭按钮只收起当前条目；关闭自定义横幅或暂停接收会清空正在显示的横幅。屏幕休眠、会话失活或屏幕配置变化也会收起。自定义模式下系统通知中心只保留 passive 记录，系统预览设为永不显示时横幅使用「收到一条新通知」并隐藏正文。这些行为由源码定义，当前截图评审没有完成实机交互验证。

Mac 操作块状态切换使用 0.16 秒 ease-out，hover wash 使用 0.12 秒 ease-out。通知横幅首次进入时以原生 `NSAnimationContext` 在 0.18 秒内从右侧偏移 12pt 回到原位；系统减少动态效果开启时跳过进入动画。

### 手机功能开关（Android 67 / Mac 30）

手机首页连接概览内的「暂停／恢复使用」控制总开关；「设置 → 功能管理」管理各项功能。功能页按通知与剪贴板、文件与命令、画面与控件分组，每项保留原生开关，只在缺少权限时显示状态原因和处理入口。Mac 从设置进入完整手机功能管理，从首页 MCP 服务进入对应能力分组。远程访问仍由手机独立授权，配色与原生组件沿用已有规则。旧版实机验证见 [功能开关历史](docs/history/features.md)。


### 设置反馈与返回

首页通知摘要同时考虑手机通知同步和电脑提醒：独立启用时分别显示「同步已就绪」或「提醒已开启」，两个方向可用时显示「均已就绪」；缺项优先提示，暂停覆盖运行状态。通知功能开关在后台保存，失败恢复显示实际选择；功能管理的保存反馈放在被操作项下方。暂停时可保存偏好，并明确恢复后生效。

`statusLinkRow()` 在大字号或小于 340dp 的窄屏上把状态放到名称下方，允许完整换行；普通字号的状态最多两行。`valueRow()` 同样支持上下排列，开关标签不再限制两行。浅色提示文字使用更深的 `android-waiting-light`，深色保留原有语义色。说明折叠控件保留展开状态、原生点击反馈和辅助功能描述。

Mac 子页沿进入路径返回；从首页与设置进入同一页面时，返回目标各自正确。返回已有上级会收拢路径，不累计返回循环。
