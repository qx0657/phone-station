#!/bin/zsh
# 说明见仓库根目录 README.md
# 无线重连。USB 或无线已经有一条在线设备时，只整理重复连接。
# 发现期间 adb 已经连上时，不再对同一地址执行 adb connect。
# 第二条 TLS 会挤掉第一条，手机就会反复弹出「无线调试已连接」。
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
  print -r -- "发现期间已经连上就不再 adb connect，避免第二条 TLS 挤掉现有无线会话。"
  print -r -- "不要填手机页面上的 172.19.0.1。"
  exit 0
fi

finish_online() {
  exec python3 "$PY" --adb "$ADB" collapse
}

if [[ -z "${1:-}" ]] && python3 "$PY" --adb "$ADB" has-device; then
  finish_online
fi

typeset -a targets records
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
    instance=${rest%%$'\t'*}
    rest=${rest#*$'\t'}
    ip=${rest%%$'\t'*}
    port=${rest##*$'\t'}
    if [[ "$kind" == "skip" ]]; then
      print -u2 -- "跳过 $ip（路由走 ${port}，连过去会 protocol fault）"
      continue
    fi
    [[ "$kind" == "use" ]] || continue
    records+=("${instance}"$'\t'"${ip}"$'\t'"${port}")
    targets+=("$ip:$port")
  done
  # mDNS 浏览的这几秒里，已有 adb 服务可能已经自动连上。再 connect 会挤掉那条会话。
  if python3 "$PY" --adb "$ADB" has-device; then
    finish_online
  fi
  if (( ${#records} > 1 )); then
    set +e
    picked_text=$(printf '%s\n' "${records[@]}" | python3 "$PY" pick-connect)
    picked_status=$?
    set -e
    if [[ "$picked_status" == 0 && -n "$picked_text" ]]; then
      targets=("${(@f)picked_text}")
    fi
  fi
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
