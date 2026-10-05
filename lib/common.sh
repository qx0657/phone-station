# 被 scripts/ 里的脚本 source。不要直接执行。
# online_serial 接受任意 state=device 的序列号，USB 和无线都可以。
# 无线调试页面上的 172.19.0.1 经常是 VPN 地址，
# 本机 Clash Verge 的 utun 会把这个网段吃掉，adb pair / adb connect 会 protocol fault。
# 可用的无线地址是 mDNS 解析到、并且路由不走 utun 的局域网地址。
PHONE_STATION_LIB_DIR=${${(%):-%x}:A:h}

adb_bin() {
  local found sdk
  found=$(command -v adb 2>/dev/null || true)
  if [[ -n "$found" && -x "$found" ]]; then
    print -r -- "$found"
    return 0
  fi
  sdk="$HOME/Library/Android/sdk/platform-tools/adb"
  if [[ -x "$sdk" ]]; then
    print -r -- "$sdk"
    return 0
  fi
  print -u2 -- "找不到 adb。安装 platform-tools，或把 ~/Library/Android/sdk/platform-tools 加到 PATH。"
  return 1
}

scrcpy_bin() {
  local found
  found=$(command -v scrcpy 2>/dev/null || true)
  if [[ -n "$found" && -x "$found" ]]; then
    print -r -- "$found"
    return 0
  fi
  if [[ -x /opt/homebrew/bin/scrcpy ]]; then
    print -r -- /opt/homebrew/bin/scrcpy
    return 0
  fi
  print -u2 -- "找不到 scrcpy。先运行: brew install scrcpy"
  return 1
}

# 只接受唯一一台物理设备。同一手机自动重现的 mDNS 别名按 ro.serialno 归并。
online_serial() {
  python3 "$PHONE_STATION_LIB_DIR/adb_mdns.py" --adb "$1" serial
}

# Android 66 projects phone-owned feature choices for the existing adb scripts.
# Older phones have no projection and keep their established behavior.
require_station_feature() {
  local station_flags station_master station_child
  station_flags=$("$1" -s "$2" shell "settings get global phonestation_enabled; settings get global phonestation_feature_${3//./_}" 2>/dev/null) || {
    print -u2 -- "无法确认手机功能开关，请检查连接后重试。"; return 1
  }
  station_flags=${station_flags//$'\r'/}
  station_master=${station_flags%%$'\n'*}
  station_child=${station_flags##*$'\n'}
  if [[ "$station_master" == 0 || "$station_child" == 0 ]]; then
    print -u2 -- "手机工位总开关或对应功能已关闭，请在手机首页「功能开关」中开启。"
    return 1
  fi
}

# Keep recording finalization intact when a phone-owned switch is revoked.
run_station_screen() {
  local station_pid station_code=0 station_stop=0
  # Noninteractive shells ignore INT in background jobs. Reset it before exec
  # so scrcpy can install its normal graceful-stop handler.
  python3 -c 'import os, signal, sys
for event in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP): signal.signal(event, signal.SIG_DFL)
os.execvp(sys.argv[1], sys.argv[1:])' "$@" &
  station_pid=$!
  trap 'station_stop=1; kill -INT "$station_pid" 2>/dev/null || true' INT TERM HUP
  while kill -0 "$station_pid" 2>/dev/null; do
    if [[ "$station_stop" == 1 ]] || ! require_station_feature "$ADB" "$SERIAL" screen; then
      kill -INT "$station_pid" 2>/dev/null || true
      break
    fi
    sleep 2
  done
  # A hung encoder must not keep a revoked screen session alive indefinitely.
  if kill -0 "$station_pid" 2>/dev/null; then
    local station_attempt
    for station_attempt in {1..25}; do
      kill -0 "$station_pid" 2>/dev/null || break
      sleep 0.2
    done
    if kill -0 "$station_pid" 2>/dev/null; then
      print -u2 -- "录屏进程未响应正常停止，正在结束；请检查录像文件是否完整。"
      kill -TERM "$station_pid" 2>/dev/null || true
      for station_attempt in {1..10}; do
        kill -0 "$station_pid" 2>/dev/null || break
        sleep 0.2
      done
      if kill -0 "$station_pid" 2>/dev/null; then kill -KILL "$station_pid" 2>/dev/null || true; fi
    fi
  fi
  wait "$station_pid" || station_code=$?
  trap - INT TERM HUP
  return "$station_code"
}
