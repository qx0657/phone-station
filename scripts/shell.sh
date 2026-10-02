#!/bin/zsh
# 通过远程中继和已授权的 Shizuku 执行手机 shell，不连接 adb。
set -euo pipefail
DIR=${0:A:h}
exec python3 "$DIR/../lib/remote_ops.py" shell "$@"
