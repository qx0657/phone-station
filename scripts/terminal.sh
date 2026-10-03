#!/bin/zsh
# 通过已有中继进入手机 PTY；不连接 adb，不打印凭据。
set -euo pipefail
DIR=${0:A:h}
source "$DIR/../lib/common.sh"
exec python3 "$DIR/../lib/remote_terminal.py" "$@"
