# 手机工位

电脑通过 [adb](https://developer.android.com/tools/adb) 操作一台 Android 手机。手机在线之后，这台电脑就有一条到它的通道：无线调试配对过一次并且开着，或者 USB 已经插上。闪光灯和投屏走 adb；文件等 MCP 功能还能经已配对的深圳远程通道访问。脚本在 `scripts/`，按自己的位置找 `lib/`，克隆到哪个目录都能跑。目前只在荣耀 PGT-AN20（Android 15）上验证过。

章节按事情排。还没有 `adb`，或还没配对，先看 [准备](#准备) 和 [连接](#连接)。

| 章节 | 内容 |
| --- | --- |
| [Mac 菜单栏](#mac-菜单栏-app) | 构建菜单栏 App：连接、投屏、截屏、录屏、闪光灯、文件 |
| [Android 应用](#android-应用) | 手机上的「手机工位」：连接、服务、通知、权限 |
| [无线调试保持](#无线调试保持) | 系统关掉无线调试之后写回去。在 Android 应用里 |
| [会话提醒](#会话提醒) | 一轮做完或要确认时，手机响一声 |
| [闪光灯](#闪光灯) | 开关，或跟着电脑正在播放的声音 |
| [文件](#文件) | 读改手机上的普通文件。[图库里的截图](#图库里的截图) 也在这一节 |
| [屏幕](#屏幕) | 投屏、录屏、截取当前画面、保持亮屏 |
| [准备](#准备) | 这台电脑要先有的命令 |
| [连接](#连接) | 配对、接上、断开 |
| [给 AI 的步骤](#给-ai-的步骤) | 这个仓库里给 AI 的步骤 |

这篇是用法。命令里看不出来的做法在 [docs/](docs/README.md)。改这个仓库的 AI 读 `AGENTS.md`（与 `CLAUDE.md` 同步）。许可是 [MIT](LICENSE)。

```bash
git clone https://github.com/qx0657/phone-station.git
cd phone-station
```

## Mac 菜单栏 App

在 Mac 上构建一个常驻菜单栏的「手机工位.app」。菜单栏图标会跟着连接变：连上这台已验证的手机时是一只手机，没连上时手机上有一道斜线。目前只对已验证的 PGT-AN20 开放设备操作。

### 构建

```bash
./scripts/build-mac-app.sh
ditto "build/.phone-station/手机工位.app" "/Applications/手机工位.app"
open "/Applications/手机工位.app"
```

构建副本放在 `build/` 下的隐藏目录，避免被系统搜索当成第二个已安装 App。安装版自带这些操作需要的脚本和手机安装包，不依赖仓库检出路径；电脑仍需有 `adb`、`scrcpy` 和 `python3`。依赖状态在「更多工具与设置」。构建、打包和任务结束的做法见 [docs/mac-app.md](docs/mac-app.md)。

重新构建后，退出已经运行的旧版 App，再打开新生成的 App。

### 主页

点击可以投屏、截取画面、录屏，保持亮屏，开关闪光灯，或让灯跟随这台 Mac 正在播放的声音。首页标题旁显示电量。命令行做这几件，见 [闪光灯](#闪光灯) 和 [屏幕](#屏幕)。

主页的「adb 命令」可以保存几条常用命令，点一下就在这台手机上执行，也可以在终端里打开 shell。「MCP服务」打开或停止手机工具，并给出统一网关地址和本机访问令牌，见 [文件](#文件)。最近的截图与录屏在首页的「最近文件」：一行一个缩略图，鼠标停在上面会在面板旁边放出大图，点这一行把图片或文件复制到剪贴板，箭头在 Finder 里显示。录屏或灯光跟随声音时，收起面板不会结束任务。

### 连接与配对

连接、断开无线和 6 位配对码在「连接与配对」。没有设备在线时会自己查找并接上；在那里断开成功之后，要再点连接，这次打开期间才会继续自动接。配对时手机先停在「使用配对码配对」那一页。二维码配对仍用脚本，见 [连接](#连接)。

### 更多

开机自启，以及安装或更新手机上的「手机工位」，在「更多工具与设置」。把 App 放进「应用程序」后，开机自启才会被系统记住。版本号在「关于」。

## Android 应用

手机上的应用叫「手机工位」。电脑不在线时还要做的事放在这里：Wi-Fi 连着时把被系统关掉的无线调试重新打开，MCP 服务，以及会话提醒的下拉通知。桌面图标和通知上的名字都是「手机工位」。包名 `dev.phonestation.adbkeep` 是无线调试保持先做出来时留下的。源码在 `lib/android/`，没有 Gradle。

### 安装

```bash
./scripts/android.sh
./scripts/android.sh status
./scripts/android.sh --remove
```

不带参数会编译、安装，授予写入系统设置和所有文件访问，并打开应用。菜单栏「更多工具与设置」里的「安装或更新手机应用」跑的是同一个脚本。从菜单栏装时，用的是构建 App 时放进包里的安装包。

### 首页

打开之后是三张卡片，页面上没有应用名大标题。

第一张是连接。左边是电脑图标，写「已连接」或「未连接」，下面三行是无线调试、USB 调试和 Wi-Fi。电脑连着时，`connect.sh` 在后台运行 `host-state.sh`，把手机上的一项配置写成当前时间。手机看到这个时间还新，才显示已连接。主动断开时先写成 0。会话自己断掉、来不及写时，过大约 15 秒变成未连接。

第二张标题是「服务」，三个开关：「保持无线调试」「MCP服务」「远程通道」。前两个见 [无线调试保持](#无线调试保持) 和 [文件](#文件)；远程通道仍需先经 adb 配对，细节见 [docs/mcp.md](docs/mcp.md)。

第三张标题是「设置」。「通知」右边是「弹出」或「不弹出」，点进去改提醒是否弹出，以及铃声，见 [会话提醒](#会话提醒)。「权限」缺了会写「还有 N 项」，点进去是写入系统设置、通知、所有文件访问、电池优化、精确闹钟和 Shizuku。没开的那一行写着怎么去开。自启动读不到，点「自启动」会试着打开荣耀的应用启动管理。「MCP说明」是四段短文，工具名和参数在 [docs/mcp.md](docs/mcp.md)。「关于」写版本号和作者。

重启后要自己起来，到荣耀的应用启动管理里允许自启动和后台活动。在系统里强行停止之后，需要再打开一次。

### 下拉通知

下拉栏有一条常驻通知，不响，点「清除」清不掉。收起时是一句现在的状态，例如「已连接」或「等电脑」；少开一项时右边补几个字，例如「MCP 关着」。展开后下面一行是无线调试和 MCP 开没开。「保持无线调试」和「MCP服务」都关着时，没有这条通知。划掉之后过一小会儿会自己再出现。界面、权限、通知和构建见 [docs/adb-keep.md](docs/adb-keep.md)。

### 无线调试保持

PGT-AN20 上，Wi-Fi 断开之后无线调试的开关会自己关掉。电脑当时如果没有 USB，就连不回去，也没法再把开关打开。

开关叫「保持无线调试」，默认开着。Wi-Fi 连着、USB 调试还开着、这个开关也开着时，它把无线调试写回打开。USB 调试关着时不写。应用里关掉这个开关，就暂停这项功能。

当前网络不被系统信任时，系统会把刚写上的值拨回去，并弹出允许对话框。应用会拉开重试间隔，避免一直弹。细节见 [docs/adb-keep.md](docs/adb-keep.md)。

## 会话提醒

一条命令装到这台电脑的 Codex、Claude、Grok 用户配置。手机在线时，这台电脑上各个项目里，一轮做完、需要确认权限，或中途停下来询问，都会让手机响一声，并在屏幕上弹出同一条通知。手机上「手机工位」打开「通知」，可以改铃声，或关掉「提醒弹出」。铃声默认跟随系统通知铃声，选静音则不响、通知还在。关掉「提醒弹出」之后，这条仍会响，但只进下拉栏。

下拉栏标题是这件事，内容是目录名加这件事，例如 `phone：这一轮的结果`。认得出 Grok、Claude、Codex 时，通知右侧是这个 Agent 的图标。Codex 不在会话空闲退出时再次响。已经打开的会话要重开一次。换一台电脑时，在这个仓库里再运行一次。各工具的事件差异、Codex 要信任的条目，见 [docs/notify.md](docs/notify.md)。

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
./scripts/notify.sh --stack --title 标题 --text 内容
./scripts/notify.sh --shell
```

不带参数时，播放系统设置里的通知铃声，并由手机上的「手机工位」在下拉栏里更新同一条通知。铃声也由这个应用用媒体音量播放。不写标题时是「手机工位」，不写内容时是「有一条提醒」。`--sound` 改用手机上的另一个音频文件。再跑一次会改这条通知的文字，不会另起一条。`--agent` 可以是 `Grok`、`Claude`、`Codex`，通知右侧放上这个 Agent 的图标；不写就没有，左边仍是手机工位。`--stack` 每次另发一条，原来的留着，也不覆盖这条。`--shell` 改回原来的方式，由 shell 发这条通知，应用名写成「手机工位」，右侧没有这张图，铃声转成 PCM。手机在震动或静音时，系统会把通知音量关掉，所以铃声走媒体音量，运行时听得到。下拉栏里的通知本身不响。`--shell` 转好的音频和播放程序会留在手机上，铃声文件和播放程序没变就直接播。做法见 [docs/notify.md](docs/notify.md)。

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

## 文件

读改内部存储里的普通文件，走手机上「手机工位」的 MCP。菜单栏的「MCP服务」，或 `./scripts/mcp.sh`，打开固定的本机网关地址并给出 `Authorization` 头。网关优先走 adb，无线断开时切到深圳中继；无线调试保持原样，完整 adb、投屏、录屏与安装仍需本地 adb。远程通道只转发手机工位已有的 MCP 工具，配对与解除配对都需要 adb 在线。

```bash
./scripts/mcp.sh
./scripts/mcp.sh status
./scripts/mcp.sh stop
./scripts/mcp.sh pair https://47.112.20.43:24443 <SPKI-SHA256>
./scripts/mcp.sh unpair
```

`pair` 会隐藏提示输入手机端和电脑端两枚令牌。手机端令牌加密存入 Android Keystore，电脑端令牌存入 macOS 钥匙串。`stop` 同时关 MCP 和远程通道但保留配对；`unpair` 清除两端配对凭据。

应用里「MCP服务」是开关，和菜单栏是同一项，可以在手机上打开或关掉。开着时下面写着手机本机地址。下拉栏那条常驻通知里能看到 MCP 开没开，不另起一条。电脑上的统一网关地址和令牌在菜单栏，或以脚本当次输出为准。删掉的文件不进回收站。改已经存在的文件要带上一次读到的 `targetVersion`。

写入、删除、移动和复制之后会请系统再扫这些路径，相册和文件列表跟着更新。`station_file_access_policy` 里有截图、相机、下载、文档、电影、录音，以及交接用的 `Download/手机工位/inbox` 和 `outbox`。这两个目录不会自动创建。`station_storage_summary` 看剩余空间和这几个目录的体积。`station_device_status` 只读电量、响铃、Wi-Fi 是否连着、调试开关和息屏。`station_stay_awake` 只做开或关，数值和 `stay-awake.sh` 相同。`station_notify` 走和 `notify.sh` 一样的提醒。`station_clipboard_set` 把一段文字放进剪贴板。`station_file_open` 用系统查看器打开一个已有文件。做法、工具和这台手机上的验证记录在 [docs/mcp.md](docs/mcp.md)。

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

投屏、录屏、截取当前画面都用 [scrcpy](https://github.com/Genymobile/scrcpy)。菜单栏主页上有同样的入口。三个脚本都会先走 `connect.sh`。参数可以接在命令后面，原样传给 scrcpy。选项以 scrcpy 自己的说明为准。

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

保存 PNG，并用预览打开。默认目录是 `~/Pictures/scrcpy/`。

这是电脑这一次抓下来的画面。手机相册里已经存着的截图，见 [图库里的截图](#图库里的截图)。

### 保持亮屏

```bash
./scripts/stay-awake.sh
./scripts/stay-awake.sh off
```

`on` 把息屏时间设为 `2147483647` 毫秒（约 24.8 天），充电（交流电、USB、无线充）时不熄屏。`off` 恢复为 60 秒息屏，充电也按系统超时。这是系统设置，不是投屏窗口附带的那一次常亮。

## 准备

| 命令 | 用在 | 没有时 |
| --- | --- | --- |
| `adb` | 全部脚本 | `~/Library/Android/sdk/platform-tools/adb`，或加入 `PATH` |
| `scrcpy` | 投屏、录屏、截屏 | `brew install scrcpy`。脚本不会自己装 |
| `ffmpeg` | `notify.sh --shell` | `brew install ffmpeg`。默认铃声由手机上的应用播放，用不到它 |
| `python3` | 发现设备、配对、启动 MCP、闪光灯跟随声音 | 系统自带 |
| `node` | `pair-qr.sh` | 第一次运行时安装到 `lib/node_modules`。这个目录不入库 |
| `swiftc` | `torch.sh beat` | 系统自带。没有已编译的程序，或源码比它新时，会自己编译 |

`android` 不参与上面这些脚本。装它和它能做的事见 [docs/android-cli.md](docs/android-cli.md)。

每个脚本都支持 `-h`。`lib/` 是这些脚本的内部实现，不用直接跑。接上手机见 [连接](#连接)。

## 连接

会话提醒、闪光灯、文件和屏幕都要先有一台在线设备。`adb devices` 里要有一行状态是 `device`。多台在线时，脚本会停下来，要求只留一台。无线调试的配对和连接是两步。配对端口和连接端口不是同一个；重启无线调试后，连接端口会变。系统说明见 [adb：通过 WLAN 连接](https://developer.android.com/tools/adb#wireless)。

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

## 给 AI 的步骤

人看的用法是上面各节。下面是这个仓库里给 AI 的步骤。

| 做什么 | 步骤 |
| --- | --- |
| 安装或卸下全局会话提醒 | `.agents/skills/agent-notify/SKILL.md` |
| 读改手机上的普通文件 | `.agents/skills/phone-mcp/SKILL.md` |
| 判断图库截图能不能删 | `.agents/skills/screenshot-cleanup/SKILL.md` |
