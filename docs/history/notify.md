# notify 历史验证记录

以下为迁移前的日期、版本、测试与部署记录，叙述中的“当前”“本轮”均指当时。不能据此判断今天运行的版本或把模拟测试当成生产验收。当前规则见 [notify.md](../notify.md)，部署状态见 [发布记录](../releases.md)。

### 构建与验证

自动验证包含 `NotificationSyncStateTest` 的白名单、自己应用排除、权限撤销、重复内容与更新、关闭与取消选择、断线基线、队列上限和 Unicode 截断；`NotificationSessionTest` 覆盖 Mac 授权不自动申请、首次基线、重复事件、未知结果、暂停及路由改变时丢弃在途结果。MCP 回环验证三个新工具和必填参数，Go 验证状态可重试而接收及配置不可重放。实机界面与真实应用通知的验证情况以本节后续记录为准；构建通过不代表已经授予系统通知使用权或收到真实应用通知。

2026-10-03，两端构建与自动测试通过，PGT-AN20 已保留数据升级到 47，Mac `/Applications/手机工位.app` 已更新为 0.1.0 (5) 并运行，签名与已安装可执行文件核对通过。远程安装检查在提交前发现 Shizuku 未运行，改用 `android.sh --adb` 升级，没有生成或重放安装任务。手机仍能报告无线与远程同时连接；新三个通知工具已由实际 `tools/list` 返回，状态实测为关闭、未授权、监听未连接、选择数量 0。

PGT-AN20 已检查浅色 1.0 倍字号的两个通知分组、应用列表和搜索；输入 `2` 可按名称与包名过滤列表，选择数量保持 0，未勾选真实应用。点击通知使用权能打开系统设置，未授予权限。深色 1.3 倍字号已检查页面上部与下部滚动内容，文字和操作未裁切；结束恢复原来的浅色与 1.0 倍字号。UiAutomator 在子页有时返回 null root，实际点击与布局通过截图和窗口组件名核对。Mac 未授权、就绪与拒绝授权的浅色/深色图片来自模拟数据的 `NSHostingView` 原生渲染；这些图片不证明系统权限已获准或真实通知已展示。当前工具没有取得真实 Mac 面板的辅助功能树。评审图片在忽略的 `.impeccable/review/phone-notifications-47-*`、`phone-notification-apps-47-*` 与 `mac-notifications-*`；旧 Mac 包在忽略的 `build/.phone-station/backups/notifications-before-47-*`。真实应用通知的端到端转发仍需用户完成两端系统授权并自行选择应用后验证。

### 48 版图标横幅验证

2026-10-03，Android 48 和 Mac 0.1.0 (6) 已保留数据升级，手机原有同步开关、通知使用权、监听服务与微信选择保持。远程安装预检发现 Shizuku 未运行，未提交安装任务，改用 `android.sh --adb`；Mac 更新前的完整应用包保存在忽略的 `build/.phone-station/backups/notifications-before-48-*`。已安装 Mac 可执行文件与构建结果相同，代码签名校验通过。

实际 `station_notification_icon` 读取 `com.tencent.mm` 返回 96 像素 PNG（3841 字节），未选 `com.android.settings` 返回 `available: false`。Mac 接收测试覆盖图标缓存、图标失败保留文字，以及读取图标期间暂停不弹出迟到横幅；横幅队列测试覆盖同 key 替换、三条上限、悬停、到期、关闭与清空。Go race、Android 全部测试和 Mac 构建通过。原生离屏渲染已检查浅色、深色、长标题/正文截断和无图标回退，以及新版 Mac 设置页；截图在忽略的 `.impeccable/review/mac-banner-*` 和 `mac-notifications-6-*`。这些图片不证明实际窗口的背景材质或交互行为；实机预览操作仍需单独核实。

此前用户已手动授予两端权限并选择微信，实测微信消息进入 Mac 通知中心。10:45–10:46 的系统日志显示手机工位已请求 banner，但 NotificationCenter 报 `muted by DND suppression: delay`；系统设置确认勿扰模式开启、允许的 App 都不允许。用户调整后已确认原生桌面横幅正常。

### Mac 7 版玻璃与圆角

用户的 6 版实机预览截图显示圆角外露出白色矩形，边缘带明显灰线；仅设置 `CALayer.cornerRadius` 和 `masksToBounds` 不能保证背景材质与窗口阴影一起裁剪。旧系统的 popover 实现现使用可拉伸圆角 alpha 图设置 [NSVisualEffectView.maskImage](https://developer.apple.com/documentation/appkit/nsvisualeffectview/maskimage)，同时保持宿主内容透明并裁剪四角，每次重新布局刷新窗口阴影。

根据用户的液态玻璃要求，macOS 26 及以上改用 Apple 公开的 [NSGlassEffectView](https://developer.apple.com/documentation/appkit/nsglasseffectview)：regular 样式、12pt 圆角，内容通过 `contentView` 嵌入，不加自绘底色或描边。原有图标、6 秒计时、悬停、关闭与通知中心记录不变；macOS 13–25 使用上面的圆角 popover 回退。

2026-10-03，Mac 0.1.0 (7) 已更新，签名校验通过，已安装代码与编译结果匹配，构建包与已安装可执行文件一致；Android 本次无需更新，保持 48。自动回归核对生产遮罩在 1×/2× 下四角透明、中心和直边完整。CUA 已检查本机 macOS 26.6.2 上三种真实 AppKit fixture 窗口：浅色、深色、长标题/正文，均使用生产材质组件与明确标为示例的文字，圆角未露白、文字截断正常。fixture 不连接手机或系统通知中心，不能替代已安装菜单栏 App 的完整通知交互验证；界面工具对该菜单栏入口仍返回超时。旧 Mac 包保留在忽略的 `build/.phone-station/backups/notifications-before-glass-7-*`。

### Mac 8 版背景采样

用户随后提供的 7 版实机截图仍表现为偏实心的浅色卡片。7 版的窗口检查只证明了边角和文字布局，不能作为桌面玻璃通透感达标的证据。本机「降低透明度」与「增加对比度」均为关闭，未更改任何系统外观设置。

8 版使用 `NSGlassEffectView` 的 clear 样式，下面单独放置 `NSVisualEffectView` 的 underWindowBackground/behindWindow/active 背景采样层，采样层 alpha 为 0.65。玻璃与通知内容保持完整不透明度，通知内容仍放在玻璃的 `contentView` 中，由系统处理可读性；不降低整个窗口或通知文字的透明度。透明容器、背景遮罩和宿主内容使用相同的 12pt 圆角，不增加描边。选择 clear 的依据见 [Apple 材质说明](https://developer.apple.com/design/human-interface-guidelines/materials)，实际可读性按界面验证判断。

2026-10-03，Mac 0.1.0 (8) 已保留配置更新并启动，签名、编译代码和构建包核对通过；Android 保持 48。队列与 1×/2× 圆角遮罩测试、Mac 编译及脚本语法检查通过。CUA 已核对生产组件在真实 AppKit 窗口内部示例背景上的浅色、深色与长消息表现：三色色块可透入，背景大字被模糊，通知正文保持清楚，长文截断和四角正常。只降低材质层 alpha 的中间版本会使背景文字干扰正文，未发布。最终保持完整原生玻璃层，保留通知中心记录和计时逻辑。

窗口级截图不能完整证明跨应用、桌面背景采样的最终合成效果，菜单栏 App 的实际横幅仍需用户预览核实；不要把内部色块 fixture 或已接入原生 API 直接描述成桌面效果已经验收。旧包保留在忽略的 `build/.phone-station/backups/notifications-before-glass-8-*`。

### Mac 9 版桌面材质根

用户反馈 8 版实际横幅仍与之前相同，因此 8 版的桌面通透效果未通过验收。对照中，不抢焦点的 `NSPanel` 在 `key=false/main=false/appActive=false` 时仍能透出窗口内部色块；不能把未成为 key window 单独归因为本次问题。窗口内玻璃与跨窗口桌面采样需要分开验证。

9 版把 `underWindowBackground/behindWindow/active` 的 `NSVisualEffectView` 直接作为窗口内容根，保留完整背景模糊强度及圆角材质遮罩，不再在它外面套裁切容器。原生 clear 玻璃作为 0.24 不透明度的装饰层；文字和图标放在最上层的独立透明宿主，保持完整不透明度。白色渐变高光表现边缘反射，降低玻璃填充对桌面材质的遮盖；高光不接收点击，减少透明度时隐藏，并保留系统材质的辅助功能回退。这是桌面模糊与玻璃装饰的组合，不等于完整原生玻璃内容适配。

本机已通过队列与 1×/2× 圆角透明边界检查、完整 Mac 编译及只读源码复查。CUA 检查与生产相同状态的非激活浮窗，浅色和深色文字、图标与边角可读；单窗口捕获不包含完整桌面合成，不能作为桌面背景透出的通过证据。尝试系统截图工具仍未取得桌面合成画面。9 版已安装并启动，签名、编译代码和构建包匹配；实际桌面效果仍需实机横幅确认，不记录为已经解决。旧包保留在忽略的 `build/.phone-station/backups/notifications-before-glass-9-*`。

### Mac 11 版原生 Clear 根修正

用户的 9 版实际截图仍显示灰白卡片，9 版没有通过桌面材质验收。本机系统「液态玻璃」已选「透明」，`NSWorkspace.accessibilityDisplayShouldReduceTransparency` 与 `accessibilityDisplayShouldIncreaseContrast` 均为 false；这次没有修改系统开关。

11 版将 [NSGlassEffectView](https://developer.apple.com/documentation/appkit/nsglasseffectview) 的 clear 样式直接设为 `NSPanel.contentView`，保持原生玻璃默认的完整不透明度，透明 `NSHostingView` 放入玻璃的 `contentView`。移除其下的 `NSVisualEffectView` 底材、0.24 不透明度的玻璃装饰以及自绘白色渐变高光，避免底材先遮住背景、再削弱玻璃的原生表现。12pt 圆角与阴影由原生窗口材质处理，macOS 13–25 的圆角 popover 回退不变。图标、计时、悬停、点击与通知中心记录的行为没有修改。

2026-10-03，生产组件的真实 AppKit 对照中，相同独立背景窗口和附属浮窗条件下，9 版偏灰白，而新原生 clear 根能透入蓝、紫、绿色，背景文字软化、前景可读。这是受控 child-panel 合成证据，不替代独立跨应用桌面验收。分别创建独立背景应用与非激活 accessory 浮窗后，CUA 单窗口捕获仍排除后方窗口；通过系统「预览 → 文件 → 截屏 → 从整个屏幕」取得的画面实际上被工具隐私遮罩覆盖，因此这张截图无效，不作为材质通过的证据。评审 disposition 为 `recapture`，仍需独立桌面的有效证据或用户实际反馈，不能声称此项已经验收。

Mac 通知会话与横幅队列、1×/2×/4× 回退材质透明圆角测试通过，完整 Mac 代码已编译。`/Applications/手机工位.app` 已保留配置更新并启动为 0.1.0 (11)，严格签名校验通过，已运行包的代码段与编译结果一致，构建包与已安装可执行文件相同。更新从当时已安装的完整 Mac 包复制资源，保留并发工作；本次未更新手机。旧完整包保存在忽略的 `build/.phone-station/backups/notifications-before-native-glass-11-*`。这份安装核对不等于桌面视觉验收。

### 51 版首页入口验证

2026-10-03，Android 构建与构建内测试通过，PGT-AN20 经远程保留数据升级到 51。安装任务 `a6c82e14214f4906b12bb9fac2fbe14d` 返回 `completed=true`、`verified=true`，升级后服务与远程通道恢复。升级前后只读通知状态均为同步开启、通知使用权已授权、已选 1 个应用、监听已连接、Mac 在线；本次未发送测试通知或更改通知配置，Mac 包无需更新。

实机截图确认浅色 1.0 倍与深色 1.3 倍字号下的首页顺序是连接、共享剪贴板与通知、MCP 服务；点击「通知」可进入原来的双向设置页，侧栏设置分组仅保留权限与关于。深色放大字号下，首页入口与通知页文字未裁切，详情可以滚动。UiAutomator 在页面切换后有时仍返回旧首页树，实际目标页通过截图核对，不以旧树判定点击失败。检查结束恢复原来的浅色与 1.0 倍字号。截图保存在忽略的 `.impeccable/review/phone51-*`。



### 46 版显示方式验证

PGT-AN20（Android 15）上，46 版经远程保留数据升级，APK 校验、应用启动与远程恢复均确认完成。通知设置页显示「只显示最新一条」和「每条都显示」；选择逐条显示后，离开并重新进入仍保留选择。

手机选择逐条显示时，经中继发送两条带旧 `mode=replace` 的提醒，返回的实际模式均为 `stack`，系统记录中留下两条不同标记的提醒。切回最新一条后，运行带旧 `--stack` 的 `notify.sh`，返回「通知已更新」，此前两条逐条提醒被清除，只保留 `alert`、id 2。两轮中常驻状态通知都保留。测试使用系统的短提示音文件，未改铃声选择；结束时核对原铃声 Copper、提醒弹出开关均保留，显示方式恢复为默认的最新一条。Android 构建及构建内测试、9 项通知路由测试、Python 编译、zsh 语法与文档镜像检查通过。

这台设备的 `uiautomator dump` 在选择框打开时有时仍返回底层通知页，铃声页也遇到过空根节点。不能仅凭底层文本判定选择框没打开，先核对 `dumpsys input` 的焦点窗口；操作后用设置行的当前值和实际通知结果确认。



2026-10-01，应用 `versionName` 13，PGT-AN20。安装脚本打印了「已勾选横幅通知」。`popup2` 是 live，`mImportance=4`，`mOriginalImp=4`，`mUserLockedFields=4`，`mSound=android.resource://dev.phonestation.adbkeep/raw/silence`。`banner` 和 `popup` 是 deleted。通道设置里「横幅通知」勾着，「通知铃声」写着 `silence`。接着 `./scripts/notify.sh --title 会话提醒 --text 横幅测试`，标准错误是 `通知已更新`，铃声是 `/system/media/audio/notifications/Pixies.ogg`，最后一行是 `played`。当时人在桌面，屏幕上方弹出「手机工位 / 会话提醒 / 横幅测试」。记录里标记 `alert`、id 是 2、重要程度 4、标志是 0，`naturalImportance=4`，`posttimeToFirstVisibleExpansionMs=185`。

2026-10-03，`versionName` 57。Agent 图标改走 `setSmallIcon`，不再放 `setLargeIcon`。远程保留数据升级后，手机显示方式仍是只显示最新一条。依次发出 Grok、Claude、Codex，记录里标记 `alert`、id 是 2，图标资源分别是 `ic_agent_grok`、`ic_agent_claude`、`ic_agent_codex`，没有 `android.largeIcon`。下拉栏左侧分别是这三家的彩色图标，抬头仍是「手机工位」，右侧没有第二张图。常驻「电脑已连接」仍用手机工位的小图标。验证时铃声文件用了一段无声采样，通知已更新，但这段文件没有播出来。

2026-10-01 在 PGT-AN20 上，应用是 `versionCode=5`。先用 `am broadcast` 叫接收器，结果是 `Broadcast completed: result=1`。记录里包名是 `dev.phonestation.adbkeep`，用户 0，标记 `alert`，id 是 2，重要程度 3，标志只有只提示一次，`mSound=null`，`isNoisy=false`。通道重要程度也是 3，声音是 null，震动关着，没有角标。图标来自这个应用自己。接着 `./scripts/notify.sh --title 手机工位 --text 脚本已交给应用`，标准错误是 `通知已更新`，铃声是 `/system/media/audio/notifications/Pixies.ogg`，最后一行是 `played`。这条的内容改成了「脚本已交给应用」。常驻那条还在：通道重要程度 2，内容是「无线调试开着」，标志是常驻、只提示一次、不可划掉、前台服务，`mSound=null`。日志是 `AdbKeep: alert replace`。无线会话没有断。`--stack` 和强行停止之后的广播这次没有再跑。

2026-10-02，PGT-AN20 的 `versionName` 33：退出 Mac 菜单栏 App 后，只断开电脑端无线 adb，手机无线调试开关保持开启。网关切到 `channel=remote`；通过已安装 App 随包的 `notify.sh` 发出「远程通知验证」，返回「通知已更新」和 `played`。恢复 adb 后在系统通知记录中找到对应标题和正文。单元测试覆盖没有 adb 时发送、提交前回退、提交后连接中断不重发，以及 Agent、stack 和指定铃声参数。

2026-10-01，应用 `versionName` 15，PGT-AN20。`settings get global mode_ringer` 是 1，也就是震动；扬声器媒体音量是 8。系统通知铃声是 `content://media/internal/audio/media/223?title=Pixies&canonical=1`。`./scripts/notify.sh --title 会话提醒 --text 铃声改由应用播放` 的标准错误是 `通知已更新`，标准输出是 `played`。通知记录里包名是 `dev.phonestation.adbkeep`，标记 `alert`，id 是 2，标题和内容就是这两句，重要程度 4，`naturalImportance=4`，`posttimeToFirstVisibleExpansionMs=199`。通道声音仍是 `silence`。同一秒 `dumpsys audio` 里这个应用（uid 10258，pid 与 `pidof` 相同）新建了 `MediaPlayer`，属性接着是 `USAGE_MEDIA`、`CONTENT_TYPE_SONIFICATION`，大约一秒后停止并释放。不是 shell 的 `AudioTrack`。接着 `./scripts/notify.sh --sound /system/media/audio/notifications/Bell.ogg --title 会话提醒 --text 指定铃声`，同样是 `通知已更新` 和 `played`。这次还是这个 uid 的 `MediaPlayer`，属性相同，大约两秒后释放。
