# 中继源码迁移来源

2026-10-03 从个人运维仓库的 `tools/phone-station-relay/` 迁入本目录。原提交 `b35393478c0a0fce2b05a39dcb0b1c747a6749bd` 的相关历史通过路径限定补丁导入，作者、时间与提交说明保留；未导入个人仓库的其他文件或历史。

迁移前已有的网络切换与取消处理修改已逐文件复制并核对 SHA-256，然后在本目录继续整改。以下摘要对应迁移前工作区，不对应整改后的源码；后续发布使用发布回执中的提交和产物摘要。

```json
{
  "README.md": "d717ccb156cd1471a8bb986cec6a8ed0930e8775ec65d2952f027cc2f312d5e5",
  "cmd/phone-relay/main.go": "c5e357283f24498f4183fbd39dc34b393587a214186ea06f6abe34e7c840fb60",
  "deploy/phone-station-relay.service": "d70329a18fb85352913e35bbf063dbc3bc1fa6fb12747ec685a24ecb0e4ae063",
  "go.mod": "9af9d70f635f1b6c4e7a20b5c7a44c72bd3e4976a1b7a878e064c80e5a901c0d",
  "internal/relay/server.go": "7a595f2e4b2a02205423d9f30bce006c61bcb5fb6d91a056aa8903367da5a09d",
  "internal/relay/server_test.go": "d239dbb1571545524dcd62a4d2a5af78db8c947629e149ae9278c88855afba6f"
}
```

此目录是通用服务端源码的唯一维护位置。个人服务器仓库只保留运维说明和来源链接。凭据、私钥、持久回执、实际部署清单均不作为源码迁移。
