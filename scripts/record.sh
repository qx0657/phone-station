#!/bin/zsh
# 说明见仓库根目录 README.md
# 一键录屏：开着投屏窗口，同时写到 ~/Movies/scrcpy/。
# Ctrl+C 或关闭窗口会把文件收好。不要直接杀进程。
# 用法: record.sh
#       record.sh ~/Movies/scrcpy/demo.mp4
#       record.sh [scrcpy 参数]
set -euo pipefail
DIR=${0:A:h}
source "$DIR/../lib/common.sh"

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  print -r -- "用法: record.sh [输出文件.mp4] [scrcpy 参数]"
  print -r -- "默认保存到 ~/Movies/scrcpy/日期时间.mp4"
  exit 0
fi

out=""
if [[ "${1:-}" == *.mp4 || "${1:-}" == *.mkv ]]; then
  out="$1"
  shift
fi
if [[ -z "$out" ]]; then
  mkdir -p "$HOME/Movies/scrcpy"
  out="$HOME/Movies/scrcpy/$(date +%Y%m%d-%H%M%S).mp4"
else
  mkdir -p "${out:h}"
fi

"$DIR/connect.sh"
ADB=$(adb_bin)
SCRCPY=$(scrcpy_bin)
SERIAL=$(online_serial "$ADB")
print -r -- "录屏 $SERIAL"
print -r -- "保存到 $out"
print -r -- "结束：关闭窗口，或在这个终端按 Ctrl+C。"
export ADB
exec "$SCRCPY" --serial="$SERIAL" --stay-awake --record="$out" "$@"
