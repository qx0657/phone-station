#!/bin/zsh
# 把已退出的手机工位旧包归档，避免应用目录积累可启动副本。
set -euo pipefail
DIR=${0:A:h}
source "$DIR/../lib/common.sh"
exec python3 "$DIR/../lib/archive_mac_app.py" "$@"
