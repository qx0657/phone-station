# adb-keep.sh

在手机上安装「无线调试保持」。Wi-Fi 连着、USB 调试还开着时，它把被系统关掉的无线调试重新打开。日常用法见 [../README.md](../README.md) 的「无线调试保持」。

包名是 `dev.phonestation.adbkeep`。源码在 `lib/adb-keep/`，没有 Gradle，也没有 AndroidX。

## 开关为什么会自己关

无线调试的开关是 `settings global adb_wifi_enabled`。USB 调试是 `adb_enabled`。

AOSP 的 `AdbDebuggingManager` 在 Wi-Fi 关闭，或当前 Wi-Fi 网络断开时，把 `adb_wifi_enabled` 写成 0。Wi-Fi 重新连上不会写回 1。荣耀的省电说明里，闲置和离开开发者选项也会关。这台上没有把这几种触发拆开实测；用户碰到的是断开之后开关自己关掉，下次要进开发者选项再开一次。

USB 调试还开着时，`adbd` 不会停，但这条 TLS 会话已经断了。电脑上没有 USB 时，看不到手机，也写不了这个设置。

下面几项解决的是别的问题，留着原值：

- `allow_charging_adb` 是荣耀「仅充电模式下允许 ADB 调试」。这台上已经是 1，无线调试仍会关。
- `adb_allowed_connection_time=0` 是 AOSP 里「一直允许」的授权窗口。它管的是已配对电脑要不要重新点允许，不管总开关。
- 已保存的 Wi-Fi（trusted BSSID）只是跳过「允许这台设备无线调试」对话框，不负责把开关保持为开。
- 这台 MagicOS 9 的设置里没有 AOSP 那个无线调试快捷开关。`com.android.settings` 里能查到的快捷开关只有助听器。

`persist.adb.tls_server.enable` 和 `service.adb.tls.port` 在这台 PGT-AN20 上，无线调试开着时用 shell 读仍是空的。不要拿这两个属性判断开关。

## 为什么不是电脑上的常驻，也不是 Shizuku

电脑上的进程只有在 adb 还在线时才能写设置。开关已经关掉、又没有 USB 时，它无事可做。

手机上的 Shizuku（`moe.shizuku.privileged.api`）和 Brevent（`me.piebridge.brevent`）装过。它们的特权进程是 adb shell 拉起来的子进程，无线会话一断就退出，不能再把开关打开。这台手机没有 root（`ro.boot.flash.locked=1`，`ro.build.tags=release-keys`）。

所以盯开关的是一个普通应用：进程不挂在 adb 会话上，`WRITE_SECURE_SETTINGS` 由 adb 授一次，之后自己写 `adb_wifi_enabled`。

## 它什么时候写

三个条件同时成立才写 1：应用里的「自动打开」开着（默认开）、`adb_enabled` 是 1、当前有 Wi-Fi 网络。USB 调试被关掉时不写，避免把用户关掉的调试重新打开。

Wi-Fi 用 `ConnectivityManager.getAllNetworks()`，认 `TRANSPORT_WIFI`，不要求它是默认网络，也不要求 `NET_CAPABILITY_INTERNET`。这台手机开着 Clash 时，默认网络是 VPN；按默认网络判断会以为没连 Wi-Fi。`getAllNetworks()` 已过时，这里仍用它，就是为了在 VPN 下面看见 Wi-Fi。

系统若觉得当前网络不可信，会把刚写上的 1 立刻拨回 0，并弹出允许对话框。写入之前先把失败次数加一，所以观察者被这次拨回唤醒时，不会马上再写。等待依次是 0、3 秒、15 秒、1 分钟、5 分钟。开关保持为开超过 1.5 秒，或 Wi-Fi 网络的 handle 变了，失败次数清零。平时每 15 分钟再看一次。这段判断在 `KeeperPolicy`，构建时在电脑上跑。

前台服务类型是 `specialUse`。精确闹钟作兜底；`setExactAndAllowWhileIdle` 被拒绝时退到 `setAndAllowWhileIdle`。开机广播和「自己被更新」广播也会拉一次。服务没有 exported：这台 Android 15 上，shell 执行 `am start-foreground-service` 会报 `Requires permission not exported`。安装脚本只启动界面，由应用自己的进程拉起服务。

## 权限和启动管理

`./scripts/adb-keep.sh` 会 `pm grant` 这两项：

- `WRITE_SECURE_SETTINGS`：写 `adb_wifi_enabled`。普通应用不能在界面里申请，只能由 adb 授。
- `POST_NOTIFICATIONS`：常驻通知。渠道 `keep`，重要性低，无声，同一条不重复响。

同时把包名放进 `deviceidle` 白名单。

荣耀的应用启动管理拦自启动。shell 里没能替用户打开这项。重启后要自己起来，需要在应用启动管理里允许自启动和后台活动。在系统里强行停止之后，进程不会自己回来，需要再打开一次应用。开机这条路径没有在这台上重启验证过。

## 构建

`lib/adb-keep/build.sh` 用本机的 OpenJDK、`platforms/android-35` 和 build-tools 打包。没有这些时，先按 [android-cli.md](android-cli.md) 安装 SDK，再执行 `android sdk install platforms/android-35 build-tools/35.0.0`。

签名存在 `build/adb-keep.keystore`，这个目录不入库。换电脑或删掉 `build/` 之后签名会变。安装脚本遇到 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 会卸掉再装，应用里的开关状态回到默认开，权限会重新授。

公开的 `android.jar` 里没有 `Theme.DeviceDefault.DayNight.NoActionBar`。界面用 `Theme.DeviceDefault.Light.NoActionBar`。图标用的是系统 drawable，包里几乎没有自己的资源。logcat 里可能出现 `No package ID 7f found for resource ID 0x7f080592`，界面和通知仍在。

菜单栏 App 的打包列表里没有这个脚本。无线调试保持装在手机上，不从菜单栏启动。

## 在 PGT-AN20 上验证过的

机型 PGT-AN20，MagicOS 9.0.0 / Android 15。2026-10-01：

- `pm grant` 之后，用户 0 的 `WRITE_SECURE_SETTINGS` 是 `granted=true`。用户 10 是 `granted=false`，主用户是 0。
- 应用 uid 写入当前的 `adb_wifi_enabled` 成功，日志是 `AdbKeep: write-ok`。当时值已经是 1，这次写入没有把开关关掉。
- Clash 开着时，界面仍显示 Wi-Fi 已连接。
- 前台服务 `isForeground=true`，类型 `0x40000000`。通知标题「无线调试保持」，内容「无线调试开着」，不响。
- 白名单里有 `dev.phonestation.adbkeep`。下一次精确闹钟挂在 `AlarmReceiver` 上。
- 界面标题和说明在状态栏下面。状态栏高度约 141px，标题从约 211px 开始。

没有做的：把 `adb_wifi_enabled` 写成 0 看它会不会写回来。当时只有无线会话，写成 0 会立刻断开，电脑就无法确认结果。USB 插着时可以再测这一条。也没有重启手机，所以开机自启还没在这台上看到。
