# notify.sh

播放手机当前的通知铃声，并在下拉栏里更新同一条通知。日常用法见 [../README.md](../README.md)。这里只记它为什么要带一个 dex，下拉通知为什么不走 `cmd notification`，以及这个 dex 怎么再编出来。

## 为什么不用系统命令

`cmd notification post` 能弹出一条通知。在 PGT-AN20 上，通知对象自己的 `sound` 是 null，不能拿来当提示音。它用的通道 `shell_cmd` 仍挂着系统通知音，记录里 `isNoisy=true`。手机不在震动或静音时，这条会再响一次系统通知音。

`tinyplay` 只能播 wav，而且 shell 用户打不开 `/dev/snd/pcmC0D0p`。通知铃声是 ogg，例如现在的 `/system/media/audio/notifications/Pixies.ogg`。

手机处在震动模式时，系统会把通知、铃声、系统音量都静音。所以脚本不走通知音量，而是把这段铃声解码后，用媒体音量播出来。下拉栏里的通知是另一条，通道不发声。

## 下拉栏里的通知

`notify.sh` 每次播放前都发一条通知。`--title` 和 `--text` 改标题和内容。不写时标题是「手机工位」，内容是「有一条提醒」。全局 hook 的标题也是「手机工位」，内容见下面的「全局 hook」。

同一条的标记是 `phone-station`，id 是 1，包名是 `com.android.shell`。再跑一次会改这条的文字和时间，不会在下拉里叠成多条。通道 id 也是 `phone-station`，名字是「手机工位」，重要程度是默认（3），声音和震动都关掉。2026-09-30 在 PGT-AN20 上看到：记录里 `mSound=null`、`isNoisy=false`、`mHidden=false`。下拉栏里能看到标题和内容，连续两条不同的文字只留最后一条。

`--stack` 另发一条。标记每次不同，形如 `phone-station-<毫秒>-<纳秒>`，id 是 2。它和 id 为 1 的那条、以及更早发出的 `--stack` 一起留在下拉里，互不覆盖。成功时标准错误是 `通知已发出`。全局 hook 不带 `--stack`，仍只改 id 为 1 的那条。2026-09-30 在 PGT-AN20 上看到：两次 `--stack` 和一次默认更新同时留着，记录是四条，通道仍是 `mSound=null`、`isNoisy=false`。下拉栏里这几条都能看到；同一应用连续发出时，系统会把其中几条收成一组，展开后仍是各自一条。

通知头上的应用名用 `android.substName` 写成「手机工位」。这台手机上 shell 有 `SUBSTITUTE_NOTIFICATION_APP_NAME`，所以不会显示成 Shell。图标用的是 `android` 包里的 `0x01080077`，和 `cmd notification` 的默认图标一样。

`cmd notification post` 的 id 固定是 2020。同一个标记下，它和脚本这条不是同一条，会并排留下。`Notify` 更新时会清掉 2020 那条。

系统上下文 `createPackageContext("com.android.shell")` 之后，包名是 shell，调用包名仍是 `android`。直接 `notify` 会报 `Caller android:2000 cannot post for pkg com.android.shell`。`Notify` 把 `mOpPackageName` 和 `AttributionSource` 的包名都改成 `com.android.shell`，再发。

通知没发出时，标准错误打出「通知没有发出。」，铃声仍继续播。

## 运行时做了什么

1. 读取 `settings get system notification_sound`。这是一个 `content://media/internal/audio/media/<id>`，再用 `content query` 取出 `_data` 里的文件路径。也可以用 `--sound` 指定设备上的音频路径。查媒体库这一步在 PGT-AN20 上大约要一秒。解析出的路径、文件大小和修改时间记在 `/data/local/tmp/notify-sound.stamp`。通知铃声设置没变，而且这个文件的大小和修改时间也没变，就不再查询、不再拉取。
2. 铃声有变化时才 `adb pull` 到本机，用 ffmpeg 转成 44100 Hz、单声道、16-bit 小端 PCM：

   ```bash
   ffmpeg -i 铃声文件 -ac 1 -ar 44100 -f s16le out.pcm
   ```

3. `lib/notify-sound.dex` 和 PCM 放在手机的 `/data/local/tmp/`。dex 用 md5 对照本机文件，一样就不再推。铃声文件的路径、大小、修改时间都和 stamp 一致，PCM 也不再推。
4. 先跑 `Notify`，再跑 `PlayPcm`。`Notify` 默认更新同一条通知，成功时往标准错误打出 `通知已更新`。带 `--stack` 时另发一条，成功时是 `通知已发出`。然后用 shell 跑里面的 `PlayPcm`：

   ```bash
   adb shell CLASSPATH=/data/local/tmp/notify-sound.dex \
     app_process /data/local/tmp PlayPcm /data/local/tmp/notify-sound.pcm 44100
   ```

`PlayPcm` 用公开的 `AudioTrack` 接口，`USAGE_MEDIA` 加 `CONTENT_TYPE_SONIFICATION`，不需要 Context，也不碰隐藏 API。播完会在 stdout 打出 `played`，然后 `System.exit(0)`。2026-09-29 在 PGT-AN20 上看到：不退出的话，AudioTrack 留下的线程会让虚拟机一直不结束，`played` 已经打出来了，`adb shell` 仍在等。

## 源码和产物

源码都在本工程里，不依赖临时目录。

| 路径 | 作用 |
| --- | --- |
| `lib/notify-sound/PlayPcm.java` | 在手机上播放 PCM 的类 |
| `lib/notify-sound/Notify.java` | 在下拉栏里更新同一条通知；`--stack` 时另发一条 |
| `lib/notify-sound/stubs/` | 只给 javac 用的 Android 类声明，常量必须和系统一致 |
| `lib/notify-sound/build.sh` | 用上面的源码重新生成 dex |
| `lib/notify-sound.dex` | `notify.sh` 实际推到手机上的文件 |

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

每次真正播出来时，下拉栏标题是这件事。内容前面是 Agent 名和工作目录名，例如 `Grok · phone · 这一轮的第一句`。不扫描对话记录。超过 80 个字就截断。

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

上面这列内容取不到时，只留 Agent 名和工作目录名。这两样也没有时，内容改用标题那一句。安装进各家配置的命令带 `--client`，重新安装后才会写上。已经打开的会话要重开一次才读得到新命令。

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
