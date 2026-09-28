#!/bin/zsh
# 说明见仓库根目录 README.md
# 用手机上的 6 位配对码配对。脚本自己找配对端口和局域网 IP。
# 先在手机上打开：无线调试 → 使用配对码配对，停在那个页面。
# 页面上的 IP 即使是 172.19.0.1 也不用管。
# 用法：pair-code.sh 424380
set -euo pipefail
DIR=${0:A:h}
source "$DIR/../lib/common.sh"
ADB=$(adb_bin)

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  print -r -- "用法: pair-code.sh <6位配对码>"
  print -r -- "手机先打开「使用配对码配对」，再运行。不要使用页面上的 172.19 地址。"
  exit 0
fi

code="${1:-}"
if [[ -z "$code" ]]; then
  printf "输入手机上的 6 位配对码: "
  read -r code
fi
code="${code//[[:space:]]/}"
if [[ ! "$code" =~ '^[0-9]{6}$' ]]; then
  print -u2 -- "配对码是 6 位数字。"
  exit 1
fi

print -r -- "正在等手机的配对服务…"
if ! discovered=$(python3 "$DIR/../lib/adb_mdns.py" list pairing 20); then
  print -u2 -- "没看到配对服务。打开手机「使用配对码配对」，停在那个页面再试。"
  exit 1
fi

target=""
while IFS=$'\t' read -r kind _instance ip port; do
  [[ -n "${kind:-}" ]] || continue
  if [[ "$kind" == "skip" ]]; then
    print -u2 -- "跳过 $ip（路由走 $port）"
    continue
  fi
  [[ "$kind" == "use" ]] || continue
  target="$ip:$port"
  break
done <<<"$discovered"

if [[ -z "$target" ]]; then
  print -u2 -- "配对服务不在可用的局域网地址上。"
  exit 1
fi

print -r -- "配对 $target"
result=$("$ADB" pair "$target" "$code" 2>&1 || true)
print -r -- "$result"
if [[ "$result" != *"Successfully paired"* ]]; then
  print -u2 -- "配对失败。配对码和端口每次重新打开页面都会变，回到手机上重新取一次。"
  exit 1
fi

exec "$DIR/connect.sh"
