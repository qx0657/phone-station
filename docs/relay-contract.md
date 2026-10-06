# 公网中继协议与恢复边界

公网服务端源码在 [server/relay](../server/relay/README.md)，Mac 网关在 `lib/remote-gateway/`。这是三端共用的协议入口；个人运维记录只维护实际部署。中继 5 沿用中继 2 的 durable-epochs-v1 持久协议和中继 3 的 [独立屏幕连接](remote-screen.md)，新增 WSS 控制与事件协议，见 [远程控制与事件](remote-control.md)。Mac 20 起支持持久会话握手，Android 透传编号。

## 接口与身份

地址是 HTTPS 服务根地址；客户端在其后拼接接口路径。通过路径前缀反代时，代理须移除此前缀再交给服务端。手机/电脑各使用一枚不同 Bearer；两端校验固定 SPKI，不自动跟随重定向。

中继 4 仅接受原生客户端，拒绝 Origin、重复 Authorization 和接口之外的查询参数；operation 查询只允许单个 id。JSON 正文拒绝重复字段（包括转义同名键）、非 UTF-8、超过手机解析深度及尾随值。权限与信任边界见 [远程安全与授权](security.md)。

| 接口 | 角色 | 请求与应答 |
|---|---|---|
| `GET /v1/desktop/status` | 电脑 | 返回 deviceId、online、version、durable、operationEpoch，以及 controlProtocol、controlConnected、controlSession；握手可能推进会话代号并清理过期回执，不调用手机工具 |
| `POST /v1/desktop/call` | 电脑 | `{operationId,payload:<MCP请求>}`；完成返回 `{status,body:<MCP应答>}` |
| `POST /v1/phone/capabilities` | 手机 | `{}`；返回支持的 controlProtocol，不执行手机操作 |
| `GET /v1/phone/control` | 手机 | WSS control-v1：指令、结果、心跳与订阅租约 |
| `GET /v1/desktop/events` | 电脑 | WSS events-v1：订阅、变化信号与心跳，不传正文 |
| `POST /v1/phone/poll` | 手机 | `{waitMs:2000}`；范围 250–25000 ms；空闲返回 `{idle:true,operationId:null}`，有请求返回 `{operationId,payload}` |
| `POST /v1/phone/result` | 手机 | `{operationId,response:{status,body}}`；收到并持久确认后返回 200 JSON；202 结果可省略 body |
| `GET /v1/desktop/operation?id=…` | 电脑 | 查看 queued/running/complete/expired/unknown，不重新提交；不存在也为 unknown |

中继与 Mac 网关请求体上限 18 MiB，手机本地 MCP 为 8 MiB。中继等待电脑请求最多 3 分钟，Mac 传输期限最多 4 分钟，手机原结果上报窗口为 3 分钟。单个活动中继请求与手机后台任务是不同层次，后台任务提交后立即返回，由手机自己的有界队列执行。

## 会话编号与兼容

Mac 20 在提交前，用固定 TLS、公网电脑角色令牌和 3 秒限时读取 status；取得 32 位小写十六进制 operationEpoch，然后生成同样长度的随机值，拼为 `<epoch>.<random>`。MCP JSON-RPC id 与手机 jobId 不用这个格式，不能混用。握手失败、状态异常或代号格式无效时不提交。

中继状态绑定配对身份摘要，代号跨重启保留。只有无活动请求且现有代号满 10 分钟时，握手才持久切换代号。旧代号的已有回执仍按原状态返回；旧代号不存在的编号返回 410，永远不当新操作。回执最多 4096 项，结果正文最多 8 项 / 32 MiB，可读取窗口为 10 分钟。正文与回执分开清理；当前会话的过期回执保留去重信息，旧会话的过期回执可删除。清理在启动、status 握手、call 或 result 请求时执行，完全空闲时不按时物理删除，不能把读取窗口当作严格的数据删除期限。

Mac 20 对没有代号的旧中继仍使用旧随机编号，以支持先升级电脑。新中继要求会话编号，必须按 [发布顺序](releases.md#兼容顺序与回滚) 更新；不会给旧 Mac 自动降级到不持久的执行方式。

## 接收、执行与恢复

配对身份、operationId、负载摘要和状态必须先可靠提交，再确认接收或派发。相同编号相同负载共享结果，相同编号不同负载返回 409，不替换旧请求。手机副作用和中继状态之间没有分布式事务，不能承诺 exactly-once；采用“结果未知时不重做”的取舍。

| 状态或事件 | 行为 |
|---|---|
| 新操作，队列或回执容量满 | 返回 429 及 `X-Phone-Station-Not-Queued: 1`，不留下接收记录 |
| 接收成功 | 持久 queued 后才唤醒手机控制连接或旧版轮询 |
| WSS 派发或手机 poll | 持久 running 后才向手机发送；连接接替与重复 poll 不重派已运行操作 |
| WSS 或 HTTP result | 正文和 complete 回执可靠提交后才确认；同结果重传返回 200，冲突结果 409 |
| 电脑取消或断线 | 未派发标为 expired；已派发 ping 可标 unknown 并释放，其他操作等待原结果；不重做 |
| 重启 | queued/running 保守转 unknown，不恢复派发；complete 返回原结果或正文已过期的 410 |
| 旧编号结果未知、正文过期或代号过期 | 返回 409/410 或 unknown；都不是重提许可 |
| 落盘失败或状态损坏 | 不接受/派发，503 或启动失败；保留原数据，不静默重置 |

Mac 19 起，公网 call 应答丢失、网络错误或其他 HTTP 错误都不自动重发。只有 429 同时带明确 Not-Queued 头才有界退避，保留原编号与原负载；Mac 20 继续此规则，即使中继已支持持久化。手机可重传原结果，不重新执行工具。电脑回环通道原有的只读白名单恢复仍保留；shell、截图、安装优先按手机原任务编号查询。

## 仓库内联合检查

`./scripts/check.sh` 运行服务端 Go race、真实文件系统持久化、丢失应答、结果/负载冲突、缓存限额、代号轮换、存储失败、强杀和单实例锁测试。相同黑盒合约还运行在真实中继源码构建出的本机 TLS 实例上，重建服务后核对持久状态。检查器自测保留，确保丢回执的模拟服务确实失败。全程不连接生产主机或手机。

## 隔离实例检查

`./scripts/check-relay.sh <私有配置文件>` 模拟手机和电脑，并执行配置中的重启命令。只能用独立测试实例、空队列、独立数据目录和两枚测试令牌，不能连接生产实例或真实手机。即使负载只是 ping，检查器也会取走队列项和重启服务。

配置放仓库外，权限 0600。示例中的域名、指纹与令牌都需替换成专用测试值：

```json
{
  "isolated": true,
  "endpoint": "https://test-relay.example.com/station",
  "pin": "测试证书的64位SPKI十六进制摘要",
  "phoneToken": "独立的手机测试令牌",
  "desktopToken": "独立的电脑测试令牌",
  "restartCommand": ["/absolute/path/restart-isolated-relay.sh"]
}
```

重启脚本必须保留原数据目录，25 秒内完成且就绪后才退出；按参数数组直接执行，输出不进测试日志。检查器限时 75 秒，严格固定 SPKI，不跟随重定向。缺配置直接失败，不静默跳过。

公网生产部署和手机权限、重启、网络切换仍需另外验收。本机通过不能写成生产已部署；本轮明确状态见 [发布记录](releases.md)。历史外部中继边界见 [历史检查](history/validation.md)。
