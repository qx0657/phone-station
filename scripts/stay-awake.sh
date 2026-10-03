#!/bin/zsh
# adb 与 MCP 共用手机应用持久化的原设置，说明见 README.md。
set -euo pipefail
DIR=${0:A:h}
source "$DIR/../lib/common.sh"

mode="${1:-on}"
if [[ "$mode" == "-h" || "$mode" == "--help" ]]; then
  print -r -- "用法: stay-awake.sh [on|off]"
  print -r -- "on   保留原设置后开启保持亮屏（需要 Android 60 或更新版及写系统设置权限）"
  print -r -- "off  恢复开启前的设置，保留中途手动改过的值"
  exit 0
fi
case "$mode" in
  on) enabled=true ;;
  off|restore) enabled=false ;;
  *) print -u2 -- "用法: stay-awake.sh [on|off]"; exit 1 ;;
esac
if (( $# > 1 )); then
  print -u2 -- "用法: stay-awake.sh [on|off]"
  exit 1
fi

"$DIR/connect.sh"
ADB=$(adb_bin)
SERIAL=$(online_serial "$ADB")
if ! result=$("$ADB" -s "$SERIAL" shell am broadcast -f 0x00400000 \
    -n dev.phonestation.adbkeep/.StayAwakeReceiver \
    -a dev.phonestation.adbkeep.STAY_AWAKE --ez on "$enabled" 2>&1); then
  print -u2 -- "亮屏操作应答丢失，请先核实手机状态；未自动重试。"
  exit 1
fi
if ! print -r -- "$result" | /usr/bin/grep -Eq 'Broadcast completed: result=1([,[:space:]]|$)'; then
  print -u2 -- "亮屏操作未确认，请检查 Android 60 或更新版与写系统设置权限。"
  print -u2 -r -- "$result"
  exit 1
fi
print -r -- "设备 $SERIAL"
print -r -- "亮屏设置已确认"
