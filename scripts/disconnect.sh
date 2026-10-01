#!/bin/zsh
# 说明见仓库根目录 README.md
# 断开无线 adb。不带参数时断开全部无线连接，USB 不受影响。
# 用法：disconnect.sh
#       disconnect.sh 192.168.0.103:42125
set -euo pipefail
DIR=${0:A:h}
source "$DIR/../lib/common.sh"
ADB=$(adb_bin)

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  print -r -- "用法: disconnect.sh [序列号或 IP:端口]"
  print -r -- "只断开无线连接，USB 保持不动。"
  print -r -- "断开后若没有设备留下，会先把手机上的连接配置写成 0。"
  exit 0
fi

# 无线断开后电脑就写不了配置。还留着 USB 时不用清，写入会继续。
# 这是唯一一条时，先停掉写入，再把值写成 0，界面不用等它过期。
clear_host=0
if [[ -z ${1:-} ]]; then
  if ! "$ADB" devices -l | awk 'NR>1 && $2=="device" && /usb:/ { found=1 } END { exit found ? 0 : 1 }'; then
    clear_host=1
  fi
else
  online=$("$ADB" devices | awk 'NR>1 && $2=="device" { c++ } END { print c+0 }')
  if [[ "$online" == 1 ]]; then
    clear_host=1
  fi
fi
if (( clear_host )); then
  if [[ -f /tmp/phonestation-host-state.pid ]]; then
    old=$(</tmp/phonestation-host-state.pid)
    if [[ "$old" == <-> ]]; then
      kill "$old" 2>/dev/null || true
    fi
  fi
  "$DIR/host-state.sh" clear || true
fi

if [[ -n "${1:-}" ]]; then
  "$ADB" disconnect "$1"
else
  "$ADB" disconnect
fi
"$ADB" devices -l
