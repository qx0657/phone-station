# Android CLI

`android` 是 Google 的 Android 命令行工具。它管的是工程、官方文档、模拟器、布局树和安装 APK。`scripts/` 里的手机操作不靠它：投屏走 scrcpy，内部存储里的普通文件走 `scripts/mcp.sh`，做完提醒走 `notify.sh` / `vibrate.sh`。

别人在这台电脑上要做 Android 开发时，再装它。Mac Apple 芯片：

```bash
curl -fsSL https://dl.google.com/android/cli/latest/darwin_arm64/install.sh | bash
```

Mac Intel：

```bash
curl -fsSL https://dl.google.com/android/cli/latest/darwin_x86_64/install.sh | bash
```

Linux：

```bash
curl -fsSL https://dl.google.com/android/cli/latest/linux_x86_64/install.sh | bash
```

Windows 在命令提示符里：

```bat
curl -fsSL https://dl.google.com/android/cli/latest/windows_x86_64/install.cmd -o "%TEMP%\i.cmd" && "%TEMP%\i.cmd"
```

装好之后，配套的 agent skill 用 `android skills` 安装或查看。本仓库不复制那份 skill。和这台手机一起用时，常见的是：

| 命令 | 用途 |
| --- | --- |
| `android info` | 看 SDK 路径和已连接设备 |
| `android docs search` | 查官方文档 |
| `android install` | 把 APK 装到已连接设备 |
| `android layout` | 看当前界面的布局树 |
| `android screen capture` | 截一张 PNG |

屏幕要给人看，仍用 `./scripts/mirror.sh`。手机上的普通文件用 `./scripts/mcp.sh`。
