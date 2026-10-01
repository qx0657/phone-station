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
