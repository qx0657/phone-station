#!/bin/zsh
# 说明见仓库根目录 README.md
# 无线重连。USB 或无线已经有一条在线设备时，只整理重复连接。
# 用法：connect.sh
#       connect.sh 192.168.0.103:42125
set -euo pipefail
DIR=${0:A:h}
source "$DIR/../lib/common.sh"
ADB=$(adb_bin)
PY="$DIR/../lib/adb_mdns.py"

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  print -r -- "用法: connect.sh [IP:端口]"
  print -r -- "不带参数时，已有在线设备（USB 或无线）就只整理重复连接；否则用 mDNS 找局域网地址。"
  print -r -- "不要填手机页面上的 172.19.0.1。"
  exit 0
fi

if [[ -z "${1:-}" ]] && python3 "$PY" --adb "$ADB" has-device; then
  exec python3 "$PY" --adb "$ADB" collapse
fi

typeset -a targets
if [[ -n "${1:-}" ]]; then
  targets=("$1")
else
  typeset -a lines
  if ! lines=("${(@f)$(python3 "$PY" list connect 8)}") ; then
    exit 1
  fi
  for line in "${lines[@]}"; do
    [[ -n "$line" ]] || continue
    kind=${line%%$'\t'*}
    rest=${line#*$'\t'}
    rest=${rest#*$'\t'}
    ip=${rest%%$'\t'*}
    port=${rest##*$'\t'}
    if [[ "$kind" == "skip" ]]; then
      print -u2 -- "跳过 $ip（路由走 ${port}，连过去会 protocol fault）"
      continue
    fi
    [[ "$kind" == "use" ]] || continue
    targets+=("$ip:$port")
  done
fi

if (( ${#targets} == 0 )); then
  print -u2 -- "没有可连接的无线地址。USB 接上并允许调试即可使用；要走无线就打开无线调试。配对过期时运行 scripts/pair-qr.sh，或打开手机配对码页面后运行 scripts/pair-code.sh。"
  exit 1
fi

connected=0
for target in "${targets[@]}"; do
  host=${target%%:*}
  iface=$(route -n get "$host" 2>/dev/null | awk '/interface:/{print $2}' || true)
  if [[ "$iface" == utun* ]]; then
    print -u2 -- "不连接 $target，它走 $iface。这就是 pair 172.19.0.1 报 protocol fault 的原因。"
    continue
  fi
  result=$("$ADB" connect "$target" 2>&1 || true)
  print -r -- "$result"
  if [[ "$result" == *connected* ]]; then
    connected=1
  fi
done

sleep 0.8
if python3 "$PY" --adb "$ADB" collapse; then
  if [[ -n "${1:-}" && "$connected" == "0" ]]; then
    exit 1
  fi
  exit 0
fi
if [[ "$connected" == "0" ]]; then
  print -u2 -- "没连上。若是还没配对：scripts/pair-qr.sh，或手机打开「使用配对码配对」后 scripts/pair-code.sh <6位码>。"
fi
exit 1
