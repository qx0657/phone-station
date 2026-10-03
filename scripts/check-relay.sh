#!/bin/zsh
# 仅对专用测试中继运行；配置和验收语义见 docs/relay-contract.md。
set -euo pipefail
DIR=${0:A:h}
if [[ ${1:-} == -h || ${1:-} == --help ]]; then
  print -r -- "用法: check-relay.sh <权限为 0600 的隔离中继 JSON 配置>"
  print -r -- "会模拟手机与电脑并执行配置里的重启命令；禁止使用生产中继或正在连接手机的实例。"
  print -r -- "配置见 docs/relay-contract.md。不会调用 adb，也不会向手机执行工具。"
  exit 0
fi
if (( $# != 1 )); then
  print -u2 -- "用法: check-relay.sh <隔离中继 JSON 配置>；详情见 --help"
  exit 1
fi
export PHONE_STATION_RELAY_CONTRACT_CONFIG=${1:A}
exec go -C "$DIR/../lib/remote-gateway" test -count=1 -tags relaycontract -run '^TestRelayContract$' -v -timeout 75s .
