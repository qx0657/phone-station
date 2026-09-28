# 被 scripts/ 里的脚本 source。不要直接执行。
# online_serial 接受任意 state=device 的序列号，USB 和无线都可以。
# 无线调试页面上的 172.19.0.1 经常是 VPN 地址，
# 本机 Clash Verge 的 utun 会把这个网段吃掉，adb pair / adb connect 会 protocol fault。
# 可用的无线地址是 mDNS 解析到、并且路由不走 utun 的局域网地址。

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

# 只接受恰好一台 state=device 的设备，避免投屏接到重复连接上。
online_serial() {
  local adb="$1" line count=0 serial=""
  while IFS= read -r line; do
    [[ -n "$line" ]] || continue
    count=$((count + 1))
    serial="$line"
  done < <("$adb" devices | awk 'NR>1 && $2=="device" {print $1}')
  if (( count == 0 )); then
    print -u2 -- "没有在线设备。USB 接上并允许调试，或打开无线调试后运行 scripts/connect.sh。"
    return 1
  fi
  if (( count > 1 )); then
    print -u2 -- "有多台在线设备。只留一台：多余的无线连接用 scripts/disconnect.sh 断开，多余的 USB 拔掉。"
    return 1
  fi
  print -r -- "$serial"
}
