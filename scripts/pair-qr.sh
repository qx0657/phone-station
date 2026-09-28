#!/bin/zsh
# 说明见仓库根目录 README.md
# 在浏览器里打开配对二维码。
# 手机：开发者选项 → 无线调试 → 使用二维码配对设备，扫这张码。
set -euo pipefail
DIR=${0:A:h}
if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  print -r -- "用法: pair-qr.sh"
  print -r -- "浏览器打开二维码。手机：无线调试 → 使用二维码配对设备。"
  exit 0
fi
if [[ ! -d "$DIR/../lib/node_modules/qrcode" ]]; then
  print -r -- "第一次使用，安装二维码依赖…"
  npm install --prefix "$DIR/../lib" --silent qrcode
fi
exec python3 "$DIR/../lib/pair_qr.py"
