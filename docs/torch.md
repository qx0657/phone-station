# torch.sh

开关闪光灯，以及让闪光灯跟着电脑正在播放的声音闪。日常用法见 [../README.md](../README.md) 的「闪光灯」。

## 为什么不用设置项或灯节点

在 PGT-AN20 上，`/sys/class/leds` 下面没有闪光灯。`settings put secure flashlight_enabled 1` 只会改掉那个值，灯不亮。系统快捷开关自己走的是 `CameraManager`。

shell 用户有 `CAMERA` 和 `FLASHLIGHT` 权限，所以 `lib/torch.dex` 里的 `Torch` 可以直接调用：

- `setTorchMode` 关灯
- `turnOnTorchWithStrengthLevel` 开到某一档

亮度档数读 `FLASH_INFO_STRENGTH_MAXIMUM_LEVEL`。PGT-AN20 后置主摄是 4，所以这台上是 1、2、3、4。`on` 用手机回报的最高档。

相机服务把这次开灯绑在调用进程上。进程一退出，灯就灭。`on` 和指定亮度会用 `setsid` 在手机上留一个 `app_process`，不占着电脑上的终端。`off` 先结束这个进程，再显式关一次灯。

## 跟着电脑的声音

`beat` 不听麦克风，也不走 BlackHole。它抓的是各进程正要送去播放的混音，耳机或扬声器都一样，播放设备不用改。

电脑上的 `lib/torch-audio/torch-audio` 每 30 毫秒打出一次音量。`lib/torch_beat.py` 把音量换成 0 到手机的最高档：鼓点这类突然变响的声音会冲到高档再落下来，一直维持同一响度的声音会停在较低的档。数字通过同一次 `adb shell` 写给手机上的 `Torch listen`，灯跟着改，不会每档都新开一次 adb。亮度可以连续改，但两次改动至少隔 120 毫秒。相机如果拒绝某一次改亮度，或者相机服务重启后原来的编号暂时失效，监听进程会重新找闪光灯并继续，不会退出。在 PGT-AN20 上，真正关灯会拆掉闪光灯会话，连续开关大约一分钟后，高通相机服务会自己退出。所以跟随声音时，短暂停顿只保持上一档亮度，安静超过大约 0.8 秒才关灯。结束时一定会关掉。

第一次运行如果系统弹出权限，到「隐私与安全性」里允许「屏幕与系统音频录制」或「系统音频录制」。允许的是音量，不录屏幕，也不改输出设备。`Ctrl+C` 会关灯并拆掉这次采集。

编好的 `lib/torch-audio/torch-audio` 不入库。没有这个文件，或 `main.swift` 比它新时，`torch.sh beat` 会自己用 `swiftc` 编译。

## 源码和产物

| 路径 | 作用 |
| --- | --- |
| `lib/torch/Torch.java` | 在手机上开关闪光灯、按行读亮度 |
| `lib/torch/stubs/` | 只给 javac 用的 Android 类声明 |
| `lib/torch/build.sh` | 重新生成 dex |
| `lib/torch.dex` | `torch.sh` 推到手机上的程序 |
| `lib/torch-audio/main.swift` | 采集电脑正在播放的声音 |
| `lib/torch_beat.py` | 把音量换成亮度 |

## 重新编译

改了 `Torch.java` 之后：

在仓库根目录运行：

```bash
lib/torch/build.sh
```

D8 来自 R8 8.9.35，不放进工程。`build.sh` 用 `--release 17` 编译，原因和 [notify.md](notify.md) 里一样。编完再运行 `./scripts/torch.sh on`，灯应亮起；`./scripts/torch.sh off` 后熄灭。
