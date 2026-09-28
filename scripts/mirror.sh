#!/bin/zsh
# 说明见仓库根目录 README.md
# 一键投屏。掉线时先走 connect.sh，再用同一条 adb 打开 scrcpy。
# 用法: mirror.sh [额外的 scrcpy 参数]
set -euo pipefail
DIR=${0:A:h}
source "$DIR/../lib/common.sh"

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  print -r -- "用法: mirror.sh [scrcpy 参数]"
  print -r -- "关闭窗口即停止投屏。"
  exit 0
fi

"$DIR/connect.sh"
ADB=$(adb_bin)
SCRCPY=$(scrcpy_bin)
SERIAL=$(online_serial "$ADB")
print -r -- "投屏 $SERIAL"
export ADB
exec "$SCRCPY" --serial="$SERIAL" --stay-awake "$@"
