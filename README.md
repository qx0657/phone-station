# 手机工位

电脑通过 [adb](https://developer.android.com/tools/adb) 操作一台 Android 手机。手机在线之后，这台电脑就有一条到它的通道：无线调试配对过一次并且开着，或者 USB 已经插上。会话提醒、闪光灯、文件和 APK、投屏都走这条通道。脚本在 `scripts/`，按自己的位置找 `lib/`，克隆到哪个目录都能跑。目前只在荣耀 PGT-AN20（Android 15）上验证过。

这条通道上现在做的事：

| 事 | 入口 |
| --- | --- |
| 一轮做完、要确认权限或停下来问时，手机响一声 | [`install-agent-notify.sh`](#会话提醒)。装一次，这台电脑上各个项目的 Codex、Claude、Grok 都走它 |
| 闪光灯跟着电脑正在播放的声音 | [`torch.sh beat`](#闪光灯) |
| 读改手机文件，分析或修改 APK | [`mt.sh`](#文件和-apk) |
| 判断图库里哪些截图可以删 | [图库截图](#图库里的截图) |
| 投屏、录屏、截取当前画面、保持亮屏 | [屏幕](#屏幕) |

手机要先在线。还没有 `adb`，或还没配对，先看 [准备](#准备) 和 [连接](#连接)。

这篇是用法。命令里看不出来的做法在 [docs/](docs/README.md)。改这个仓库的 AI 读 `AGENTS.md`（与 `CLAUDE.md` 同步）。许可是 [MIT](LICENSE)。

```bash
git clone https://github.com/qx0657/phone-station.git
cd phone-station
```

## 会话提醒

一条命令装到这台电脑的 Codex、Claude、Grok 用户配置。手机在线时，这台电脑上各个项目里，一轮做完、需要确认权限，或中途停下来询问，都会让手机响一声，并在下拉栏里更新同一条通知。

下拉栏标题是这件事，内容以 Agent 名和目录名开头，例如 `Grok · phone · 这一轮的结果`。Codex 不在会话空闲退出时再次响。已经打开的会话要重开一次。换一台电脑时，在这个仓库里再运行一次。各工具的事件差异、Codex 要信任的条目，见 [docs/notify.md](docs/notify.md)。

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
./scripts/notify.sh --stack --title 标题 --text 内容
```

不带参数时，播放系统设置里的通知铃声，并在下拉栏里更新同一条通知。不写标题时是「手机工位」，不写内容时是「有一条提醒」。`--sound` 改用手机上的另一个音频文件。再跑一次会改这条通知的文字，不会另起一条。`--stack` 每次另发一条，原来的留着，也不覆盖这条。手机在震动或静音时，系统会把通知音量关掉，所以铃声改走媒体音量，运行时听得到。下拉栏里的通知本身不响。转好的音频和播放程序会留在手机上，铃声文件和播放程序没变就直接播。做法见 [docs/notify.md](docs/notify.md)。

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

开关闪光灯，或让它跟着电脑正在播放的声音闪。

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

第一次如果系统问权限，允许「屏幕与系统音频录制」或「系统音频录制」。它只拿音量，不录屏幕。灯为什么不能用系统设置项点亮，见 [docs/torch.md](docs/torch.md)。

## 文件和 APK

读写手机文件，以及分析、修改 APK，走手机上 MT 管理器的 MCP。官方说明在 <https://mt.cc/guide/ai/mcp.html>。`./scripts/mt.sh` 把服务开起来，并把本机地址打印出来。

```bash
./scripts/mt.sh
./scripts/mt.sh stop
```

这一节后面还会加手机文件上的事。按信息价值判断图库截图能不能删，是已经有的一件。

工具、权限和这台手机上的验证记录在 [docs/mt-mcp.md](docs/mt-mcp.md)。电脑上读 Java、脱壳、看 so、结构重打包在 [docs/apk-edit.md](docs/apk-edit.md)。

### 图库里的截图

手机相册里的截图在内部存储的 `Pictures/Screenshots`。同目录的 mp4 是录屏。

判断哪些可以删，不在 `scripts/` 里。标准在 [docs/screenshot-cleanup.md](docs/screenshot-cleanup.md)。在仓库根目录运行：

```bash
python3 .agents/skills/screenshot-cleanup/scripts/cleanup.py
python3 .agents/skills/screenshot-cleanup/scripts/cleanup.py list 游戏
python3 .agents/skills/screenshot-cleanup/scripts/cleanup.py dupes
```

这三条只统计和列名单，不删除。点名类别或名单之后怎么删，见 `.agents/skills/screenshot-cleanup/SKILL.md`。

这是手机相册里已经存着的截图。电脑这一次抓下来的画面，见 [截取当前画面](#截取当前画面)。

## 屏幕

投屏、录屏、截取当前画面都用 [scrcpy](https://github.com/Genymobile/scrcpy)。三个脚本都会先走 `connect.sh`。参数可以接在命令后面，原样传给 scrcpy。选项以 scrcpy 自己的说明为准。

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
| `ffmpeg` | `notify.sh` | `brew install ffmpeg` |
| `python3` | 发现设备、配对、启动 MCP、闪光灯跟随声音 | 系统自带 |
| `node` | `pair-qr.sh` | 第一次运行时安装到 `lib/node_modules`。这个目录不入库 |
| `swiftc` | `torch.sh beat` | 系统自带。没有已编译的程序，或源码比它新时，会自己编译 |

`android`、`jadx`、`apktool`、`radare2` 不参与上面这些脚本。装 `android` 和它能做的事见 [docs/android-cli.md](docs/android-cli.md)。在电脑上读 Java、脱壳、看 so、结构重打包见 [docs/apk-edit.md](docs/apk-edit.md)。

先在仓库根目录确认有一台在线设备：

```bash
./scripts/connect.sh
./scripts/status.sh
```

`adb devices` 里要有一行状态是 `device`。多台在线时，脚本会停下来，要求只留一台。

每个脚本都支持 `-h`。`lib/` 是这些脚本的内部实现，不用直接跑。

## 连接

会话提醒、闪光灯、文件和屏幕都要先有一台在线设备。无线调试的配对和连接是两步。配对端口和连接端口不是同一个；重启无线调试后，连接端口会变。系统说明见 [adb：通过 WLAN 连接](https://developer.android.com/tools/adb#wireless)。

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
| 连 MT 的 MCP，读改文件或 APK | `.agents/skills/mt-mcp/SKILL.md` |
| 判断图库截图能不能删 | `.agents/skills/screenshot-cleanup/SKILL.md` |
