#!/bin/zsh
# 说明见仓库根目录 README.md，实现见 docs/torch.md
# 控制手机闪光灯。开着的时候手机会留一个进程，退出后系统会把灯关掉。
# 用法: torch.sh                 # 打开，亮度最高
#       torch.sh off             # 关闭
#       torch.sh toggle
#       torch.sh status
#       torch.sh 1               # 指定亮度档。PGT-AN20 最高 4，其它机型用手机自己的上限
#       torch.sh beat            # 跟着电脑正在播放的声音闪
#       torch.sh beat 1.5        # 增益越大越容易亮
set -euo pipefail
DIR=${0:A:h}
source "$DIR/../lib/common.sh"

usage() {
  print -r -- "用法: torch.sh [on|off|toggle|status|beat|亮度]"
  print -r -- "      torch.sh beat [增益]"
  print -r -- "on       打开，亮度最高。不带参数也是打开"
  print -r -- "off      关闭"
  print -r -- "toggle   开着就关，关着就开"
  print -r -- "status   查看当前开关"
  print -r -- "正整数   指定亮度档。超过手机上限时收到最高档。PGT-AN20 最高 4"
  print -r -- "beat     跟着电脑正在播放的声音闪，Ctrl+C 停下并关灯"
  print -r -- "         增益默认 2，范围 0.2 到 8，越大越容易亮"
}

cmd="${1:-on}"
if [[ "$cmd" == "-h" || "$cmd" == "--help" ]]; then
  usage
  exit 0
fi

"$DIR/connect.sh"
ADB=$(adb_bin)
SERIAL=$(online_serial "$ADB")

if [[ ! -f "$DIR/../lib/torch.dex" ]]; then
  print -u2 -- "缺少 $DIR/../lib/torch.dex"
  print -u2 -- "先运行 lib/torch/build.sh"
  exit 1
fi
"$ADB" -s "$SERIAL" push "$DIR/../lib/torch.dex" /data/local/tmp/torch.dex >/dev/null 2>&1

stop_holder() {
  # ps 一行就能找到 app_process Torch，比扫全部 /proc 快。
  "$ADB" -s "$SERIAL" shell 'ps -A -o PID,ARGS | while read pid args; do case "$args" in "app_process /data/local/tmp Torch"*) kill "$pid" 2>/dev/null || true ;; esac; done; rm -f /data/local/tmp/torch.pid; exit 0' || true
  sleep 0.2
}

run_torch() {
  "$ADB" -s "$SERIAL" shell CLASSPATH=/data/local/tmp/torch.dex app_process /data/local/tmp Torch "$@"
}

read_status() {
  local blob line
  blob=$(run_torch status 2>/dev/null | tr -d '\r' || true)
  line=$(print -r -- "$blob" | awk '/^(on|off|err)( |$)/ { print; exit }')
  if [[ -z "$line" ]]; then
    print -u2 -- "没有读到闪光灯状态。"
    if [[ -n "$blob" ]]; then
      print -u2 -- "$blob"
    fi
    return 1
  fi
  print -r -- "$line"
}

wait_log() {
  local line
  line=$("$ADB" -s "$SERIAL" shell 'i=0; while [ "$i" -lt 60 ]; do if grep -q "^holding " /data/local/tmp/torch.log 2>/dev/null || grep -q "^err " /data/local/tmp/torch.log 2>/dev/null || grep -qx off /data/local/tmp/torch.log 2>/dev/null; then grep -E "^(holding |err |off$)" /data/local/tmp/torch.log | head -n 1; exit 0; fi; i=$((i+1)); sleep 0.1; done; echo timeout; exit 1' | tr -d '\r' || true)
  if [[ -z "$line" || "$line" == timeout ]]; then
    print -u2 -- "闪光灯没有响应。"
    "$ADB" -s "$SERIAL" shell cat /data/local/tmp/torch.log >&2 || true
    return 1
  fi
  print -r -- "$line"
}

start_holder() {
  local args="$1"
  stop_holder
  "$ADB" -s "$SERIAL" shell rm -f /data/local/tmp/torch.log /data/local/tmp/torch.pid
  "$ADB" -s "$SERIAL" shell "setsid sh -c 'CLASSPATH=/data/local/tmp/torch.dex exec app_process /data/local/tmp Torch $args' </dev/null >/data/local/tmp/torch.log 2>&1 &"
  local line level max
  line=$(wait_log) || exit 1
  case "$line" in
    err\ *)
      print -u2 -- "${line#err }"
      exit 1
      ;;
    holding\ *)
      level=${line#holding }
      max=${level##* }
      level=${level%% *}
      if [[ "$level" == "$max" ]]; then
        print -r -- "闪光灯已打开"
      else
        print -r -- "闪光灯亮度 ${level}，最高 ${max}"
      fi
      ;;
    *)
      print -u2 -- "$line"
      exit 1
      ;;
  esac
}

turn_off() {
  stop_holder
  local line
  line=$(run_torch off 2>/dev/null | tr -d '\r' || true)
  line=$(print -r -- "$line" | awk '/^(off|err)( |$)/ { print; exit }')
  case "$line" in
    err\ *)
      print -u2 -- "${line#err }"
      exit 1
      ;;
  esac
  print -r -- "闪光灯已关闭"
}

case "$cmd" in
  on)
    start_holder on
    ;;
  off)
    turn_off
    ;;
  toggle)
    line=$(read_status) || exit 1
    case "$line" in
      on*) turn_off ;;
      off) start_holder on ;;
      err\ *)
        print -u2 -- "${line#err }"
        exit 1
        ;;
      *)
        print -u2 -- "$line"
        exit 1
        ;;
    esac
    ;;
  status)
    line=$(read_status) || exit 1
    case "$line" in
      on\ *) print -r -- "闪光灯开着，亮度 ${line#on }" ;;
      on) print -r -- "闪光灯开着" ;;
      off) print -r -- "闪光灯关着" ;;
      err\ *)
        print -u2 -- "${line#err }"
        exit 1
        ;;
      *)
        print -u2 -- "$line"
        exit 1
        ;;
    esac
    ;;
  beat)
    gain="${2:-2}"
    if [[ ! "$gain" =~ '^[0-9]+([.][0-9]+)?$' ]] || ! python3 -c 'import sys; v=float(sys.argv[1]); raise SystemExit(0 if 0.2 <= v <= 8 else 1)' "$gain"; then
      print -u2 -- "增益用 0.2 到 8，例如 1.5"
      usage >&2
      exit 1
    fi
    src="$DIR/../lib/torch-audio/main.swift"
    bin="$DIR/../lib/torch-audio/torch-audio"
    if [[ ! -x "$bin" || "$src" -nt "$bin" ]]; then
      swiftc=$(command -v swiftc || true)
      if [[ -z "$swiftc" && -x /usr/bin/swiftc ]]; then
        swiftc=/usr/bin/swiftc
      fi
      if [[ -z "$swiftc" ]]; then
        print -u2 -- "找不到 swiftc，无法编译系统声音采集。"
        exit 1
      fi
      print -r -- "编译系统声音采集…"
      "$swiftc" -O -framework CoreAudio -framework AudioToolbox -o "$bin" "$src"
    fi
    stop_holder
    print -r -- "跟着电脑正在播放的声音闪。按 Ctrl+C 停下。"
    exec python3 -u "$DIR/../lib/torch_beat.py" \
      --adb "$ADB" --serial "$SERIAL" --audio "$bin" --gain "$gain"
    ;;
  <->)
    if (( cmd < 1 || cmd > 255 )); then
      print -u2 -- "亮度用 1 到 255 的整数。超过手机上限时会收到最高档。"
      usage >&2
      exit 1
    fi
    start_holder "level $cmd"
    ;;
  *)
    print -u2 -- "无法识别的参数: $cmd"
    usage >&2
    exit 1
    ;;
esac
