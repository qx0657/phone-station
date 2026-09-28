---
name: phone-signal
description: 用户或上游任务明确要求这一次提醒时，让已连接的 Android 手机响一声或震一下。用户说做完提醒、手机响一下、震动、通知音，或某个任务要在结束时给手机一个信号时使用。安装全局会话提醒走 agent-notify。
---

# 手机信号

这是手机工位的信号通道。只在用户或上游任务明确要求这一次提醒时调用。

已经装上全局 hook 时，会话结束和中途询问会自己响，不要为了同一次结束再跑下面的命令。没装时不要安装。用户要安装、卸下或换电脑接上会话提醒时，走 `.agents/skills/agent-notify/SKILL.md`。

用户要现在就响，或只要震动，用下面的命令。

在仓库根目录运行。设备不在线时先 `./scripts/connect.sh`。

响一声：

```bash
./scripts/notify.sh
```

震一下：

```bash
./scripts/vibrate.sh
```

两条可以一起用。铃声实现和已验证机型上的音量限制在 `docs/notify.md`。震动命令差异在 `AGENTS.md` 的「已验证设备上的命令差异」。

不要用 `cmd notification post` 当提示音。亮屏、投屏、截屏和闪光灯不是完成信号。
