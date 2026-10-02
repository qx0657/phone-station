# 共享剪贴板

日常操作见 [README](../README.md#共享剪贴板)。入口是 Mac 主页、手机侧栏里的「共享剪贴板」。首次安装共享关闭；开启共享后自动双向同步默认开启，可单独关闭自动同步以使用预览和手动复制。

## 后台读取

Android 10 起普通后台应用不能读取剪贴板，仅前台有焦点的应用或默认输入法可以读取。因此手机工位使用已经授权的 Shizuku 13+ 创建专用 `ClipboardUserService`，以 shell/root 身份调用系统剪贴板服务；它只提供读取、写文字、停止三个操作，不执行 shell 命令，不改变输入法，不抢焦点。

服务核实 Binder 调用方必须是手机工位 UID，并在系统调用前清除传入 Binder 身份。系统调用使用 `com.android.shell` 的包身份及手机工位所在 Android 用户，默认设备编号为 0。系统接口签名按运行版本检测。PGT-AN20 的 MagicOS 9 在读取接口末尾增加 `userOperate` 布尔参数；适配已知接口并传 true，未知签名拒绝调用。关闭共享、停止 MCP 或 Shizuku 不再可用时释放服务，清除预览。APK 更新后重新绑定独立版本的服务。

手机锁定时返回 `locked`，不读文字也不自动写入。`ClipDescription` 的 `android.content.extra.IS_SENSITIVE` 为 true 时不传文字；Mac 遇到 `org.nspasteboard.ConcealedType` 或 `org.nspasteboard.TransientType` 同样跳过。本 App 复制 MCP 授权头时附加 ConcealedType。敏感标记由复制内容的应用提供，未标记的文字不能据此识别。

## 交换与冲突

Mac App 在运行期间每约 0.8 秒通过统一网关调用 `station_clipboard_exchange`，一次只允许一个交换请求。共享关闭时调用只读设置，不读取系统剪贴板；共享开启时交换 Mac 当前文本和手机当前文本。手机页独立刷新内存中的 Mac 预览，最后收到 Mac 交换超过 10 秒则显示未连接。内容没有历史记录，不写入偏好、文件或日志；偏好只保存共享与自动开关。

使用手机随机版本、Mac `NSPasteboard.changeCount` 以及本次运行的客户端 UUID。手机读取内容与 `ClipDescription.getTimestamp()` 的复制事件标记，相同文字重新复制也会产生新版本；不比较两端时钟。首次接入、长时间断线或服务重启，只建立基线，不覆盖已有内容。自动同步开关改变也会重建基线。之后 Mac 发生复制且手机仍是上次观察版本时，把 Mac 文字写到手机；手机版本改变时，把手机文字写到 Mac。手机写入前再读一次、写入后核对结果。两端同时变化时手机版本优先；Mac 在请求途中又复制时，旧响应不得覆盖那次新复制。

回传内容相同时不再次写入；相同 Mac 版本的重复请求不重复写。业务请求不进入网关跨通道重放白名单；超时或断线会丢弃同步基线，下次成功交换重新核对。两条通道共用手机应用中的状态与服务，因此切换 adb/远程本身不会制造回传循环。

支持文字与链接，按 UTF-16 长度最多 100000；只取 Android 第一个 ClipData item 的文字，不读取图片或文件 URI。Mac 文件 URL 即使同时提供路径字符串也跳过。目标端当前是敏感内容、图片、文件或超长文字时，同样不自动覆盖，需先复制可同步的文字。剪贴板清空、图片、文件、超长文字和敏感内容只更新类型状态，不自动覆盖另一端。远程经现有 HTTPS 与证书公钥指纹校验的中继转发；中继能读取文字，使用自己部署或信任的服务。

## 工具

| 工具 | 参数与用途 |
| --- | --- |
| `station_clipboard_get` | 只读手机当前剪贴板，返回 `kind` 和非敏感的 `text`，不依赖共享开关 |
| `station_clipboard_state` | 只读共享、自动开关、已有预览、手机版本和 Mac 在线状态 |
| `station_clipboard_configure` | 可选 `shared`、`automatic` 布尔值，改变设置 |
| `station_clipboard_exchange` | Mac 提供 `clientId`、`macVersion`、`macKind`、可选 `macText`、上次 `phoneVersion`；省略客户端参数只刷新手机预览；可能写手机剪贴板 |
| `station_clipboard_set` | 原来的手动写手机文字工具；应用自身写入，不要求共享开启或 Shizuku |

共享关闭时状态的两端预览为 null。共享开启但 Shizuku 不可用时返回明确的 `error`；Mac 不用错误响应执行任何自动写入，手机页提供权限入口。`station_clipboard_get` 的 `kind` 与交换结果的 `phone.kind` 可以是 text、empty、unsupported、sensitive、oversize、locked。

## 验证

电脑测试 `ClipboardStateTest` 覆盖首次连接、Mac 复制、手机同时复制优先、重复请求、手动模式、离线过期、敏感数据不序列化、重启基线与相同文字重新复制。`ClipboardSyncPolicyTest` 覆盖手机复制到 Mac、请求途中的本机新复制、回传抑制、敏感本机剪贴板和重连。完整构建由 `scripts/build-mac-app.sh` 执行。

2026-10-02，荣耀 PGT-AN20（Android 15 / MagicOS 9）与 Mac 完成实测：

- 本地无线 adb 与远程中继分别运行同一组原生 Mac 客户端测试，双向自动同步、手动复制、预览、换行/中文/emoji、敏感 Mac 内容跳过、回传抑制、相同手机文字重新复制与断线重连均通过。远程测试期间只移除 adb MCP 转发，保留无线调试；网关明确为 remote、localOnline=false。
- 后台读取通过专用 Shizuku UserService；首次按 AOSP 签名调用失败，发现荣耀接口差异后修正。读取与写后核对均通过，应用没有取得前台焦点也可读取。
- 测试保存并恢复两端原剪贴板内容，不输出真实内容。测试后的空手机剪贴板恢复为空文字；未创建历史或内容文件。
- Android 构建、Mac 完整构建、新增同步/签名测试与 MCP HTTP 路由测试通过。

实测没有重启手机或主动停止 Shizuku；相应不可用状态由状态检查和错误路径处理。没有验证其他机型、Android 多用户或图片/文件同步。

上述实测结束时，安装版本为 Android 42 与 Mac 0.1.0 (3)，两端已启动并开启共享和自动双向同步。Mac 位于 `/Applications/手机工位.app`，签名验证通过，主程序与随包 APK 均与当次实测构建一致；远程升级返回 `completed: true`。原有中继配对与 Shizuku 授权保留。

两端界面使用原生组件：Mac 检查浅色与深色 NSHostingView 渲染，手机检查实际浅色、深色和 1.3 字号页面。PGT-AN20 写入 `font_scale` 后，旧 Activity 的文字可能不立即变化；验证字号需要重建 Activity，再核对实际字形变大，不能只看设置值。截图位于忽略的 `.impeccable/review/`；Mac 截图原生控件处于非活动外观，实际操作已由客户端实测覆盖。
