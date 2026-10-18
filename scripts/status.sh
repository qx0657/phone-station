#!/bin/zsh
# 说明见仓库根目录 README.md
# 看当前 adb 设备（USB 和无线），以及局域网里广播的无线调试地址。
set -euo pipefail
DIR=${0:A:h}
source "$DIR/../lib/common.sh"
ADB=$(adb_bin)
print -r -- "当前设备"
station=""
for candidate in "$DIR/../build/.phone-station/station" "$DIR/../lib/station"; do
  if [[ -x $candidate ]]; then
    station=$candidate
    break
  fi
done
if [[ -n $station ]] && "$station" devices; then
  :
else
  "$ADB" devices -l
fi
print
print -r -- "局域网无线调试"
typeset -a lines
wireless_status=0
if [[ -n $station ]]; then
  lines=("${(@f)$("$station" wireless)}") || wireless_status=$?
else
  wireless_status=3
fi
if (( wireless_status == 3 )); then
  lines=("${(@f)$(python3 "$DIR/../lib/adb_mdns.py" list connect 4)}") || exit 0
elif (( wireless_status != 0 )); then
  exit 0
fi
for line in "${lines[@]}"; do
  [[ -n "$line" ]] || continue
  kind=${line%%$'\t'*}
  rest=${line#*$'\t'}
  name=${rest%%$'\t'*}
  rest=${rest#*$'\t'}
  ip=${rest%%$'\t'*}
  port=${rest##*$'\t'}
  if [[ "$kind" == "use" ]]; then
    print -r -- "$name  $ip:$port"
  elif [[ "$kind" == "skip" ]]; then
    print -u2 -- "跳过 $ip（路由走 $port）"
  fi
done
