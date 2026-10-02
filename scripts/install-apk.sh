#!/bin/zsh
# 远程上传、校验并安装单个 APK 或一组 split APK，不连接 adb。
set -euo pipefail
DIR=${0:A:h}
exec python3 "$DIR/../lib/remote_ops.py" install "$@"
