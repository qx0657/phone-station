#!/bin/zsh
# 说明见仓库根目录 README.md，做法见 docs/adb-keep.md
# 电脑连着时，把手机上的连接配置写成手机当前时间。
set -euo pipefail
DIR=${0:A:h}
source "$DIR/../lib/common.sh"

KEY=phonestation_host_ms
TRANSPORT_KEY=phonestation_host_transport
PIDFILE=/tmp/phonestation-host-state.pid
INTERVAL=2

if [[ ${1:-} == -h || ${1:-} == --help ]]; then
  print -r -- "用法: host-state.sh mark"
  print -r -- "      host-state.sh watch"
  print -r -- "      host-state.sh clear"
  print -r -- "mark   有且只有一台在线设备时，写入手机当前时间与 USB / 无线连接类型。"
  print -r -- "watch  每 ${INTERVAL} 秒写一次，直到没有在线设备。已经有一个在写就直接退出。"
  print -r -- "clear  写成 0。手机看到 0 或超过 6 秒没更新，就不再算 adb 在线；远程连接单独判断。"
  exit 0
fi
if [[ ${1:-mark} != mark && ${1:-} != watch && ${1:-} != clear ]]; then
  print -u2 -- "用法: host-state.sh [mark|watch|clear]"
  exit 1
fi

# 0 正好一台，1 没有，2 多台。多台时不写，调用方决定要不要继续等。
one_serial() {
  local adb="$1" line count=0 serial=""
  while IFS= read -r line; do
    [[ -n "$line" ]] || continue
    count=$((count + 1))
    serial="$line"
  done < <("$adb" devices | awk 'NR>1 && $2=="device" {print $1}')
  if (( count == 1 )); then
    print -r -- "$serial"
    return 0
  fi
  if (( count == 0 )); then
    return 1
  fi
  return 2
}

write_mark() {
  local adb="$1" serial="$2" transport=usb
  if [[ "$serial" == *:* || "$serial" == *._adb-tls-connect._tcp* ]]; then
    transport=wireless
  fi
  "$adb" -s "$serial" shell "settings put global $TRANSPORT_KEY $transport; settings put global $KEY \"\$(date +%s)000\"" >/dev/null
}

write_clear() {
  local adb="$1" serial="$2"
  "$adb" -s "$serial" shell settings put global "$KEY" 0 >/dev/null
}

watch_running() {
  [[ -f "$PIDFILE" ]] || return 1
  local pid
  pid=$(<"$PIDFILE")
  [[ "$pid" == <-> ]] || return 1
  kill -0 "$pid" 2>/dev/null
}

ADB=$(adb_bin)

if [[ ${1:-mark} == watch ]]; then
  if watch_running; then
    exit 0
  fi
  print -r -- "$$" >"$PIDFILE"
  trap 'rm -f "$PIDFILE"' EXIT
  print -r -- "开始写入连接配置"
  while true; do
    set +e
    serial=$(one_serial "$ADB")
    got=$?
    set -e
    if (( got == 1 )); then
      print -r -- "没有在线设备，停止写入"
      exit 0
    fi
    if (( got == 0 )); then
      write_mark "$ADB" "$serial" || true
    fi
    sleep "$INTERVAL"
  done
fi

set +e
serial=$(one_serial "$ADB")
got=$?
set -e
if (( got != 0 )); then
  if [[ ${1:-mark} == clear ]]; then
    exit 0
  fi
  print -u2 -- "没有唯一的在线设备，没有写入。"
  exit 1
fi
if [[ ${1:-mark} == clear ]]; then
  write_clear "$ADB" "$serial"
else
  write_mark "$ADB" "$serial"
fi
