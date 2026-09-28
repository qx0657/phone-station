#!/bin/zsh
# 说明见仓库根目录 README.md
# 控制手机震动。PGT-AN20（Android 15）上走 vibrator_manager，这台没有 cmd vibrator。
# 用法: vibrate.sh                     # 震 400 毫秒
#       vibrate.sh 1500                # 震指定毫秒，最长 10 秒
#       vibrate.sh 1500 255            # 第二个数是力度 1-255
#       vibrate.sh 400 200 1000        # 震 400 毫秒，停 1000 毫秒，一直重复
#       vibrate.sh 400 200 1000 5      # 同样的节奏，只重复 5 次
#       vibrate.sh stop                # 停掉正在进行的震动
set -euo pipefail
DIR=${0:A:h}
source "$DIR/../lib/common.sh"

usage() {
  print -r -- "用法: vibrate.sh [毫秒] [力度1-255] [间隔毫秒] [次数]"
  print -r -- "      vibrate.sh stop"
  print -r -- "不带参数时震 400 毫秒。写出间隔后会按「震、停、震」重复；不写次数就一直重复，用 stop 停下。免打扰也会震。"
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

"$DIR/connect.sh"
ADB=$(adb_bin)
SERIAL=$(online_serial "$ADB")

if [[ "${1:-}" == "stop" || "${1:-}" == "off" || "${1:-}" == "cancel" ]]; then
  "$ADB" -s "$SERIAL" shell cmd vibrator_manager cancel
  print -r -- "已停止震动"
  exit 0
fi

ms="${1:-400}"
amp="${2:-200}"
gap="${3:-}"
times="${4:-}"
if [[ ! "$ms" =~ '^[0-9]+$' || "$ms" -lt 1 || "$ms" -gt 10000 ]]; then
  print -u2 -- "时长用 1 到 10000 毫秒。"
  usage >&2
  exit 1
fi
if [[ ! "$amp" =~ '^[0-9]+$' || "$amp" -lt 1 || "$amp" -gt 255 ]]; then
  print -u2 -- "力度用 1 到 255。"
  usage >&2
  exit 1
fi
if [[ -z "$gap" ]]; then
  "$ADB" -s "$SERIAL" shell cmd vibrator_manager synced -f -B oneshot -a "$ms" "$amp"
  print -r -- "震动 ${ms} 毫秒，力度 ${amp}"
  exit 0
fi
if [[ ! "$gap" =~ '^[0-9]+$' || "$gap" -lt 1 || "$gap" -gt 60000 ]]; then
  print -u2 -- "间隔用 1 到 60000 毫秒。"
  usage >&2
  exit 1
fi

if [[ -z "$times" || "$times" == "0" ]]; then
  "$ADB" -s "$SERIAL" shell cmd vibrator_manager synced -f -B waveform -r 0 -a "$ms" "$amp" "$gap" 0
  print -r -- "震动 ${ms} 毫秒，力度 ${amp}，间隔 ${gap} 毫秒，一直重复。停下: vibrate.sh stop"
  exit 0
fi
if [[ ! "$times" =~ '^[0-9]+$' || "$times" -lt 1 || "$times" -gt 50 ]]; then
  print -u2 -- "次数用 1 到 50。不写次数则一直重复。"
  usage >&2
  exit 1
fi

typeset -a steps
steps=(waveform -a)
for ((i = 1; i <= times; i++)); do
  steps+=("$ms" "$amp")
  if ((i < times)); then
    steps+=("$gap" 0)
  fi
done
"$ADB" -s "$SERIAL" shell cmd vibrator_manager synced -f -B "${steps[@]}"
print -r -- "震动 ${ms} 毫秒，力度 ${amp}，间隔 ${gap} 毫秒，共 ${times} 次"
