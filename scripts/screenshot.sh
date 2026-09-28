#!/bin/zsh
# 说明见仓库根目录 README.md
# 一键截取手机当前画面，保存为 PNG 并用预览打开。
# 用法: screenshot.sh
#       screenshot.sh ~/Pictures/scrcpy/demo.png
set -euo pipefail
DIR=${0:A:h}
source "$DIR/../lib/common.sh"

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  print -r -- "用法: screenshot.sh [输出文件.png]"
  print -r -- "默认保存到 ~/Pictures/scrcpy/日期时间.png"
  exit 0
fi

out=""
if [[ "${1:-}" == *.png ]]; then
  out="$1"
fi
if [[ -z "$out" ]]; then
  mkdir -p "$HOME/Pictures/scrcpy"
  out="$HOME/Pictures/scrcpy/$(date +%Y%m%d-%H%M%S).png"
else
  mkdir -p "${out:h}"
fi

"$DIR/connect.sh"
ADB=$(adb_bin)
SERIAL=$(online_serial "$ADB")
print -r -- "截屏 $SERIAL"
"$ADB" -s "$SERIAL" exec-out screencap -p > "$out"
if ! file "$out" | grep -q 'PNG image data'; then
  rm -f "$out"
  print -u2 -- "截屏失败，没有得到 PNG。"
  exit 1
fi
print -r -- "保存到 $out"
open "$out"
