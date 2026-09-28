#!/bin/zsh
# 说明见仓库根目录 README.md
# 拉长息屏时间，并在充电时保持亮屏。
#   adb shell settings put system screen_off_timeout 2147483647
#   adb shell settings put global stay_on_while_plugged_in 7
# 用法: stay-awake.sh        # 开启
#       stay-awake.sh off    # 恢复为 60 秒息屏，充电也按系统超时
set -euo pipefail
DIR=${0:A:h}
source "$DIR/../lib/common.sh"

mode="${1:-on}"
if [[ "$mode" == "-h" || "$mode" == "--help" ]]; then
  print -r -- "用法: stay-awake.sh [on|off]"
  print -r -- "on   息屏时间设为 2147483647 毫秒，充电（交流电、USB、无线充）时不熄屏"
  print -r -- "off  息屏恢复为 60 秒，并关掉充电时常亮"
  exit 0
fi

"$DIR/connect.sh"
ADB=$(adb_bin)
SERIAL=$(online_serial "$ADB")

put() {
  "$ADB" -s "$SERIAL" shell settings put "$1" "$2" "$3"
}

get() {
  "$ADB" -s "$SERIAL" shell settings get "$1" "$2" | tr -d '\r'
}

case "$mode" in
  on)
    put system screen_off_timeout 2147483647
    put global stay_on_while_plugged_in 7
    ;;
  off|restore)
    put system screen_off_timeout 60000
    put global stay_on_while_plugged_in 0
    ;;
  *)
    print -u2 -- "用法: stay-awake.sh [on|off]"
    exit 1
    ;;
esac

timeout=$(get system screen_off_timeout)
plugged=$(get global stay_on_while_plugged_in)
print -r -- "设备 $SERIAL"
print -r -- "screen_off_timeout=$timeout"
print -r -- "stay_on_while_plugged_in=$plugged"
