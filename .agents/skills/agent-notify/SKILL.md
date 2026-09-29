---
name: agent-notify
description: 把 notify.sh 接到 Codex、Claude、Grok 的用户级 hook，或从这些配置里卸下。用户说安装会话提醒、卸下会话提醒、一键安装、换电脑也接上时使用。单次响一声、这一轮结束响一声，或只要震动，走 phone-signal。
---

# 安装全局任务提醒

事件、各家配置写到哪、以及为什么铃声在脱离的进程里播，以 `docs/notify.md` 的「全局 hook」为准。

只有用户要求安装或换电脑接上时才运行安装脚本。只有用户要求卸下时才运行 `--remove`。单次响一声、这一轮结束响一声，或只要震动，走 `phone-signal`。

## 安装

在仓库根目录运行：

```bash
./scripts/install-agent-notify.sh
```

脚本把 `scripts/agent-notify-hook.sh` 的绝对路径写进用户目录。仓库换位置后要再运行一次。

把脚本印出的结果告诉用户，包括 Codex 信任是否成功，以及已经打开的会话要重开。信任没成功时，让用户在 Codex 里打开 `/hooks`，信任命令以 `agent-notify-hook.sh` 结尾的条目。

## 卸下

```bash
./scripts/install-agent-notify.sh --remove
```

## 和手动响一声分开

一轮完成、权限确认和中途询问由这条全局 hook 播放。Codex 的 `SessionEnd` 不接入，避免会话空闲退出时重复或延迟提醒。用户要现在就响，或只要震动，走 `phone-signal`。
