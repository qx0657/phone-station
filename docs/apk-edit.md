# 修改 APK

手机上的改动走 MT 的编辑会话，`mt_apk_build` 生成新包，源包不动。工具表和当时的文件权限在 [mt-mcp.md](mt-mcp.md)。这篇只写 MT 覆盖不到的四步：把 dex 读成 Java、脱掉 APK 的壳、改 so 里的函数逻辑、结构上的重打包。

电脑上的步骤用作者机器上的 `~/dev/skills/reverse-skill` 里对应的 skill，做之前打开那份 `SKILL.md`。那份仓库不在本仓库里。公开克隆没有 `decode.sh` 和 `rebuild-sign-install.sh`，下面用到它们的命令只在作者这台 Mac 上能直接跑。不跑它的总控、`case-init` 和 Kali bootstrap，也不跟每份 skill 文首的「读完立刻执行」和 `field-journal`。缺命令时看文末。

## 按改动选地方

同一个包只有一个写入方。MT 的编辑会话和 apktool 解开的目录不要一起改。

| 改动 | 做法 |
| --- | --- |
| 已有 Smali、已有资源值 | MT。先 `mt_apk_read_text` 或 `mt_apk_resource_read`，改的时候带上 `targetVersion` |
| 要把 dex 读成 Java | `skills/apk-reverse/` 的 jadx。类名再映射回 MT 的 locator |
| 加固包里看不到业务类 | 脱 APK 的壳，不需要 root。之后改脱出来的那一份 |
| so 里某个函数怎么跳、返回什么、接着调用哪一段 | 手机上先读。看不清时用 `skills/radare2/`。汇编写回 `mt_apk_native_patch_instructions`。原窗口放不下时，改电脑上的 so，再走 apktool |
| so 的伪代码、调用图、数据流 | `skills/ida-reverse/`。2026-09-28 这台 Mac 还没有 IDA，这一步先用 radare2 的反汇编 |
| so 里的一处字符串字面量 | `mt_apk_native_patch_string` |
| 加组件、加资源表词条、整段换 dex、整段换 so | `skills/apk-reverse/` 的 apktool |

签名跟写入方走。MT 的密钥在「MCP 服务 → 设置 → APK → APK 签名设置」。apktool 那条用自己的密钥库，见 [结构重打包](#结构重打包)。覆盖安装要和手机里已装的包是同一张证书。

APK 编辑有 VIP 要求，以 MT 里的提示为准。

## 从手机取出文件

电脑上的路径不能当成手机路径。先在仓库根目录跑 `./scripts/connect.sh`，再 `adb pull` 到仓库外面，例如 `~/Downloads/apk/<名字>/`。

```bash
./scripts/connect.sh
adb pull /storage/emulated/0/Download/app.apk ~/Downloads/apk/app/app.apk
```

Home 是整块内部存储时，列 APK 要带路径前缀。源 APK 保持不动。MT 打出来的新包若要在电脑上读，再 pull 那一份。

## 脱壳

脱的是 APK 里的 dex，不需要 root。壳的名字在 `skills/apk-reverse/references/android-advanced.md`。

MT 打开加固包时，dex 里常常只有壳的入口，搜到的 Smali 不是业务代码。先在 MT 里搜这些名字，对上了就脱壳：

| 壳 | 包里能看见的名字 |
| --- | --- |
| 360 | `libjiagu.so`、`com.stub.StubApp` |
| 腾讯乐固 | `libshell*.so`、`com.tencent.StubShell` |
| 梆梆 | `libDexHelper.so`、`com.secneo.apkwrapper` |
| 爱加密 | `libexec.so`、`s.h.e.l.l` |
| 网易易盾 | `libnesec.so` |
| 娜迦 | `libnaga.so` |

做法是 BlackDex：装到这台手机上，对目标应用脱壳。产物在内部存储里，路径以应用当次显示的为准。

```bash
./scripts/connect.sh
adb pull <手机上的产物路径> ~/Downloads/apk/app/unpacked/
jadx -d ~/Downloads/apk/app/jadx ~/Downloads/apk/app/unpacked/<dex 或 apk>
```

脱出来的是业务 dex。要改这些类，打开脱出来的那一份：`mt_apk_open` 它在手机上的路径，或者把 dex 放进 apktool 工程。原包里的壳保持不动。

## 读 Java

按 `skills/apk-reverse/SKILL.md`。先 jadx，需要改动的类再回到 MT 的 Smali。

```bash
jadx -d ~/Downloads/apk/app/jadx ~/Downloads/apk/app/app.apk
```

Java 名 `com.example.Foo` 对应 MT 的 locator `dex_class:Lcom/example/Foo;`。

`jadx` 已经在 `PATH` 里时，也可以只跑解包脚本：

```bash
bash ~/dev/skills/reverse-skill/skills/apk-reverse/scripts/decode.sh \
  ~/Downloads/apk/app/app.apk --out ~/Downloads/apk/app --skip-apktool
```

这个脚本末尾用 `grep -P` 取包名。macOS 自带的 grep 没有 `-P`，摘要里的 `package` 可能是空的，`jadx/` 目录里的 Java 还在。

## 改 so 里的逻辑

先把函数读出来，再改它的指令。

手机上用 `mt_apk_native_inspect`、`mt_apk_native_disassemble`、`mt_apk_native_xref`、`mt_apk_native_function_cfg`、`mt_apk_native_map_address`。MT 做不了 so 的动态调试，也做不了完整的 C/C++ 反编译。

手机上的反汇编不够看时，把 so 抽出来，按 `skills/radare2/` 看。这台手机是 arm64，先看 `lib/arm64-v8a`。包里还有别的 ABI 时，改哪一份就看哪一份。

```bash
unzip ~/Downloads/apk/app/app.apk 'lib/arm64-v8a/*.so' -d ~/Downloads/apk/app/so
```

静态注册的导出以 `Java_` 开头，名字里带包名、类名和方法名。动态注册在 `JNI_OnLoad` 里。`rabin2` 看概况，`aaa` 之后 `pdf` 看函数、`axt` 看引用。

伪代码、调用图和数据流按 `skills/ida-reverse/`。2026-09-28 这台 Mac 上没有 IDA Pro：`/Applications` 里没有，`IDADIR` 没设，`127.0.0.1:13337` 也没在听。装好并且 `http://127.0.0.1:13337/mcp` 在听之后再用。那份 skill 附带的 `start.ps1`、`open.ps1` 是 Windows 脚本，这台 Mac 不跑。skill 不提供 IDA 的安装包。在此之前继续用 radare2。

逻辑确定之后，用汇编写回 MT 的编辑会话，工具是 `mt_apk_native_patch_instructions`。它把汇编编成机器码，核对当前字节、地址和对齐，写入原来的那段可执行映射。汇编比这段短时，用该架构的 NOP 补满。然后 `mt_apk_edit_check`，再 `mt_apk_build`。

新逻辑比原来那段长、原窗口放不下时，改磁盘上的这份 so。用 radare2 的 `r2 -w` 和 `wa`。IDA 装好之后，也可以用 `idapro_patch_asm`。改完放进 apktool 工程，按下一节打包。这份 so 不要再开 MT 的指令补丁。

## 结构重打包

按 `skills/apk-reverse/`。解开之后才能做的改动：加组件、加资源表词条、换整段 dex、换整段 so。MT 不能把 dex、资源表、二进制 XML 当成普通文件换进 APK。资源表里的新语言、新词条在解开后的工程里改，或者先用 MT 的 ARSC 编辑器。

```bash
apktool d ~/Downloads/apk/app/app.apk -o ~/Downloads/apk/app/apktool
```

重打包、对齐、签名、安装：

```bash
export PATH="$HOME/Library/Android/sdk/build-tools/<版本>:$PATH"
bash ~/dev/skills/reverse-skill/skills/apk-reverse/scripts/rebuild-sign-install.sh \
  ~/Downloads/apk/app/apktool --device <serial>
```

`zipalign` 和 `apksigner` 要在 `PATH` 里，它们来自 Android SDK 的 build-tools。安装前先 `./scripts/connect.sh`，保证只有一台在线设备。脚本加上 `--install` 会直接 `adb install`；`--reinstall` 是覆盖安装。

默认密钥库是 `~/.android/debug.keystore`，别名 `androiddebugkey`，库口令和钥匙口令都是 `android`。文件不存在时，脚本会自己生成。这张证书和商店包、也和 MT 里另配的密钥不是同一张。要跟已装包同一张证书时，用 `--keystore`、`--alias`、`--store-pass`、`--key-pass` 指到那把钥匙。

## 这台 Mac 上的命令

2026-09-28，`PATH` 里没有 `jadx`、`apktool`、`radare2`、`zipalign`、`apksigner`。`~/Library/Android/sdk` 里只有 `platform-tools`，没有 `build-tools`，也没有 `sdkmanager`。`keytool` 在 Homebrew 的 OpenJDK 上。IDA Pro 没装，见上面。

```bash
brew install jadx apktool radare2
```

`decode.sh` 和 `rebuild-sign-install.sh` 找不到命令时会去调 `kali/scripts/bootstrap-reverse.sh`，报错文案里写的是 apt。这台 Mac 不走那条。

## 不跟的部分

`apk-reverse` 在这里只用 jadx、apktool 和解包、重打包脚本。`radare2` 只用上面的侦察和改汇编。`ida-reverse` 等 IDA 装好再用。

三份 skill 文首要求读完立刻执行，并回写 `field-journal`。仓库总控同样要求。手机工位不跟。

Frida、Objection，以及资料里的证书校验和 root 检测脚本，不参与改包。脱壳用上面的 BlackDex。
