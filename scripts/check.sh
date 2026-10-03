#!/bin/zsh
# 只在电脑上验证；不启动网关、不连接手机、不使用发布签名。
set -euo pipefail
DIR=${0:A:h}
source "$DIR/../lib/common.sh"
exec python3 "$DIR/../lib/check.py" "$@"
