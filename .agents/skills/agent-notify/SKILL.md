---
name: agent-notify
description: 把 notify.sh 接到 Codex、Claude、Grok 的用户级 hook，或从这些配置里卸下。用户说安装会话提醒、卸下会话提醒、一键安装、换电脑也接上，或要改这条全局提醒的下拉通知时使用。单次响一声、这一轮结束响一声，或只要震动，用 README「提醒」里的命令。
---

# 安装全局任务提醒

事件表、下拉通知的标题和内容、Agent 名怎么判断，以及铃声为什么在脱离的进程里播，以 `docs/notify.md` 的「全局 hook」为准。

只有用户要求安装或换电脑接上时才运行安装脚本。只有用户要求卸下时才运行 `--remove`。单次响一声、这一轮结束响一声，或只要震动，用 README「提醒」里的命令。

## 安装

在仓库根目录运行：

```bash
./scripts/install-agent-notify.sh
```

脚本把 `scripts/agent-notify-hook.sh` 的绝对路径写进用户目录，并带上 `--client`。仓库换了位置，或写进配置的命令行变了，再运行一次。只改脚本里的判断或通知文字时不用重装，下次触发会直接跑这份脚本。

把脚本印出的结果告诉用户：写了哪些文件、每条命令的 `--client`、Codex 信任了几条。已经打开的会话要重开一次，才会读到新命令。信任没成功时，让用户在 Codex 里打开 `/hooks`，信任来自 `~/.codex/hooks.json`、命令中包含 `agent-notify-hook.sh` 的条目。

## 卸下

```bash
./scripts/install-agent-notify.sh --remove
```

卸下后告诉用户：已经打开的会话要重开一次。

## 和手动响一声分开

一轮完成、权限确认和中途询问由这条全局 hook 播放，并在下拉栏里更新同一条通知。标题是这件事，内容以 Agent 名和目录名开头。各事件的文字在 `docs/notify.md`。Codex 的 `SessionEnd` 不接入，避免会话空闲退出时重复或延迟提醒。用户要现在就响，或只要震动，用 README「提醒」里的 `notify.sh` 或 `vibrate.sh`。
