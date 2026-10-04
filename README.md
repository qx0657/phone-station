# 手机工位

电脑通过 [adb](https://developer.android.com/tools/adb) 操作一台 Android 手机。手机在线之后，这台电脑就有一条到它的通道：无线调试配对过一次并且开着，或者 USB 已经插上。Mac App 的截屏、保持亮屏和手电筒，以及文件等 MCP 功能，还能经自行配置并配对的远程通道访问。Mac 25、手机 63 和中继 3 起，实时投屏与录屏也支持远程连接；灯光跟随声音仍走 adb。脚本在 `scripts/`，按自己的位置找 `lib/`，克隆到哪个目录都能跑。目前只在荣耀 PGT-AN20（Android 15）上验证过。

章节按事情排。还没有 `adb`，或还没配对，先看 [准备](#准备) 和 [连接](#连接)。

| 章节 | 内容 |
| --- | --- |
| [Mac 菜单栏](#mac-菜单栏-app) | 构建菜单栏 App：连接、投屏、截屏、录屏、闪光灯、MCP、共享剪贴板 |
| [Android 应用](#android-应用) | 手机上的「手机工位」：连接、服务、通知、权限 |
| [无线调试保持](#无线调试保持) | 系统关掉无线调试之后写回去。在 Android 应用里 |
| [共享剪贴板](#共享剪贴板) | Mac 与手机自动双向同步文字和链接；两种 MCP 通道均可用 |
| [手机通知同步](#手机通知同步) | 只把手机上勾选应用的新通知同步到 Mac |
| [会话提醒](#会话提醒) | 一轮做完或要确认时，手机响一声 |
| [闪光灯](#闪光灯) | 开关，或跟着电脑正在播放的声音 |
| [远程命令与安装](#远程命令与安装) | 经 Shizuku 执行 shell、上传 APK 并安装升级，不依赖 adb 连接 |
| [文件](#文件) | 读改手机上的普通文件。[图库里的截图](#图库里的截图) 也在这一节 |
| [屏幕](#屏幕) | 投屏、录屏、截取当前画面、保持亮屏 |
| [准备](#准备) | 这台电脑要先有的命令 |
| [连接](#连接) | 配对、接上、断开 |
| [给 AI 的步骤](#给-ai-的步骤) | 这个仓库里给 AI 的步骤 |

这篇是用法。完整组件与源码归属见 [系统架构](docs/architecture.md)，异常处理见 [故障排查](docs/troubleshooting.md)，三端版本、部署与回滚见 [发布记录](docs/releases.md)。命令里看不出来的做法在 [docs/](docs/README.md)。改这个仓库的 AI 读 `AGENTS.md`（与 `CLAUDE.md` 同步）。许可是 [MIT](LICENSE)。

```bash
git clone https://github.com/qx0657/phone-station.git
cd phone-station
```

## Mac 菜单栏 App

在 Mac 上构建一个常驻菜单栏的「手机工位.app」。菜单栏图标会跟着连接变：连上这台已验证的手机时是一只手机，没连上时手机上有一道斜线。目前只对已验证的 PGT-AN20 开放设备操作。

### 构建

本机构建需要带 macOS 26 或更新 SDK 的 Xcode / Command Line Tools、Go 1.23 或更新版、Python 3、OpenJDK 17 或更新版，以及 Android SDK 的 `platforms/android-35` 和 build-tools。App 运行目标仍为 macOS 13，新系统外观由运行时可用性判断。Android 的 SDK 与签名准备见 [docs/adb-keep.md](docs/adb-keep.md#构建)。构建会运行两端测试并生成随包 APK，不安装到手机。

```bash
./scripts/build-mac-app.sh
ditto "build/.phone-station/手机工位.app" "/Applications/手机工位.app"
open "/Applications/手机工位.app"
```

构建副本放在 `build/` 下的隐藏目录，避免被系统搜索当成第二个已安装 App。安装版自带这些操作需要的脚本和手机安装包，不依赖仓库检出路径；电脑仍需有 `adb`、`scrcpy` 和 `python3`。依赖状态在「更多工具与设置」。构建、打包和任务结束的做法见 [docs/mac-app.md](docs/mac-app.md)。

更新已安装版本时，先退出旧版 App 并保留备份，再复制并打开新版本；本机网关的替换步骤见 [docs/mac-app.md](docs/mac-app.md)。

### 主页

点击可以投屏、截取画面、录屏（仅远程连接时的实时投屏与录屏见 [远程屏幕](docs/remote-screen.md)），保持亮屏，开关闪光灯，或让灯跟随这台 Mac 正在播放的声音。首页标题旁显示电量。命令行做这几件，见 [闪光灯](#闪光灯) 和 [屏幕](#屏幕)。

主页的「adb 命令」可以保存几条常用命令，点一下就在这台手机上执行。远程连接也能执行 `shell …`，需要手机上的 Shizuku 已启动并授权；Mac 22 和手机 61 起也能从该页打开远程交互终端，支持持续输入和 Ctrl-C。「MCP 服务」打开或停止手机工具，显示统一网关地址和当前通道，并提供「复制 Authorization」，见 [文件](#文件)。「共享剪贴板」查看同步状态，开启后自动双向同步。最近的截图与录屏在首页的「最近文件」：一行一个缩略图，鼠标停在上面会在面板旁边放出大图，点这一行把图片或文件复制到剪贴板，箭头在 Finder 里显示。录屏或灯光跟随声音时，收起面板不会结束任务。

### 连接与配对

连接、断开无线和 6 位配对码在「连接与配对」。没有设备在线时会自己查找并接上；在那里断开成功之后，要再点连接，这次打开期间才会继续自动接。配对时手机先停在「使用配对码配对」那一页。二维码配对仍用脚本，见 [连接](#连接)。

### 更多

开机自启，以及安装或更新手机上的「手机工位」，在「更多工具与设置」。把 App 放进「应用程序」后，开机自启才会被系统记住。版本号在「关于」。

## Android 应用

手机上的应用叫「手机工位」。电脑不在线时还要做的事放在这里：Wi-Fi 连着时把被系统关掉的无线调试重新打开，MCP 服务，以及会话提醒的下拉通知。桌面图标和通知上的名字都是「手机工位」。包名 `dev.phonestation.adbkeep` 是无线调试保持先做出来时留下的。源码在 `lib/android/`，没有 Gradle。

### 安装

```bash
./scripts/android.sh
./scripts/android.sh --remote
./scripts/android.sh --adb
./scripts/android.sh status
./scripts/android.sh --remove
```

不带参数时，先检查远程中继与 Shizuku；可用就编译并远程更新，沿用现有权限和配对。上传前检查远程不可用时才转到 adb，首次安装也走这条路径：安装、授予写系统设置、通知和所有文件访问，打开应用并配置提醒横幅。`--remote` 强制远程，`--adb` 强制本地并补权限；已提交的远程安装不会因断线再次走 adb 安装。`status` 与 `--remove` 仍要求 adb 在线，远程安装结果用 `install-apk.sh --status <任务编号>` 查询。

菜单栏「更多工具与设置」里的「安装或更新手机应用」跑的是同一个脚本。从菜单栏装时，用的是构建 App 时放进包里的安装包。

### 首页

首页只保留连接状态和 MCP 服务两张卡片。左上角菜单或左侧边缘向右滑动打开侧栏，拖动时跟随手指，松手后按位置和速度展开或收起；点击遮罩、关闭按钮或系统返回也能收起。

第一张是连接。左边是电脑图标，写「已连接」或「未连接」，下面是「无线调试」和「自动保持无线调试」两个开关，标题右侧的 Wi-Fi 与 USB 图标分别显示网络连接和 USB 调试的实际状态：可用时绿色，未连接或关闭时灰色并带斜线；长按图标区域可查看完整状态。adb 连着或远程通道在线，都显示已连接。标题下面显示「无线连接」「USB 连接」或「远程连接」；本地和远程都在线时同时列出。adb 状态由 `connect.sh` 在后台运行 `host-state.sh`，把手机上的一项配置写成当前时间；主动断开时先写成 0，会话自己断掉时大约 15 秒过期。Wi-Fi 断开或无线调试关闭时，立即去掉无线连接，不等心跳过期。已配对并打开远程通道时，手机会随默认网络变化立即重连，电脑网关自动切换 MCP 通道，首页、常驻通知和菜单栏跟着刷新。远程状态看实际连接，只有开关打开或完成配对还不算。两条通道都不在线时才显示未连接。

「无线调试」直接控制系统开关，显示系统实际值。手动关闭时先停用自动保持，避免马上被重新打开；手动打开不改变自动保持的选择。开启需要 Wi-Fi、USB 调试与写入权限，失败时在连接卡片内提示原因。关掉自动保持本身不会关闭仍开启的无线调试。

「MCP 服务」保留服务开关、「远程通道」状态入口和「接入信息与说明」；远程实际已连接、服务实际运行时，对应入口的左侧图标与底托也显示绿色。本机监听地址与使用说明在详情页，不再常驻首页。远程通道的开关在远程中继页；首次填写服务器地址、证书指纹和手机令牌，点「保存并连接」即可，不需要 adb。已有地址和指纹回填，令牌始终隐藏，原地址与指纹不变时可留空保留。

首页将「共享剪贴板」与「通知」放在同一组，显示各自的实际同步状态，点击进入详细设置；这一组位于 MCP 配置前。侧栏顶部写「手机工位」，下面的「设置」分组包含权限和关于。远程通道与接入说明使用首页入口。首页「通知」分成「手机通知 → Mac」与「电脑提醒 → 手机」：前者选择要同步的应用，后者改电脑提醒在手机上的显示与铃声。首页显示同步中、等待 Mac、未授权、未选应用或同步未开等状态，见 [手机通知同步](#手机通知同步) 与 [会话提醒](#会话提醒)。「权限」缺了会写「还有 N 项」，首页也会出现可点击的权限提示；点进去是写入系统设置、通知、所有文件访问、电池优化、精确闹钟和 Shizuku。没开的那一行写着怎么去开。自启动读不到，点「自启动」会试着打开荣耀的应用启动管理。首页「接入信息与说明」包含接入信息和四段短文，工具名和参数在 [docs/mcp.md](docs/mcp.md)。「关于」写版本号和作者。

重启后要自己起来，到荣耀的应用启动管理里允许自启动和后台活动。在系统里强行停止之后，需要再打开一次。

### 下拉通知

下拉栏有一条常驻通知，不响，点「清除」清不掉。标题显示「电脑已连接」「等待电脑连接」或「电脑未连接」。展开后标题右侧显示无线、USB、远程等实际连接类型，底部的「无线」「MCP」「保持」三组图标与文字显示各自开关状态；自动保持遇到问题时单独说明原因。「自动保持无线调试」和「MCP 服务」都关着时，没有这条通知。划掉之后过一小会儿会自己再出现。界面、权限、通知和构建见 [docs/adb-keep.md](docs/adb-keep.md)。

### 无线调试保持

PGT-AN20 上，Wi-Fi 断开之后无线调试的开关会自己关掉。电脑当时如果没有 USB，就连不回去，也没法再把开关打开。

开关叫「自动保持无线调试」，默认开着。Wi-Fi 连着、USB 调试还开着、这个开关也开着时，它把无线调试写回打开。USB 调试关着时不写。应用里关掉这个开关，就暂停这项功能。

当前网络不被系统信任时，系统会把刚写上的值拨回去，并弹出允许对话框。应用会拉开重试间隔，避免一直弹。细节见 [docs/adb-keep.md](docs/adb-keep.md)。

## 共享剪贴板

Mac 菜单栏和手机首页都有「共享剪贴板」。新安装默认开启；开启共享就会自动双向同步，在任意应用复制文字或链接，再到另一端粘贴。界面只显示同步状态，不显示两端正文，也不需要手动复制按钮。升级保留已经关闭的共享设置；旧版单独暂停过自动同步的，页面提供「恢复自动同步」。Mac 页面显示本次运行中最近一次实际同步的方向与时间。

Mac 需运行手机工位并连接 MCP 服务；手机需启动并授权 Shizuku 13+，重启手机后要重新启动 Shizuku。本地无线 adb 和远程中继使用相同的功能，不要求打开投屏。首次连接、长时间断线或服务重启只核对当前内容，随后新的复制动作才会同步。短暂切换会先核对两端版本，再继续同步符合条件的新复制；首页会显示剪贴板正在核对或恢复，完成交换后才显示自动双向同步。

支持文字和链接，长度上限为 100000（按 UTF-16 计数，emoji 可能占多个）。两端共享页另有「同步 Mac 图片到相册」，默认关闭；关闭时复制图片会安静跳过，仍显示正常同步。开启后新复制的 Mac 图片保存到手机的 `Pictures/手机工位`，通过系统媒体库刷新相册，并在手机通知栏显示「Mac 图片已保存到相册」，点击查看图片。提示默认无声、无震动，连续复制只保留最新一条；手机关闭通知权限或该通知类别时仍正常保存图片。每张最多 4 MB / 3200 万像素，不覆盖手机剪贴板。Mac 的 PNG 与 TIFF 图片统一存为 PNG，Finder 中复制文件仍跳过。开启开关或重新连接不会补存已有图片。

文件、标记为敏感的内容会跳过，手机锁屏时暂停；清空剪贴板不会清空另一端。文字只保留当前内容，不记录历史，不写入磁盘；开启图片同步后保存的图片由手机相册管理，关闭开关不会删除。关闭共享清除内存状态；退出 Mac 应用停止交换。远程中继会转发剪贴板文字及开启后的图片，请使用自己部署或信任的中继。实现与验证见 [docs/clipboard.md](docs/clipboard.md)。

## 手机通知同步

手机工位的「首页 → 通知 → 手机通知 → Mac」默认关闭，应用默认一个也不选。先在「选择应用」里勾选需要同步的应用，可按名称或包名搜索；再点「通知使用权」进入 Android 系统授权页，允许手机工位读取通知，并打开「同步到 Mac」。开启会打开 MCP 服务。

Mac 菜单栏主页进入「手机通知」，点击「允许 Mac 显示通知」完成 macOS 授权，连接 MCP 服务并保持手机工位运行。「在这台 Mac 接收」可以单独暂停这台电脑。应用白名单只在手机上选择；新安装的应用不会自动加入。手机通知使用权与电脑提醒的通知权限是两个不同授权。

只同步已选应用的新通知及内容更新。跳过手机工位自己的提醒、常驻通知、分组汇总和没有标题正文的通知；同一条通知的更新替换 Mac 上的同一个条目。本地 adb 和已配置的远程中继都可用，不依赖 Shizuku。首次连接、断线、重启和更改选择后不补发旧通知。此版本不转发通知操作按钮，也不联动两端清除通知。

Mac 默认显示带手机应用图标的自定义横幅，6 秒后收起；鼠标停留时保持，最多同时显示 3 条，同一条更新会替换。点击横幅进入「手机通知」页，关闭按钮只收起横幅；系统通知中心继续安静地保存记录，来源图标仍是手机工位。「显示应用图标横幅」可以关闭并恢复系统横幅，「预览横幅」使用已选应用图标和明确标注的示例文字。

自定义横幅独立于系统专注模式，想暂停可关闭「在这台 Mac 接收」或改回系统横幅；系统通知设置关闭声音或将预览设为「永不」时，自定义横幅也会静音或隐藏正文。Android 15 可能隐藏验证码等敏感内容，以系统提供的内容为准。应用没有通知历史文件；在线短期队列、图标缓存和正在显示的横幅保留在内存中，通知中心记录由系统管理。远程中继会承载通知文字和应用图标，请使用自己部署或信任的中继。实现与验证见 [docs/notify.md](docs/notify.md#手机通知同步到-mac)。

## 会话提醒

一条命令装到这台电脑的 Codex、Claude、Grok 用户配置。手机在线时，这台电脑上各个项目里，一轮做完、需要确认权限，或中途停下来询问，都会让手机响一声，并在屏幕上弹出同一条通知。手机上「手机工位」打开「通知」，可以改铃声，或关掉「提醒弹出」。铃声默认跟随系统通知铃声，选静音则不响、通知还在。关掉「提醒弹出」之后，这条仍会响，但只进下拉栏。

下拉栏标题是这件事，内容是目录名加这件事，例如 `phone：这一轮的结果`。认得出 Grok、Claude、Codex 时，通知左侧是这个 Agent 的图标。Codex 不在会话空闲退出时再次响。已经打开的会话要重开一次。换一台电脑时，在这个仓库里再运行一次。各工具的事件差异、Codex 要信任的条目，见 [docs/notify.md](docs/notify.md)。

### 装上

```bash
./scripts/install-agent-notify.sh
```

卸下：

```bash
./scripts/install-agent-notify.sh --remove
```

### 响一声

要现在就响一声，直接跑 `notify.sh`。

```bash
./scripts/notify.sh
./scripts/notify.sh --title 标题 --text 内容
./scripts/notify.sh --sound /system/media/audio/notifications/Bell.ogg
./scripts/notify.sh --agent Grok --title 标题 --text 内容
./scripts/notify.sh --shell --stack --title 标题 --text 内容
./scripts/notify.sh --shell
```

不带参数时，播放手机工位「通知」里选的铃声；没选过就跟随系统，选了静音则只发通知。通知显示方式在手机 App 的「首页 → 通知 → 电脑提醒 → 手机 → 显示方式」里选择：默认「只显示最新一条」，新提醒覆盖旧提醒；「每条都显示」则逐条保留。从下一条提醒开始生效，升级和重启后保留选择。电脑脚本和全局 hook 都遵循手机设置，旧的 `--stack` 参数不再覆盖它。

默认先用已在线的 MCP 网关，只有远程连接也能送达；提交通知前网关不可用才回退 adb。铃声由手机工位用媒体音量播放。不写标题时是「手机工位」，不写内容时是「有一条提醒」。`--sound` 改用手机上的另一个音频文件。`--agent` 可以是 `Grok`、`Claude`、`Codex`，通知左侧换成这个 Agent 的图标；不写则左边仍是手机工位。`--shell` 改回原来的方式，由 shell 发通知，应用名写成「手机工位」，左侧没有 Agent 图标，铃声转成 PCM；只有这条旧路径仍用 `--stack` 逐条显示。手机在震动或静音时，系统会把通知音量关掉，所以铃声走媒体音量，运行时听得到。下拉栏里的通知本身不响。`--shell` 转好的音频和播放程序会留在手机上，铃声文件和播放程序没变就直接播。做法见 [docs/notify.md](docs/notify.md)。

### 震一下

不带参数时震 400 毫秒。四个数依次是时长、力度、间隔、次数。

| 参数 | 范围 | 不写时 |
| --- | --- | --- |
| 时长 | 1–10000 毫秒 | 400 |
| 力度 | 1–255 | 200 |
| 间隔 | 最长 60 秒 | 不重复 |
| 次数 | 最多 50 | 写出间隔后一直重复 |

不写间隔就震一次。写了间隔但不写次数，会一直重复，用 `stop` 停下。免打扰也会震。

```bash
./scripts/vibrate.sh
./scripts/vibrate.sh 1500 255
./scripts/vibrate.sh 400 200 1000
./scripts/vibrate.sh 400 200 1000 5
./scripts/vibrate.sh stop
```

## 闪光灯

开关闪光灯，或让它跟着电脑正在播放的声音闪。菜单栏主页上有同样的两项。

不带参数时打开，亮度用手机自己的最高档。PGT-AN20 后置是 4 档。开着的时候手机会留一个进程，脚本自己会结束；灯一直亮到 `off`。超过手机上限时收到最高档。

```bash
./scripts/torch.sh
./scripts/torch.sh off
./scripts/torch.sh 2
./scripts/torch.sh status
./scripts/torch.sh toggle
```

`beat` 跟着电脑正在播放的声音闪，不听麦克风。按 `Ctrl+C` 停下，灯会关掉。增益默认 2，范围 0.2 到 8，越大越容易亮到高档。

```bash
./scripts/torch.sh beat
./scripts/torch.sh beat 1.5
```

第一次如果系统问权限，到「系统设置 → 隐私与安全性 → 系统录音」允许。菜单栏里这次要允许的是「手机工位」。它只拿音量，不录屏幕。允许之后如果灯还是不跟，关掉再打开一次。灯为什么不能用系统设置项点亮，见 [docs/torch.md](docs/torch.md)。

## 远程命令与安装

手机工位已连上远程中继、Shizuku 已启动并授权后，AI 可以直接执行手机 shell 和安装 APK，不需要 USB 或无线 adb 连接：

```bash
./scripts/shell.sh 'pm list packages'
./scripts/terminal.sh                    # 远程交互式 shell，Ctrl-] 关闭
./scripts/terminal.sh --resume <会话编号>  # 只恢复原会话
./scripts/install-apk.sh /电脑上的路径/app.apk
./scripts/android.sh --remote
```

`install-apk.sh` 支持新装、保留数据升级及 base + split APK；先上传和校验，再提交安装，最后核对已安装 APK 的 SHA-256。更新手机工位自身会自动拉起后台服务、打开主页并核对远程连接恢复；仅需后台时用 `./scripts/install-apk.sh --no-open <APK>`。`verified` 表示 APK 一致，`completed` 才表示整个安装与恢复流程完成。签名不同或版本回退时返回错误，不自动卸载。默认 `android.sh` 优先远程更新；`--adb` 指定原有本地安装。完整用法与结果查询见 [remote-ops.md](docs/remote-ops.md)。

## 文件

读改内部存储里的普通文件，走手机上「手机工位」的 MCP。菜单栏的「MCP服务」，或 `./scripts/mcp.sh`，打开固定的本机网关地址并给出 `Authorization` 头。网关优先走 adb，无线断开时切到已配置的中继；完整 adb 与本地投屏、录屏脚本仍需本地 adb，Mac 的远程实时投屏与录屏见 [远程屏幕](docs/remote-screen.md)，远程 shell 和安装升级见 [远程命令与安装](#远程命令与安装)。远程通道转发手机工位已有的 MCP 工具。手机与 Mac 可各自配置；通过 adb 同时配置或清除两端资料时才需要 adb 在线。

Mac 19 起，`./scripts/mcp.sh clients create '资料读取' status,files.read` 可生成独立客户端令牌，按需授予状态、文件读写或 shell 权限；`clients list` 查看，`clients revoke <编号>` 撤销。创建时只显示一次令牌，详细范围见 [客户端权限](docs/mcp-clients.md)。

需要通过本地或远程通道执行 shell 命令时，在手机上启动 Shizuku 13 或更新版，并在「手机工位 → 权限 → Shizuku」允许使用。`station_shell_status` 检查是否可用，新客户端用 `station_shell_start` 提交有编号的后台任务，再用 `station_operation_status` 查询结果；`station_shell_exec` 保留给旧客户端。这类单次命令默认执行 10 秒，最长 60 秒，标准输入关闭。交互终端使用独立的 `terminal.sh`，保留目录与环境；断线 60 秒或会话运行 1 小时后清理进程。提交应答丢失后只查询原编号，不重新执行。非 root 手机重启后需要重新启动 Shizuku，之前的应用授权仍保留。参数和权限边界见 [MCP 文档](docs/mcp.md#shizuku-shell)。

远程通道默认未配置，没有内置服务器地址。手机「远程中继设置」填写 HTTPS 地址、SPKI SHA-256 证书指纹和手机令牌；Mac「MCP 服务 → 远程中继」填写相同地址、指纹和另一枚电脑令牌，点「保存 Mac 配置」。两端均可独立配置，无需 adb。Mac 勾选「同时配置手机」后，可通过已连接的 adb 一次保存两端资料。地址支持域名或 IP、可选端口和路径，例如 `https://relay.example.com/station`。中继需要实现手机工位的转发协议，见 [docs/mcp.md](docs/mcp.md)；任意 MCP 服务器不能直接代替它。已有配对在更新后保留。

```bash
./scripts/mcp.sh
./scripts/mcp.sh status
./scripts/mcp.sh status --safe
./scripts/mcp.sh profile
./scripts/mcp.sh stop
./scripts/mcp.sh desktop https://relay.example.com '<SPKI-SHA256>'
./scripts/mcp.sh forget-desktop
./scripts/mcp.sh pair https://relay.example.com '<SPKI-SHA256>'
./scripts/mcp.sh unpair
```

自动化调用可用 `./scripts/mcp.sh call` 从标准输入提交 JSON-RPC，凭据在进程内部读取，不进入命令参数或输出。`status --safe` 查看已有网关状态且不输出令牌、不刷新 adb。AI 需要启动服务时用 `./scripts/mcp.sh >/dev/null`，再调用这两个入口，示例见 [MCP 步骤](.agents/skills/phone-mcp/SKILL.md)。

示例域名需换成自己的中继地址，指纹需换成服务器公钥的实际 SHA-256。`profile` 只读地址与证书指纹，不输出令牌。`desktop` 隐藏提示输入一枚电脑令牌，只配置此 Mac；`forget-desktop` 只清除此 Mac。`pair` 会隐藏提示输入手机端和电脑端两枚令牌，并通过 adb 同时配置两端。手机端令牌加密存入 Android Keystore，电脑端令牌存入 macOS 钥匙串。`stop` 同时关 MCP 和远程通道但保留配对；`unpair` 清除两端配对凭据。

应用里「MCP 服务」是开关，和菜单栏控制同一项，可以在手机上打开或关掉；手机本机地址在「接入信息与说明」页显示。下拉栏那条常驻通知里能看到 MCP 开没开，不另起一条。电脑上的统一网关地址在菜单栏显示，授权头通过「复制 Authorization」获取，或以脚本当次输出为准。删掉的文件不进回收站。改已经存在的文件要带上一次读到的 `targetVersion`。

写入、删除、移动和复制之后会请系统再扫这些路径，相册和文件列表跟着更新。`station_file_access_policy` 里有截图、相机、下载、文档、电影、录音，以及交接用的 `Download/手机工位/inbox` 和 `outbox`。这两个目录不会自动创建。`station_storage_summary` 看剩余空间和这几个目录的体积。`station_device_status` 只读连接类型、电量、响铃、Wi-Fi 是否连着、调试开关和息屏。`station_stay_awake` 只做开或关，数值和 `stay-awake.sh` 相同。`station_notify` 走和 `notify.sh` 一样的提醒。`station_clipboard_get` 读取手机文字，`station_clipboard_set` 写入文字；共享设置和自动同步见 [共享剪贴板](#共享剪贴板)。`station_file_open` 用系统查看器打开一个已有文件。做法、工具和这台手机上的验证记录在 [docs/mcp.md](docs/mcp.md)。

### 图库里的截图

手机相册里的截图在内部存储的 `Pictures/Screenshots`。同目录的 mp4 是录屏。

判断哪些可以删，不在 `scripts/` 里。标准在 [docs/screenshot-cleanup.md](docs/screenshot-cleanup.md)。在仓库根目录运行：

```bash
python3 .agents/skills/screenshot-cleanup/scripts/cleanup.py
python3 .agents/skills/screenshot-cleanup/scripts/cleanup.py list 游戏
python3 .agents/skills/screenshot-cleanup/scripts/cleanup.py dupes
```

这三条只统计和列名单，不删除。点名类别或名单之后，用手机上「手机工位」的文件工具删，见 `.agents/skills/screenshot-cleanup/SKILL.md`。

这是手机相册里已经存着的截图。电脑这一次抓下来的画面，见 [截取当前画面](#截取当前画面)。

## 屏幕

本地投屏和录屏用 [scrcpy](https://github.com/Genymobile/scrcpy)，截屏脚本用 adb 的 `screencap`。菜单栏主页上有同样的入口。这三个脚本都会先走 `connect.sh`；投屏与录屏的额外参数原样传给 scrcpy。Mac App 在只有远程连接时，也可实时投屏并录屏（Mac 25、手机 63、中继 3），见 [远程屏幕](docs/remote-screen.md)；截屏、保持亮屏和手电筒见 [远程控件](docs/remote-controls.md)。

窗口开着时，scrcpy 的 `--stay-awake` 会让充电中的手机暂时不熄屏。关掉窗口之后，息屏仍按系统设置。要一直亮着，用 [保持亮屏](#保持亮屏)。

系统提示「不允许截屏」的界面，截屏、投屏和录屏都是黑的。那是应用给窗口加了安全标记。在这台 Android 15 上，换采集命令也读不到那一层。

### 投屏

```bash
./scripts/mirror.sh
```

关掉窗口就停止。

### 录屏

```bash
./scripts/record.sh
./scripts/record.sh ~/Movies/scrcpy/demo.mp4
```

一边投屏一边录。默认文件在 `~/Movies/scrcpy/日期时间.mp4`，扩展名也可以是 `.mkv`。结束时关闭窗口，或在运行它的终端按 `Ctrl+C`，文件才会收好。

### 截取当前画面

```bash
./scripts/screenshot.sh
./scripts/screenshot.sh ~/Pictures/scrcpy/demo.png
```

保存 PNG，并用预览打开。默认目录是 `~/Pictures/scrcpy/`。Mac App 的「截取画面」在无 adb 时使用远程 MCP：手机需要运行并授权 Shizuku、允许所有文件访问；传回的 PNG 会校验后保存到同一 Mac 目录，并出现在「最近文件」。

这是电脑这一次抓下来的画面。手机相册里已经存着的截图，见 [图库里的截图](#图库里的截图)。

### 保持亮屏

```bash
./scripts/stay-awake.sh
./scripts/stay-awake.sh off
```

`on` 把息屏时间设为 `2147483647` 毫秒（约 24.8 天），充电（交流电、USB、无线充）时不熄屏。Android 60 起，开启前由手机应用持久保存原设置，`off` 恢复原值；重复开启不覆盖备份，中途手动改过的值会保留。旧版本遗留的常亮没有原值记录，首次关闭仍回退到 60 秒/充电掩码 0。这是系统设置，不是投屏窗口附带的那一次常亮。脚本与 MCP 共用这份记录，需要 Android 60 或更新版及已授予的写系统设置权限。Mac App 的同名开关也支持远程连接，不要求 Shizuku。失败恢复与升级边界见 [远程控件](docs/remote-controls.md#亮屏原设置恢复)。

## 准备

| 命令 | 用在 | 没有时 |
| --- | --- | --- |
| `adb` | 本地连接、配对、屏幕、闪光灯及首次安装 | `~/Library/Android/sdk/platform-tools/adb`，或加入 `PATH` |
| `scrcpy` | 投屏、录屏；本地截屏使用 adb | `brew install scrcpy`。脚本不会自己装 |
| `ffmpeg` | `notify.sh --shell` | `brew install ffmpeg`。默认铃声由手机上的应用播放，用不到它 |
| `python3` | 发现设备、配对、MCP 通知、远程 shell/安装、闪光灯跟随声音 | 系统自带 |
| `node`、`npm` | `pair-qr.sh` | 先准备 Node.js 与 npm；首次运行自动安装的是二维码依赖，放在 `lib/node_modules`，这个目录不入库 |
| `swiftc` | Mac App 构建、`torch.sh beat` | 由 Command Line Tools 提供；跟随声音没有程序或源码较新时会自己编译 |
| `go` | Mac App、MCP 网关与 Android PTY 构建 | Go 1.23 或更新版；已打包 App 运行时不用它 |
| `javac`、Android SDK | Android APK 构建，包括 Mac App 的随包 APK | OpenJDK 17 或更新版、Android 35 平台与 build-tools，见 [构建说明](docs/adb-keep.md#构建) |

`android` 不参与上面这些脚本。装它和它能做的事见 [docs/android-cli.md](docs/android-cli.md)。

每个脚本都支持 `-h`。`lib/` 是这些脚本的内部实现，不用直接跑。接上手机见 [连接](#连接)。

## 连接

这一节是本地 adb 的连接与配对。闪光灯、屏幕和首次安装需要一台 adb 在线设备；文件、剪贴板、默认会话提醒和远程 shell/安装也可经已配置的远程中继访问。走 adb 时，`adb devices` 里要有一行状态是 `device`。多台在线时，脚本会停下来，要求只留一台。无线调试的配对和连接是两步。配对端口和连接端口不是同一个；重启无线调试后，连接端口会变。系统说明见 [adb：通过 WLAN 连接](https://developer.android.com/tools/adb#wireless)。

### 查看状态

```bash
./scripts/status.sh
```

USB 在线时，序列号不带端口。无线在线时，`status.sh` 会印出当前的局域网地址和端口，以它为准。

### 接上

作者这台电脑和 PGT-AN20 已经配对过。USB 已经在线时，不用再配，也不用再连无线。换一台电脑，或手机把这台电脑删掉之后，用下面的「第一次配对」。

```bash
./scripts/connect.sh
./scripts/connect.sh 192.168.0.103:37135
```

不带参数时：已经有在线设备（USB 或无线），就只去掉重复连接；一台都没有，就用 mDNS 找局域网地址。第二行是自己指定地址，`192.168.0.103:37135` 只是示例。

PGT-AN20 上也验证过：手机开着 VPN、页面显示 `172.19.0.1` 时，仍能通过 mDNS 找到真实 Wi-Fi 地址并连接。如果 `status.sh` 能发现局域网地址、`route -n get <IP>` 显示走 Wi-Fi 接口、`nc -vz -G 3 <IP> <端口>` 成功，但 adb 报 `No route to host`，先运行 `adb kill-server`、`adb start-server`，再运行 `./scripts/connect.sh`。本次这种情况重启电脑上的 adb 后恢复，无需重新配对；重启会中断这台电脑的其他 adb 会话。

已经有一台在线设备时，再手动 `adb connect` 一次，列表里会变成两台，后面的脚本会拒绝继续。

### 断开

```bash
./scripts/disconnect.sh
./scripts/disconnect.sh 192.168.0.103:37135
```

只断无线，USB 不动。不带参数断开全部无线连接。带上地址只断一条。

### 第一次配对

还没配对，或手机把这台电脑删掉之后，再用这两个。页面上的 IP 和端口每次打开都会变，不用抄。

二维码：手机打开「开发者选项 → 无线调试 → 使用二维码配对设备」，然后：

```bash
./scripts/pair-qr.sh
```

扫完停几秒。

配对码：先打开「使用配对码配对」，停在那个页面，再把 6 位数字传进来。`424380` 只是示例：

```bash
./scripts/pair-code.sh 424380
```

### 地址

无线调试页面上的 `172.19.0.1` 不要拿来 `adb pair` 或 `adb connect`。那是 VPN 地址。这台 Mac 上的 Clash 会把 `172.19.0.0/16` 送进隧道，握手报 `protocol fault`。用局域网地址，例如 `192.168.0.103`，并且路由不能走 `utun`。

## 公网中继源码与发布

公网中继现在与两端客户端一起维护，源码在 [server/relay](server/relay/README.md)，本机网关仍在 `lib/remote-gateway/`。通用配置与 systemd 模板随源码发布，真实主机资料、凭据和私钥留在部署侧。

```sh
./scripts/build-relay.sh
```

该入口运行服务端回归并生成 Linux/amd64 程序和发布回执，不部署。Mac 20 支持中继 2 的持久会话编号；先升级 Mac，再升级中继，保留原配对。协议、隔离验收与回滚限制见 [中继合约](docs/relay-contract.md) 和 [发布记录](docs/releases.md)。

## 本机检查

开发时运行 `./scripts/check.sh`，统一检查文档镜像、项目 Skill 入口、脚本语法、Python/Java/Go race/Swift 测试、真实中继 TLS 联合合约和 Mac 全量源码编译。依赖 Python 3.10+、zsh、Go 1.23+、JDK 17+ 和 macOS Command Line Tools；无 Android SDK 也可运行。其他平台用 `./scripts/check.sh --core`，明确排除 Swift。

检查只使用临时产物与测试数据，不连接手机、不启动真实网关、不读发布签名。Android 资源和 APK 打包仍用 `lib/android/build.sh`；可安装 Mac 包用 `scripts/build-mac-app.sh`。CI 使用同一检查入口，范围及尚未覆盖的设计边界见 [验证说明](docs/validation.md)。

## 给 AI 的步骤

人看的用法是上面各节。下面是这个仓库里给 AI 的步骤。

| 做什么 | 步骤 |
| --- | --- |
| 安装或卸下全局会话提醒 | `.agents/skills/agent-notify/SKILL.md` |
| 手机文件、剪贴板、Shizuku shell 与远程 APK 安装更新 | `.agents/skills/phone-mcp/SKILL.md` |
| 判断图库截图能不能删 | `.agents/skills/screenshot-cleanup/SKILL.md` |
