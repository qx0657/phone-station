# 通知

手机 App 首页的「通知」设置分成两个方向：「电脑提醒 → 手机」控制电脑脚本与 AI 会话发来的显示和铃声；「手机通知 → Mac」只同步手机上选择的应用通知。日常用法见 [../README.md](../README.md) 的「会话提醒」与「手机通知同步」。电脑提醒的下拉通知和铃声默认都由手机上的「手机工位」完成。`--shell` 仍用原来的 shell 方式，铃声转成 PCM。下面保留这条路径的设备差异，并记录通知同步实现。

## 手机通知同步到 Mac

Android 51 将「通知」入口从设置侧栏移到首页，与共享剪贴板共用一组，位于 MCP 配置前。首页通过只读 `PhoneNotifications.status()` 显示同步中、等待 Mac、未授权、未选应用、服务未开、等待服务或同步未开，不读取正文、不建立接收会话。同步开关、已选应用、授权与电脑提醒设置继续沿用原值。

Android 47 新增 `PhoneNotificationListener`，由系统按 `BIND_NOTIFICATION_LISTENER_SERVICE` 绑定。用户在系统页授予通知使用权；不会通过 adb 或 Shizuku 自动授权。没有使用 `getActiveNotifications()` 补扫现存通知。`PhoneNotifications` 单独保存同步开关和包名白名单，默认关闭、默认空白名单，升级不改变原来的电脑提醒设置。应用列表按桌面入口查询可见包，manifest 的 `<queries>` 声明 `MAIN` / `LAUNCHER`，不要求 `QUERY_ALL_PACKAGES`。搜索名称或包名不改变选择；逐项立即保存，已选的不可用应用仍可取消。

监听先核对当前用户、开关、系统授权、监听连接、包名白名单和 Mac 接收会话，再读取 extras。手机工位自身、常驻通知、分组汇总及空内容跳过。标题来自 `EXTRA_TITLE`，正文依次取 `EXTRA_BIG_TEXT`、`EXTRA_TEXT`、最后一条 `EXTRA_TEXT_LINES`。标题最多 200 个、正文最多 4000 个 UTF-16 单元，截断不拆开 emoji 的代理对。Android 15 对检测到验证码的通知可能隐藏内容，遵循 [Android 15 通知监听保护](https://developer.android.com/about/versions/15/behavior-changes-all#otp-redaction)，不尝试绕过。

`NotificationSyncState` 是可在电脑上测试的纯 Java 状态。在线会话租期 15 秒，队列最多 64 条、事件最多保留 20 秒；到期安排主线程清理，即使没有后续请求也不长期留存。选择变化、关闭同步、授权撤销或监听断开会更换会话并清除队列。首个 cursor、未知 cursor、新客户端及租期过期都只建立基线，不返回先前事件。Mac 未接收时不收集正文。去重按通知 key 和内容；内容改变时产生新事件，Mac 用包名与 key 的 SHA-256 作为系统条目标识。

同一个 MCP 服务新增：

| 工具 | 行为 |
| --- | --- |
| `station_notification_status` | 只读状态与已选数量，不返回内容、不建立接收会话；网关可重试 |
| `station_notification_configure` | 必须带布尔值 `enabled`；不改变应用白名单，也不授予通知使用权 |
| `station_notification_poll` | Mac 传本次运行的 `clientId` 和上次成功处理的 `cursor`，刷新接收会话；首次和断线后省略 cursor；非只读、不自动重放 |

Mac `NotificationSession` 每 2 秒检查。未允许 Mac 系统通知或本机暂停时，只查状态、不接收正文；通知授权只由页面按钮申请。暂停、路由变化及未知结果会丢弃接收基线并收起横幅，不重放旧请求。通知内容不写 App 偏好或日志；进入 macOS 通知中心后由系统管理。没有操作按钮，也不联动清除。

Android 48 / Mac 0.1.0 (6) 默认用 `NotificationBannerCenter` 的非激活 `NSPanel` 显示自定义横幅，左侧使用手机真实应用图标。新增只读 `station_notification_icon`：必须已开启同步、已授予通知使用权且包名仍在白名单里，只返回 96×96 PNG，最多 64 KiB；省略包名时读第一个已选应用供预览。不读取联系人头像、未选应用或通知正文。Mac 图标只在内存缓存，最长 10 分钟、最多 48 个；失败 30 秒后可重试，每次读取最长 2 秒，失败不丢文字通知。Mac 拒绝超限或无效 PNG，图标缺失时使用系统 `app.fill` 符号。

横幅在鼠标所在显示器可用区域右上方显示，最多 3 条，6 秒后收起；悬停时保持，离开后重新计时。更新相同包名与 key 时替换，不增加横幅。关闭按钮只收起这一条，点击正文打开「手机通知」页；窗口出现不激活 App。屏幕休眠、用户会话退出、显示器布局变化、暂停和断线会清除横幅，不在恢复后补弹。系统记录使用 [UNUserNotificationCenter](https://developer.apple.com/documentation/usernotifications/unusernotificationcenter) 的 `passive` 中断等级并且不带声音，前台 delegate 只请求 `.list`，避免第二条系统横幅或第二次声音。实际声音在横幅显示时按 macOS `soundSetting` 播放一次；预览为「永不」时隐藏标题正文。

自定义横幅独立于系统专注模式与系统横幅样式；不会申请读取专注状态的额外权限，也不声称遵守系统的专注模式或共享屏幕抑制。Mac 页明确说明这一点，提供「显示应用图标横幅」开关：关闭后恢复原来的原生 banner/list/sound，专注模式与预览由系统管理。「预览横幅」读取已选应用图标并显示明确标为示例的文字，同时留一条安静的系统记录。

## 为什么不用系统命令

`cmd notification post` 能弹出一条通知。在 PGT-AN20 上，通知对象自己的 `sound` 是 null，不能拿来当提示音。它用的通道 `shell_cmd` 仍挂着系统通知音，记录里 `isNoisy=true`。手机不在震动或静音时，这条会再响一次系统通知音。

`tinyplay` 只能播 wav，而且 shell 用户打不开 `/dev/snd/pcmC0D0p`。通知铃声是 ogg，例如现在的 `/system/media/audio/notifications/Pixies.ogg`。

手机处在震动模式时，系统会把通知、铃声、系统音量都静音。所以不走通知音量。默认由「手机工位」用媒体音量直接播放系统通知铃声。`--shell` 仍把这段铃声解码成 PCM 再播。下拉栏里的通知是另一条，通道不发声。

## 下拉栏里的通知

`notify.sh` 每次播放前都发一条通知。`--title` 和 `--text` 改标题和内容。不写时标题是「手机工位」，内容是「有一条提醒」。全局 hook 的标题是这件事，内容见下面的「全局 hook」。

默认把标题、内容和铃声交给手机上的「手机工位」。`notify.sh` 先通过 `lib/notify_mcp.py` 读取 `mcp.sh status`，网关在线就调用 `station_notify`，本地 adb 与用户配置的远程中继都可发送。网关不可用且还未提交通知时才回退 adb。业务请求已提交后，失败或结果未确认不会再走 adb 重发。adb 路径的入口是显式广播 `dev.phonestation.adbkeep/.AlertReceiver`，动作 `dev.phonestation.adbkeep.ALERT`，extra 是 `title`、`text`、`agent`，写了 `--sound` 时再加 `sound`。显示方式统一读取手机 App「首页 → 通知 → 电脑提醒 → 手机 → 显示方式」：默认「只显示最新一条」，也可选「每条都显示」。选择存入应用偏好，下一条提醒起生效，重启与升级保留；旧脚本、旧 MCP 客户端或广播传来的 `mode` 不再覆盖手机设置。没写 `sound` 时，应用先看「通知」里选的铃声；没选过就读 `Settings.System.NOTIFICATION_SOUND`。选了静音则通知照发、不播，广播结果是 4，脚本打出「铃声已静音。」，不以失败退出。应用用自己的包名和图标发出来，头上就是「手机工位」，不用再改调用包名。点通知打开应用。广播带 `FLAG_RECEIVER_FOREGROUND`，播完才结束，所以脚本会等到声音停。

Android 14 及以上，这条和 MCP 的开关只收两类来源。`am broadcast` 不公开发送方 uid，`getSentFromUid()` 是 -1，但 shell 的广播带着隐藏标志 `Intent.FLAG_RECEIVER_FROM_SHELL`（`0x00400000`）。系统会把其他应用加上的这一位去掉，所以看得到这一位就是 shell 或 root。应用自己的 `station_notify` 用 `BroadcastOptions.setShareIdentityEnabled(true)` 公开自己的 uid。对不上的广播结果是 0，日志是 `alert rejected` 或 `mcp rejected`，不发通知，也不动 MCP。判断在 `AlertSender`，打包前在电脑上跑。2026-10-01，`versionName` 29，PGT-AN20：shell 发来的一条提醒没有被拒绝，结果是 3，日志是 `alert sound missing`。这次故意给了一个打不开的铃声路径，所以没有发出通知，也没有响。同一条来源检查放行了 MCP 的打开和关掉。

`agent` 是 `Grok`、`Claude` 或 `Codex` 时，左侧图标用 `setSmallIcon` 换成这个 Agent 的图标。图在 `lib/android/res/drawable-nodpi/`，分别是这三家应用自己的图标。应用名仍是手机工位。别的名字、空着，左侧仍是手机工位。更新同一条时这次没带 `agent`，左侧回到手机工位。`--shell` 不读这个 extra，仍是原来的 shell 通知。

这条和常驻状态不是同一条。常驻通道是 `keep`。点「清除」清不掉；划掉之后过一小会儿会自己再出现，再显示时换一个 id。id 从 1 起，不用 2 和 3。提醒有两条通道，震动关掉，划掉就没了。应用里「通知」页的「提醒弹出」开着时用 `popup2`，名字是「提醒」。这条按高重要程度（4）建立，并带一段无声，资源名是 `silence`。关掉时用 `remind-quiet`，名字是「提醒，不弹出」，重要程度是默认（3），声音是 null，只进下拉栏。默认是弹出。系统建好通道后不允许应用再改重要程度，所以这两档不能共用一条通道。

这台 MagicOS 会把第三方应用新建的高重要程度通道降到默认。记录里看得到 `mOriginalImp=4`、`mImportance=3`，应用再调不回去。通道设置里勾上「横幅通知」之后，重要程度才锁在 4，`mUserLockedFields=4`。通道没有声音时，这个勾选不会把重要程度抬上去，所以弹出这条要带一段无声。设置里「通知铃声」显示资源名 `silence`，不用资源号。听得见的铃声由应用按系统通知铃声用媒体音量播放。`--shell` 仍是下面的 PCM。这段无声是约 50 毫秒的全零采样，本身听不见。`android.sh` 装好并重启应用之后，若 `popup2` 还不是 4，就清掉设置任务、打开这一条并勾上。已经是 4 时不再点，避免把勾选点掉。

打开应用时会删掉 `alert`、`remind`、带过资源号的 `popup`，以及没有声音的 `banner`。删掉的通道 id 不能再拿来新建，系统会把旧设置恢复回来。选「只显示最新一条」时，提醒标记是 `alert`、id 是 2；新提醒更新文字和时间，并清掉此前逐条显示的提醒，常驻状态与打开文件提示不受影响。MCP 成功时标准错误是 `通知已更新`；adb 回退路径统一打印 `通知已发出`。

同一天的 `versionName` 14。`./scripts/notify.sh --agent Grok --title 这一轮结束了 --text 'Grok · phone · 图标'`，标准错误是「通知已更新」，铃声仍是 Pixies.ogg，最后一行是 `played`。下拉栏里这一条单独一张卡片：左边是手机工位的蓝色图标，右边是 Grok 的图标。记录里标记 `alert`、id 是 2，`android.largeIcon` 是这个应用自己的资源。另发三条 `stack`，`agent` 分别是 Grok、Claude、Codex，右侧分别是这三家的图标，小图标都还是手机工位那张。同一应用连着四条时，下拉栏先收成一组，组的封面上只有手机工位的图标，展开之后每条右侧才是各自的图标。全局 hook 不带 `--stack`，平时是单独那一张。接着用空的 `agent` 更新同一条，记录里没有 `android.largeIcon`。再带上 Grok，右侧图标又出现。

PGT-AN20 的 MagicOS 把通知左侧画成 small icon，不画启动图标。模板是 `hn_notification_template_material_base`：左列是 `android.R.id.icon`，标题行用 `hn_notification_template_header_without_small_icon`，右侧才是 `setLargeIcon`。彩色位图会按原色显示。X 保存图片时，`com.twitter.android` 在通道 `x_media_download_channel` 上发通知，标题是文件名，正文是「已保存至“下载”」，`setSmallIcon(android.R.drawable.stat_sys_download_done)`。这台框架里的这张图是蓝色下载箭头，所以左侧不是 X 的图标，抬头仍是 X。

手机选「每条都显示」时，每条提醒的标记不同，形如 `alert-<毫秒>-<纳秒>`，id 是 3。它和此前显示的提醒一起留在下拉里，互不覆盖；系统可能自动收成一组，展开后仍是每条各自保留。成功时标准错误是 `通知已发出`。全局 hook 同样遵循手机设置。`--stack` 仅在下面的 `--shell` 路径中仍决定逐条显示。

这个接收器是导出的，adb 才能叫到它。手机上别的应用知道这个动作的话，也能让它发一条通知。系统里强行停止「手机工位」之后，这条广播到不了，通知和铃声都没有；再打开一次应用就恢复。`--shell` 不经过应用，强行停止也不影响那条路径。

默认路径上，通知没发出时不播放。没有铃声，或 `--sound` 指向的文件不在，广播结果是 3，通知也不发，标准错误是「没有读到通知铃声。」。通知发出了但播放失败，结果是 2，标准错误另有「铃声没有播放。」，脚本以失败退出。播完结果是 1。`--shell` 在通知没发出时仍会继续播。

### `--shell`

`--shell` 不走应用，改回原来的 `Notify`。同一条的标记是 `phone-station`，id 是 1，包名是 `com.android.shell`。通道 id 也是 `phone-station`，名字是「手机工位」，重要程度是默认（3），声音和震动都关掉。2026-09-30 在 PGT-AN20 上看到：记录里 `mSound=null`、`isNoisy=false`、`mHidden=false`。下拉栏里能看到标题和内容，连续两条不同的文字只留最后一条。

这条路径的 `--stack` 标记形如 `phone-station-<毫秒>-<纳秒>`，id 是 2。2026-09-30 在 PGT-AN20 上看到：两次 `--stack` 和一次默认更新同时留着，记录是四条，通道仍是 `mSound=null`、`isNoisy=false`。下拉栏里这几条都能看到；同一应用连续发出时，系统会把其中几条收成一组，展开后仍是各自一条。

通知头上的应用名用 `android.substName` 写成「手机工位」。这台手机上 shell 有 `SUBSTITUTE_NOTIFICATION_APP_NAME`，所以不会显示成 Shell。图标用的是 `android` 包里的 `0x01080077`，和 `cmd notification` 的默认图标一样。

`cmd notification post` 的 id 固定是 2020。同一个标记下，它和这条不是同一条，会并排留下。`Notify` 更新时会清掉 2020 那条。

系统上下文 `createPackageContext("com.android.shell")` 之后，包名是 shell，调用包名仍是 `android`。直接 `notify` 会报 `Caller android:2000 cannot post for pkg com.android.shell`。`Notify` 把 `mOpPackageName` 和 `AttributionSource` 的包名都改成 `com.android.shell`，再发。

## 运行时做了什么

默认经 MCP 或 adb 把请求交给「手机工位」：通知和铃声都在应用里完成。全局 hook 调用同一个 `notify.sh`，脚本更新后无需重新安装 hook。`--shell` 仍走下面的 PCM。

### 默认

`notify.sh` 不查媒体库，也不把铃声拉到电脑。MCP 的 `station_notify` 或 adb 的 `am broadcast` 叫 `AlertReceiver`。应用先确认铃声打得开：`--sound` 是手机上的文件；没写时用「通知」里选的那首，没选过就读系统通知铃声。选了静音则直接发通知，结果是 4。`content://` 打不开时，再试媒体库记录里的 `_data` 文件。打不开就不发通知。

打得开才发下拉通知，再用 `MediaPlayer` 播放。属性是 `USAGE_MEDIA` 和 `CONTENT_TYPE_SONIFICATION`，不申请音频焦点，避免把正在放的声音停掉。播放放在广播的 `goAsync()` 里，播完才 `finish()`。结果是 1 时，MCP 按接收器返回的实际显示方式打出 `通知已更新` 或 `通知已发出`，adb 回退路径统一打印 `通知已发出`。标准输出最后一行是 `played`。

从 `versionName` 15 起是这条路径。更早的记录里，默认也把铃声转成 PCM，那是当时的做法。

### `--shell`

1. 读取 `settings get system notification_sound`。这是一个 `content://media/internal/audio/media/<id>`，再用 `content query` 取出 `_data` 里的文件路径。也可以用 `--sound` 指定设备上的音频路径。查媒体库这一步在 PGT-AN20 上大约要一秒。解析出的路径、文件大小和修改时间记在 `/data/local/tmp/notify-sound.stamp`。通知铃声设置没变，而且这个文件的大小和修改时间也没变，就不再查询、不再拉取。
2. 铃声有变化时才 `adb pull` 到本机，用 ffmpeg 转成 44100 Hz、单声道、16-bit 小端 PCM：

   ```bash
   ffmpeg -i 铃声文件 -ac 1 -ar 44100 -f s16le out.pcm
   ```

3. `lib/notify-sound.dex` 和 PCM 放在手机的 `/data/local/tmp/`。dex 用 md5 对照本机文件，一样就不再推。铃声文件的路径、大小、修改时间都和 stamp 一致，PCM 也不再推。
4. 先跑 dex 里的 `Notify` 发下拉通知，再用 shell 跑里面的 `PlayPcm`：

   ```bash
   adb shell CLASSPATH=/data/local/tmp/notify-sound.dex \
     app_process /data/local/tmp PlayPcm /data/local/tmp/notify-sound.pcm 44100
   ```

`PlayPcm` 用公开的 `AudioTrack` 接口，`USAGE_MEDIA` 加 `CONTENT_TYPE_SONIFICATION`，不需要 Context，也不碰隐藏 API。播完会在 stdout 打出 `played`，然后 `System.exit(0)`。2026-09-29 在 PGT-AN20 上看到：不退出的话，AudioTrack 留下的线程会让虚拟机一直不结束，`played` 已经打出来了，`adb shell` 仍在等。

## 源码和产物

源码都在本工程里，不依赖临时目录。

| 路径 | 作用 |
| --- | --- |
| `lib/notify_mcp.py` | 默认通知的 MCP 路由、结果解析与提交前回退判断 |
| `lib/android/.../AlertSound.java` | 默认路径：在「手机工位」里用媒体音量播放 |
| `lib/android/.../AlertSoundPlan.java` | 铃声地址怎么选。没有 Android 依赖，构建时在电脑上跑 |
| `lib/notify-sound/PlayPcm.java` | `--shell` 时在手机上播放 PCM 的类 |
| `lib/notify-sound/Notify.java` | `--shell` 时在下拉栏里更新同一条通知；`--stack` 时另发一条 |
| `lib/notify-sound/stubs/` | 只给 javac 用的 Android 类声明，常量必须和系统一致 |
| `lib/notify-sound/build.sh` | 用上面的源码重新生成 dex |
| `lib/notify-sound.dex` | `--shell` 推到手机上的文件 |

stubs 里写死的值和 Android 一致：`USAGE_MEDIA = 1`，`CONTENT_TYPE_SONIFICATION = 4`，`ENCODING_PCM_16BIT = 2`，`CHANNEL_OUT_MONO = 4`，`MODE_STREAM = 1`。javac 会把这些数字编进 dex。stubs 不会被打进 dex。

D8 来自 R8 8.9.35，体积大约 17MB，不放进工程。`build.sh` 每次编译时下载到临时目录。

## 重新编译

本机用 Homebrew 的 OpenJDK 27，`JAVA_HOME` 在 `~/.zprofile` 和 `~/.zshrc` 里。

在仓库根目录运行：

```bash
lib/notify-sound/build.sh
```

`build.sh` 用 `--release 17` 压低 class 文件版本。OpenJDK 27 的默认版本，PGT-AN20 上的 ART 和 D8 都接不住。编完覆盖 `lib/notify-sound.dex`，然后运行 `./scripts/notify.sh`。手机扬声器应响起当前通知铃声。终端里先有「通知已更新」，最后一行是 `played`。下拉栏里有一条通知。

## 全局 hook

`scripts/install-agent-notify.sh` 把 `scripts/agent-notify-hook.sh` 接到这台电脑的用户配置。这台电脑上每个项目的 Codex、Claude、Grok 会话都会走它。换一台电脑时，在这个仓库里再运行一次安装脚本。步骤在 `.agents/skills/agent-notify/SKILL.md`。

| 工具 | 文件 |
| --- | --- |
| Claude | `~/.claude/settings.json` |
| Codex | `~/.codex/hooks.json` |
| Grok | `~/.grok/hooks/phone-notify.json` |

命令记的是安装当时这份仓库里脚本的绝对路径，并带上 `--client`。仓库换了位置，或这条命令行变了，就再装一次。脚本内容变了、命令行没变，不用再装。

Grok 默认还会读 Claude 的用户配置。同一时刻两边都触发时，8 秒内只响一次。时间戳在 `~/.cache/phone-agent-notify/last`，播放输出在同目录的 `last.log`。

每次真正播出来时，下拉栏标题是这件事。内容是工作目录名加这件事，例如 `phone：这一轮的第一句`。只有目录名或只有这件事时，正文就只写那一项。认得出 Grok、Claude、Codex 时，`notify.sh` 带上 `--agent`，通知左侧是这个 Agent 的图标。不扫描对话记录。超过 80 个字就截断。

Agent 名先看会话文件路径：`~/.grok/sessions/`、`~/.codex/`、`~/.claude/`。路径看不出来时，Grok 的 hook 进程带有 `GROK_HOOK_EVENT` 或 `GROK_SESSION_ID`，就标成 Grok。还是看不出来，才用安装命令里的 `--client`。Grok 也会执行 Claude 配置里的同一条命令，所以不能只信 `--client`。都没有时不写名字。Claude 在没有路径、也没有 `--client` 时，用它设置的 `CLAUDE_PROJECT_DIR`。

| 原因 | 标题 | 内容 |
| --- | --- | --- |
| 一轮结束 `stop` | 这一轮结束了 | `last_assistant_message`（Grok 里是 `lastAssistantMessage`）的第一行 |
| 会话结束 `sessionend` | 会话结束了 | Grok 会话 `summary.json` 里的 `last_turn_summary`、`generated_title` 或 `session_summary` |
| 权限确认 `permissionrequest`、`permission_prompt` | 需要确认权限 | 工具名，加上命令、文件或通知原文 |
| `elicitation`、`elicitation_dialog`、`agent_needs_input` | 需要你的回答 | 问题或通知原文 |
| `elicitation_url_dialog` | 需要打开一个链接 | 链接 |
| 中途询问 | 有一个问题要回答 | 问题原文 |
| 直接跑 hook，或上面没有的原因 | 手机工位 | 有一条提醒 |

上面这列内容取不到时，只留工作目录名。目录名也没有时，内容改用标题那一句。安装进各家配置的命令带 `--client`，重新安装后才会写上。已经打开的会话要重开一次才读得到新命令。

8 秒内被挡下的那次不会响，也不会改通知。

会响的时机：

| 时机 | Codex | Claude | Grok |
| --- | --- | --- | --- |
| 主会话一轮结束 `Stop` | 是 | 是 | 是 |
| 会话进程结束 `SessionEnd` | 否 | 是 | 是 |
| 权限确认 `PermissionRequest` | 是 | 是 | 否 |
| 中途询问 `PreToolUse` | 是 | 是 | 是 |
| 需要输入的 `Notification` | 否 | 是 | 是 |
| `Elicitation` | 否 | 是 | 否 |

中途询问匹配 `AskUserQuestion`、`ask_user_question`、`request_user_input`。`Notification` 只匹配 `permission_prompt`、`agent_needs_input`、`elicitation_dialog`、`elicitation_url_dialog`。子会话自己的结束不响，子会话中途弹出的问题会响。

Codex 不安装 `SessionEnd`。Codex 的主会话在归档、删除、正常关闭，或没有客户端打开且空闲约 30 分钟后都可能发出 `SessionEnd`。回合完成时 `Stop` 已经提醒，再接 `SessionEnd` 会产生重复或延迟很久的铃声。

Grok 给新会话起标题时会另开一个无头会话，工作目录是 `/`，几秒后自己结束。这个会话的 `Stop` 和 `SessionEnd` 不响，所以会话刚开始时不会跟着响一声。钩子用载荷里的 `sessionId` 和 `cwd` 读 `~/.grok/sessions` 下的 `summary.json`。`session_kind` 是 `headless` 且这条会话的 `cwd` 是 `/`，或者记录里是起标题的提示，就跳过。在项目目录里跑完的 `grok -p` 仍会响。`session_kind` 以 `subagent` 开头的也不响。

`idle_prompt` 不接入。它在回合结束大约一分钟后才来，和 `Stop` 叠在一起。

Codex 的安装范围是 `PreToolUse`、`PermissionRequest`、`Stop`。`Notification` 和 `Elicitation` 只写入支持它们的 Claude 或 Grok 配置。重新运行安装脚本时，会先删除它以前安装的条目，再按当前事件表重建；其他 hook 不受影响。

安装脚本不改 Codex `config.toml` 里的 `notify`。那边若已经有别的命令，回合结束时两份一起跑。

`Stop` 会读标准输出，退出码 2 会让这一轮继续。会话结束的 hook 预算很短。入口脚本判断完就退出，铃声在脱离的进程里播，自己不往标准输出打印。手机不在线时，退出码也不会交回给 hook。

已经打开的会话要重开一次才读得到新 hook。Codex 还要信任新加的条目。安装脚本会用本机 `codex app-server` 的 `hooks/list` 和 `config/batchWrite`，信任来自 `~/.codex/hooks.json`、命令的第一段以 `agent-notify-hook.sh` 结尾的那几条。整条命令形如 `agent-notify-hook.sh --client Codex`。

卸下：`./scripts/install-agent-notify.sh --remove`。

## 历史验证

通知显示、声音与 Mac 窗口材质的历次验证见 [历史验证记录](history/notify.md)。模拟窗口、受控合成与真实桌面分别记载；独立跨应用桌面材质验收仍未关闭。
