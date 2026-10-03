---
name: screenshot-cleanup
description: 判断已连接 Android 手机图库里哪些截图可以删。用户说清理截图、整理图库、哪些截图能删、按信息价值处理截图，或在同一目录里找重复截图时使用。截取当前画面用 scripts/screenshot.sh。不用于电脑截屏、投屏或录屏。
---

# 图库截图

标准在 `docs/screenshot-cleanup.md`。归类和去重跑本技能里的脚本，不要现写一遍，也不要加到仓库 `scripts/`。

在仓库根目录：

```bash
python3 .agents/skills/screenshot-cleanup/scripts/cleanup.py
python3 .agents/skills/screenshot-cleanup/scripts/cleanup.py list 游戏
python3 .agents/skills/screenshot-cleanup/scripts/cleanup.py dupes
```

不带参数按信息价值统计可以删的五类。`list` 列出这一类在手机上的路径。`dupes` 找正文几乎相同的重复张，大约两分钟。这三条都不删除。

文档没写过的界面，先抽不同日期的几张看内容，把抽样结论告诉用户。用户确认之后，再改脚本里的 `CLASSES` 和文档里的表。

用户点名类别，或点名按名单删，之后再删。删除不进回收站。删之前先报这一批的张数和体积。

删除只针对 `list` 打出的路径，或 `dupes` 里标成可删的路径。只删以 `.jpg` 或 `.jpeg` 结尾的文件。不删目录，不删 mp4，不用通配符，不删 `dupes` 里标成留下的那张。脚本在手机计算指纹，仅将候选原图经 adb 取到电脑临时目录核对，结束后清理；不要另写下载与比对流程。每张待删图都必须直接匹配最终留下的那张，不能沿相似链合并；解码失败或没有界面名的图不进重复删除名单。

点名之后的删除走手机上「手机工位」的文件 MCP，按 `.agents/skills/phone-mcp/SKILL.md` 连接并查询 `station_file_access_policy`。每条先核对当前文件与名单一致，再将 `station_file_stat` 读到的 `targetVersion` 和绝对路径传给 `station_file_delete`，一次一条。文件已变化则跳过并报告，不沿用旧名单强删。删除会刷新媒体库；不用目录删除工具或 `adb shell rm`。
