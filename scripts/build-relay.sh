#!/bin/zsh
# 构建公网中继发布包；不连接服务器、不读取生产凭据。
set -euo pipefail
DIR=${0:A:h}
source "$DIR/../lib/common.sh"
if [[ ${1:-} == -h || ${1:-} == --help ]]; then
  print -r -- "用法: build-relay.sh [--host]"
  print -r -- "默认运行中继回归并构建 Linux/amd64；--host 构建本机隔离实例用的程序。"
  print -r -- "产物、摘要和源码版本在 build/.phone-station/relay/；不部署、不读取服务器配置。"
  exit 0
fi
if (( $# > 1 )) || [[ ${1:-} != "" && ${1:-} != --host ]]; then
  print -u2 -- "用法: build-relay.sh [--host]"; exit 1
fi
ROOT=${DIR:h}
go -C "$ROOT/server/relay" test -race ./...
target_os=linux
target_arch=amd64
if [[ ${1:-} == --host ]]; then
  target_os=$(go env GOOS)
  target_arch=$(go env GOARCH)
fi
out="$ROOT/build/.phone-station/relay/$target_os-$target_arch"
mkdir -p "$out"
CGO_ENABLED=0 GOOS=$target_os GOARCH=$target_arch go -C "$ROOT/server/relay" build -trimpath -o "$out/.phone-relay-$$" ./cmd/phone-relay
mv "$out/.phone-relay-$$" "$out/phone-relay"
python3 "$ROOT/lib/release_manifest.py" relay "$out/phone-relay" "$out/manifest.json"
print -r -- "$out/phone-relay"
print -r -- "$out/manifest.json"
