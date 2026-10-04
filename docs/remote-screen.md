# 远程实时投屏与录屏

Android 63、Mac 0.1.0 (24)、公网中继 3 配合使用时，Mac 首页的「打开投屏」「开始录屏」可在只有远程连接时使用。需要已经配置的远程中继、已运行并授权的 Shizuku 13+，以及 PGT-AN20 / Android 15。旧手机不会启用远程投屏；旧中继返回明确的通道不可用错误。构建与本机回归通过不代表已完成设备安装或真机验收。

本地 adb 在线时继续使用 scrcpy 脚本。只有远程连接时打开原生 Mac 窗口，窗口保持在菜单栏面板外，收起面板不会停止投屏。点击、拖动和滚轮传到手机；右键或 Esc 返回，Home 键回首页，方向键、回车、退格和普通文字按 Android 输入能力处理。安全界面可能是黑屏，不改变安全标记。

远程录屏可以直接开始，也可以在远程投屏期间开始。停止录屏会保留投屏；关闭窗口结束整个会话，并收尾已有录屏。视频和可用的手机音频直接封装成 MP4，保存到 `~/Movies/scrcpy/` 并进入最近文件，不在手机上保存完整录像。没有声音捕获能力时仍可投屏和录制视频。旋转导致编码尺寸改变时结束并保存当前录像，投屏继续；需要继续录制时再次开始，避免产生尺寸不一致的 MP4。保存期间使用隐藏的临时文件，完成后才移动到最终名称。

## 通道与权限

控制会话的三个工具是 `station_screen_open`、`station_screen_status`、`station_screen_close`，参数是提前保存的 32 位小写十六进制 `sessionId`。open 返回编号相同的后台任务，使用 `station_operation_status` 查询创建结果；status 返回原会话状态与视频帧数，不重新采集。丢失创建应答后只查询原任务，不再次提交。close 关闭原会话，可重复调用。

视频不经过串行 MCP 队列。Mac 通过已鉴权的本机 `/__screen/<sessionId>` WebSocket 接入，网关内部读取钥匙串里的电脑凭据，再连接中继 `/v1/screen/desktop/<sessionId>`。手机用自身角色凭据连接 `/v1/screen/phone/<sessionId>`。两端使用 WSS 和既有 SPKI 固定验证；不扩大手机回环监听，不暴露 adb。中继可看到视频、音频与输入，应部署在自己管理或信任的主机上。

屏幕连接仅授予原生 App 的完整网关身份，不把已有 status、文件或 shell 的受限客户端权限升级成屏幕权限。电脑和手机角色不能互换；浏览器 Origin、查询参数中的凭据、重复连接同一角色都拒绝。中继最多容纳一个活跃会话，配对等待最多 20 秒，双方连上后才允许手机开始捕获。会话结束后在本次中继运行期间保留 24 小时的旧编号拒绝记录（最多 4096 条），过期按新请求清理；手机后台回执也保留 24 小时。客户端始终生成新编号，不恢复原采集；这些临时编号与持久 MCP 回执分别管理，不写视频结果缓存。

## 实现与生命周期

手机非 daemon Shizuku UserService 从 APK 提取已固定摘要的 scrcpy-server 4.1 和 Android/arm64 视频桥到 shell 所有的 0700 目录。Jar 只读，辅助程序校验摘要后执行；角色令牌经 Binder 和 stdin 传入内存，不进命令参数或临时文件。DNS 在 Android 应用中解析，避免纯 Go 在 Android 上没有系统 DNS 配置的问题。辅助程序 stderr 用单独线程排空，不使用桌面 JDK 的 `ProcessBuilder.Redirect.DISCARD`；Android 15 没有该字段，桌面 `javac --release 17` 的编译通过不能证明 ART 支持它。

打开会话时唤醒手机，息屏时间继续使用既有设置。scrcpy 用 MediaCodec 编码 H.264 与 AAC，默认最长边 1600、30 fps、视频 2 Mbps、音频 128 kbps。独立连接传输完整编码帧；单个消息最多 4 MiB，写入阻塞超过 5 秒时结束，避免堆积旧画面或丢失参考帧。手机每 5 秒验证视频连接，最长会话为 1 小时。输入只接受按键、文字、触摸、滚动和返回消息，不转发 scrcpy 的剪贴板同步或文件操作。

Mac 使用 AVSampleBufferDisplayLayer 和 AVSampleBufferAudioRenderer 同步播放；录屏使用 AVAssetWriter 直接封装压缩数据，避免重复编码。AAC 的 Android AudioSpecificConfig 转为 Core Audio ES descriptor，音频 packet description 使用一个压缩包，不能将 PCM 帧数误作描述项数。

网络断开、关闭窗口、手机远程开关关闭或配置更换、手机 MCP 停止、手机应用或 Shizuku 退出都会结束采集。辅助程序的 stdin 保持打开作为服务租期，服务死亡关闭管道后停止 scrcpy；同一会话不自动重连或重建。Mac 下次启动新投屏前只清理尚未确认关闭的旧编号。

## 发布与验证边界

先准备并验证中继 3、Android 63、Mac 24 的产物。公网中继升级保留原配置、证书、角色令牌及完整持久状态目录；MCP 状态格式仍为 durable-epochs-v1，与中继 2 兼容。实际切换遵循 [发布与回滚](releases.md) 的停机授权和回执规则。已有反向代理须透传 WebSocket Upgrade；直连既有 TLS 中继无需增加端口。

本机验证包含真实 WebSocket 双向转发、角色隔离、重复连接与旧编号拒绝、关服清理、scrcpy 4.1 session 元数据与帧边界、输入白名单，以及原生 H.264/AAC 写入 MP4 后再解码。真机延迟、蜂窝切换、声音和点击效果应在部署回执中单独登记。

第三方许可随包附带：[scrcpy Apache-2.0](licenses/scrcpy.txt)、[coder/websocket ISC](licenses/coder-websocket.txt)。
