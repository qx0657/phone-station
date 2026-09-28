#!/bin/zsh
# 说明见仓库根目录 README.md
# 看当前 adb 设备（USB 和无线），以及局域网里广播的无线调试地址。
set -euo pipefail
DIR=${0:A:h}
source "$DIR/../lib/common.sh"
ADB=$(adb_bin)
print -r -- "当前设备"
"$ADB" devices -l
print
print -r -- "局域网无线调试"
typeset -a lines
if ! lines=("${(@f)$(python3 "$DIR/../lib/adb_mdns.py" list connect 4)}") ; then
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
