# Phone Station 公网中继

本目录是手机工位公网中继的唯一通用源码。Mac 本机网关在 `../../lib/remote-gateway/`，不能替代本服务。迁移来源和工作区摘要见 [MIGRATION.md](MIGRATION.md)，完整协议见 [中继合约](../../docs/relay-contract.md)。

## 构建与验收

在仓库根目录运行：

```sh
./scripts/check.sh
./scripts/build-relay.sh
```

构建默认生成 Linux/amd64 发布包和来源回执；`--host` 生成本机隔离实例程序。只使用 Go 标准库。全量检查包含真实服务端的持久回执、TLS 联合协议、重启、强杀与存储失败测试；不连接生产实例或手机。公网隔离实例另用 [check-relay.sh](../../docs/relay-contract.md#隔离实例检查)。

## 配置

使用 [deploy/config.example](deploy/config.example) 的字段，配置文件和 TLS 私钥权限 0600，配置目录 0700。手机/电脑角色令牌必须不同且为随机值；默认监听端口只是样例，具体端口以部署配置为准。不在 shell 参数、仓库、日志或 App bundle 中放入凭据。

| 字段 | 含义 |
|---|---|
| `PHONE_RELAY_LISTEN` | TLS 监听地址，默认 `0.0.0.0:24443` |
| `PHONE_RELAY_DEVICE_ID` | 此配对的设备标识，默认 `pgt-an20` |
| `PHONE_RELAY_PHONE_TOKEN` | 手机角色 Bearer，至少 32 字符 |
| `PHONE_RELAY_DESKTOP_TOKEN` | 独立的电脑角色 Bearer，至少 32 字符 |
| `PHONE_RELAY_CERT` / `PHONE_RELAY_KEY` | TLS 证书与私钥路径 |
| `PHONE_RELAY_STATE_DIR` | 持久回执目录，默认 `/var/lib/phone-station-relay` |

服务端必须通过 TLS 监听，最低 TLS 1.2；两端仍验证相同 SPKI 公钥指纹。[systemd 模板](deploy/phone-station-relay.service) 为状态目录提供写权限，其余系统目录只读，配置仍在 `/etc/phone-station-relay/config`。现有 root-only 配置沿用；没有自动扩大端口或修改其他服务。

## 操作状态与清理

中继 2 在接收、派发和完成时分别持久提交回执，再发送对应应答。文件使用 0600，目录 0700；写入经过临时文件、文件 fsync、原子替换和目录 fsync。单实例锁防止多进程同时写入。任何不明确的存储失败都会停止接受/派发，返回 503；不能当作确定未执行。

重启后 queued/running 都保守地变为 unknown，既不自动恢复队列，也不重派旧操作；完成结果可恢复。回执只存操作/结果摘要与状态，不存请求正文、令牌或访问日志。结果正文最多 8 份、32 MiB，可读取窗口为 10 分钟；过期内容在服务启动、握手、调用或结果提交时清理，完全空闲时不定时物理删除。正文会包含工具返回的内容，故状态目录同样属于私密数据。

会话代号来自服务端并持久保存，操作 ID 为 `<32位会话代号>.<32位电脑随机值>`。握手在无活动请求且会话满 10 分钟时更新代号；旧回执到期可清理，但旧代号未保留的编号永远拒绝。最多保留 4096 个回执；容量或活动队列已满时，429 明确带 Not-Queued 头。Mac 20 先获取会话，再提交；原内存版只能用于历史对照，不能作为持久版的运行模式。

## 更新与恢复

实际主机、部署授权、配置位置和生产回执由个人运维仓库维护。本仓只提供通用模板和版本化源码。先完成测试、Linux 构建、摘要核对与备份准备，批准停机窗口后才替换运行服务。配置、证书、令牌与持久状态都保留；公网范围不变。

先升级 Mac 20，再升级中继 2；Android 沿用现有编号透传。新服务接收过操作后，回滚必须使用读取同一状态格式并保留去重语义的持久版本，不能退回内存版或删除状态目录。状态格式、发布清单与恢复边界见 [发布与回滚](../../docs/releases.md)。

## 中继 3 的独立屏幕连接

中继 3 保留中继 2 的持久 MCP 状态格式与回执，增加已鉴权的 `/v1/screen/phone/<sessionId>` 和 `/v1/screen/desktop/<sessionId>` WSS。只在双方配对完成后开始视频，消息不占 MCP 队列、不持久缓存；角色隔离、时限、关闭与兼容顺序见 [远程屏幕](../../docs/remote-screen.md)。已有反向代理须透传 WebSocket Upgrade，直连 TLS 不改端口。第三方许可位于 [docs/licenses](../../docs/remote-screen.md#发布与验证边界)。
