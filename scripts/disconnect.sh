#!/bin/zsh
# 说明见仓库根目录 README.md
# 断开无线 adb。不带参数时断开全部无线连接，USB 不受影响。
# 用法：disconnect.sh
#       disconnect.sh 192.168.0.103:42125
set -euo pipefail
DIR=${0:A:h}
source "$DIR/../lib/common.sh"
ADB=$(adb_bin)

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  print -r -- "用法: disconnect.sh [序列号或 IP:端口]"
  print -r -- "只断开无线连接，USB 保持不动。"
  exit 0
fi

if [[ -n "${1:-}" ]]; then
  "$ADB" disconnect "$1"
else
  "$ADB" disconnect
fi
"$ADB" devices -l
