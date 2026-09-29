# notify.sh

播放手机当前的通知铃声。日常用法见 [../README.md](../README.md)。这里只记它为什么要带一个 dex，以及这个 dex 怎么再编出来。

## 为什么不用系统命令

`cmd notification post` 能弹出一条通知。在 PGT-AN20 上，弹出来的通知是 `sound=null`，没有声音。

`tinyplay` 只能播 wav，而且 shell 用户打不开 `/dev/snd/pcmC0D0p`。通知铃声是 ogg，例如现在的 `/system/media/audio/notifications/Pixies.ogg`。

手机处在震动模式时，系统会把通知、铃声、系统音量都静音。所以脚本不走通知音量，而是把这段铃声解码后，用媒体音量播出来。真正弹出的通知仍然不响。

## 运行时做了什么

1. 读取 `settings get system notification_sound`。这是一个 `content://media/internal/audio/media/<id>`，再用 `content query` 取出 `_data` 里的文件路径。也可以直接把设备上的音频路径当参数。查媒体库这一步在 PGT-AN20 上大约要一秒。解析出的路径、文件大小和修改时间记在 `/data/local/tmp/notify-sound.stamp`。通知铃声设置没变，而且这个文件的大小和修改时间也没变，就不再查询、不再拉取。
2. 铃声有变化时才 `adb pull` 到本机，用 ffmpeg 转成 44100 Hz、单声道、16-bit 小端 PCM：

   ```bash
   ffmpeg -i 铃声文件 -ac 1 -ar 44100 -f s16le out.pcm
   ```

3. `lib/notify-sound.dex` 和 PCM 放在手机的 `/data/local/tmp/`。dex 用 md5 对照本机文件，一样就不再推。铃声文件的路径、大小、修改时间都和 stamp 一致，PCM 也不再推。
4. 用 shell 跑里面的 `PlayPcm`：

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

`build.sh` 用 `--release 17` 压低 class 文件版本。OpenJDK 27 的默认版本，PGT-AN20 上的 ART 和 D8 都接不住。编完覆盖 `lib/notify-sound.dex`，然后运行 `./scripts/notify.sh`。手机扬声器应响起当前通知铃声，终端最后一行是 `played`。

## 全局 hook

`scripts/install-agent-notify.sh` 把 `scripts/agent-notify-hook.sh` 接到这台电脑的用户配置。这台电脑上每个项目的 Codex、Claude、Grok 会话都会走它。换一台电脑时，在这个仓库里再运行一次安装脚本。步骤在 `.agents/skills/agent-notify/SKILL.md`。

| 工具 | 文件 |
| --- | --- |
| Claude | `~/.claude/settings.json` |
| Codex | `~/.codex/hooks.json` |
| Grok | `~/.grok/hooks/phone-notify.json` |

命令记的是安装当时这份仓库里脚本的绝对路径。仓库换了位置就再装一次。

Grok 默认还会读 Claude 的用户配置。同一时刻两边都触发时，8 秒内只响一次。时间戳在 `~/.cache/phone-agent-notify/last`，播放输出在同目录的 `last.log`。

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

已经打开的会话要重开一次才读得到新 hook。Codex 还要信任新加的条目。安装脚本会用本机 `codex app-server` 的 `hooks/list` 和 `config/batchWrite`，信任命令以 `agent-notify-hook.sh` 结尾、并且来自 `~/.codex/hooks.json` 的那几条。

卸下：`./scripts/install-agent-notify.sh --remove`。
