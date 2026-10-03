#!/bin/zsh
# 说明见仓库根目录 README.md，实现见 docs/mcp.md
# 在本机启动统一 MCP 网关，优先使用 adb，也可经用户配置的远程中继访问手机。
set -euo pipefail
DIR=${0:A:h}
source "$DIR/../lib/common.sh"

GATEWAY_PORT=18765
LOCAL_PORT=18766
REMOTE_PORT=8765
PKG=dev.phonestation.adbkeep
GATEWAY_BIN=""

# A wireless transport can remain listed while shell commands stop responding.
bounded_adb() {
  python3 -c 'import subprocess, sys
try:
    result = subprocess.run(sys.argv[1:], timeout=2)
    sys.exit(result.returncode)
except subprocess.TimeoutExpired:
    sys.exit(124)
' "$ADB" "$@"
}

find_gateway() {
  if [[ -x "$DIR/../lib/phone-relay-gateway" ]]; then
    GATEWAY_BIN="$DIR/../lib/phone-relay-gateway"
  elif [[ -x "$DIR/../build/.phone-station/phone-relay-gateway" ]]; then
    GATEWAY_BIN="$DIR/../build/.phone-station/phone-relay-gateway"
  else
    print -u2 -- "找不到本机 MCP 网关。先运行 ./scripts/build-mac-app.sh。"
    return 1
  fi
}

read_phone_token() {
  bounded_adb -s "$SERIAL" shell dumpsys activity service "$PKG/.FileMcpService" \
    | tr -d '\r' \
    | sed -n 's/.*mcp token //p' \
    | tail -n 1 \
    | tr -d '[:space:]'
}

read_one_online_serial() {
  SERIAL=$(online_serial "$ADB" 2>/dev/null)
}

clear_local_route() {
  if [[ -n ${ADB:-} ]]; then
    bounded_adb forward --remove "tcp:${LOCAL_PORT}" >/dev/null 2>&1 || true
  fi
  "$GATEWAY_BIN" clear-local >/dev/null 2>&1 || true
}

refresh_local_route() {
  if ! ADB=$(adb_bin 2>/dev/null) || ! read_one_online_serial; then
    clear_local_route
    return 1
  fi
  local flag
  if ! flag=$(bounded_adb -s "$SERIAL" shell settings get global phonestation_mcp 2>/dev/null | tr -d '\r[:space:]'); then
    clear_local_route
    return 1
  fi
  if [[ $flag != 1 ]]; then
    clear_local_route
    return 1
  fi
  if ! bounded_adb -s "$SERIAL" forward "tcp:${LOCAL_PORT}" "tcp:${REMOTE_PORT}" >/dev/null; then
    clear_local_route
    return 1
  fi
  local token
  if ! token=$(read_phone_token); then
    clear_local_route
    return 1
  fi
  if [[ -z $token ]]; then
    clear_local_route
    return 1
  fi
  print -r -- "$token" | "$GATEWAY_BIN" local-token >/dev/null
}

if [[ ${1:-} == -h || ${1:-} == --help ]]; then
  print -r -- "用法: mcp.sh [status|profile|stop|desktop <https-endpoint> <SPKI-SHA256> [--stdin]|forget-desktop|pair <https-endpoint> <SPKI-SHA256> [--stdin]|unpair]"
  print -r -- "打开本机 MCP 网关。它优先走 adb，必要时经已配对的远程通道。"
  print -r -- "远程通道默认未配置；中继地址可用自己的 HTTPS 域名、IP、端口和路径。"
  print -r -- "pair 需先有 adb；手机令牌和电脑令牌都会隐藏提示输入。"
  print -r -- "--stdin 从标准输入依次读取手机令牌、电脑令牌，各占一行；profile 只读地址与指纹。"
  print -r -- "stop 关闭手机 MCP 与远程通道；unpair 清除两端远程凭据。"
  print -r -- "desktop 只配置此 Mac，隐藏输入电脑令牌；--stdin 读取一行。不需要 adb。"
  print -r -- "forget-desktop 只清除此 Mac 的远程配置，不修改手机。"
  exit 0
fi

find_gateway

if [[ ${1:-} == profile && $# == 1 ]]; then
  "$GATEWAY_BIN" profile
  exit 0
fi

# Used by the menu bar's fast status loop. No adb or credential refresh here.
if [[ ${1:-} == snapshot && $# == 1 ]]; then
  "$GATEWAY_BIN" status-json
  exit 0
fi

if [[ ${1:-} == watch && $# == 1 ]]; then
  exec "$GATEWAY_BIN" watch-status
fi

if [[ ${1:-} == desktop ]]; then
  if (( $# != 3 && $# != 4 )) || [[ $# == 4 && $4 != --stdin ]]; then
    print -u2 -- "用法: mcp.sh desktop <https-endpoint> <SPKI-SHA256> [--stdin]"
    exit 2
  fi
  pin=$3
  if [[ ! $pin =~ '^[[:xdigit:]]{64}$' ]]; then
    print -u2 -- "SPKI SHA-256 必须是 64 位十六进制。"
    exit 2
  fi
  endpoint=$("$GATEWAY_BIN" validate-endpoint "$2") || exit 2
  if [[ ${4:-} == --stdin ]]; then
    IFS= read -r desktop_token || {
      print -u2 -- "标准输入需要一行电脑令牌。"
      exit 2
    }
  else
    read -r -s "desktop_token?电脑端 64 位十六进制令牌："
    print
  fi
  if [[ ! $desktop_token =~ '^[[:xdigit:]]{64}$' ]]; then
    unset desktop_token
    print -u2 -- "电脑令牌需要 64 位十六进制字符。"
    exit 2
  fi
  print -r -- "{\"endpoint\":\"$endpoint\",\"pin\":\"${pin:l}\",\"token\":\"${desktop_token:l}\"}" \
    | "$GATEWAY_BIN" configure
  unset desktop_token
  "$GATEWAY_BIN" start
  print -r -- "此 Mac 的中继配置已保存，正在检测远程连接。手机需另行保存相同地址、指纹和手机令牌。"
  exit 0
fi

if [[ ${1:-} == forget-desktop && $# == 1 ]]; then
  "$GATEWAY_BIN" unpair
  print -r -- "此 Mac 的远程配置已清除，手机配置保留。"
  exit 0
fi

if [[ ${1:-} == pair ]]; then
  if (( $# != 3 && $# != 4 )) || [[ $# == 4 && $4 != --stdin ]]; then
    print -u2 -- "用法: mcp.sh pair <https-endpoint> <SPKI-SHA256> [--stdin]"
    exit 2
  fi
  pin=$3
  if [[ ! $pin =~ '^[[:xdigit:]]{64}$' ]]; then
    print -u2 -- "SPKI SHA-256 必须是 64 位十六进制。"
    exit 2
  fi
  endpoint=$("$GATEWAY_BIN" validate-endpoint "$2") || exit 2
  if [[ ${4:-} == --stdin ]]; then
    IFS= read -r phone_token && IFS= read -r desktop_token || {
      unset phone_token desktop_token
      print -u2 -- "标准输入需要手机令牌、电脑令牌各一行。"
      exit 2
    }
  else
    read -r -s "phone_token?手机端 64 位十六进制令牌："
    print
    read -r -s "desktop_token?电脑端 64 位十六进制令牌："
    print
  fi
  if [[ ! $phone_token =~ '^[[:xdigit:]]{64}$' || ! $desktop_token =~ '^[[:xdigit:]]{64}$' ||
        ${phone_token:l} == ${desktop_token:l} ]]; then
    unset phone_token desktop_token
    print -u2 -- "令牌必须是两枚不同的 64 位十六进制随机值。"
    exit 2
  fi
  if ! ADB=$(adb_bin) || ! "$DIR/connect.sh" </dev/null >/dev/null; then
    print -u2 -- "配对需要 adb 在线。"
    exit 1
  fi
  SERIAL=$(online_serial "$ADB" </dev/null)
  pair_capability=$("$ADB" -s "$SERIAL" shell am broadcast -f 0x00400000 \
    -n "$PKG/.PhoneRelayControlReceiver" -a "$PKG.PHONE_RELAY" --es action capabilities)
  if ! print -r -- "$pair_capability" | rg -q 'Broadcast completed: result=2(,|[[:space:]]|$)'; then
    unset phone_token desktop_token
    print -u2 -- "手机应用尚不支持配置确认，请先安装或更新手机应用，再配对远程通道。"
    exit 1
  fi
  remote_cmd="IFS= read -r token || exit 1; am broadcast -f 0x00400000 -n $PKG/.PhoneRelayControlReceiver -a $PKG.PHONE_RELAY --es action pair --es endpoint '$endpoint' --es pin ${pin:l} --es token \"\$token\""
  pair_reply=$(print -r -- "${phone_token:l}" | "$ADB" -s "$SERIAL" shell "$remote_cmd")
  unset phone_token
  remote_flag=$("$ADB" -s "$SERIAL" shell settings get global phonestation_remote | tr -d '\r[:space:]')
  if [[ $remote_flag != 1 ]] || ! print -r -- "$pair_reply" | rg -q 'Broadcast completed: result=1(,|[[:space:]]|$)'; then
    unset desktop_token
    print -u2 -- "手机没有确认接受配对；请更新手机应用，再检查中继地址、SPKI 指纹和手机令牌。"
    exit 1
  fi
  if ! print -r -- "{\"endpoint\":\"$endpoint\",\"pin\":\"${pin:l}\",\"token\":\"${desktop_token:l}\"}" \
    | "$GATEWAY_BIN" configure; then
    unset desktop_token
    print -u2 -- "电脑凭据保存失败；手机已保存新中继，电脑仍保留原配置。请保持 adb 连接，检查钥匙串权限和配置目录，再用刚填的地址、指纹和两枚令牌重新配对。"
    exit 1
  fi
  unset desktop_token
  "$GATEWAY_BIN" start
  for _ in {1..30}; do
    if "$GATEWAY_BIN" status | rg -q 'relay=online'; then
      print -r -- "已配对，远程中继往返正常。手机设置里的「远程通道」已打开。"
      exit 0
    fi
    sleep 0.5
  done
  print -u2 -- "配对资料已写入，但尚未确认远程中继往返。可稍后运行 ./scripts/mcp.sh status 查看；不要重复提交有副作用的操作。"
  exit 1
fi

if [[ ${1:-} == unpair ]]; then
  if (( $# != 1 )); then
    print -u2 -- "用法: mcp.sh unpair"
    exit 2
  fi
  if ! ADB=$(adb_bin) || ! read_one_online_serial; then
    print -u2 -- "解除配对需要 adb 在线；手机设置里的「远程通道」开关只能暂时关闭连接。"
    exit 1
  fi
  "$ADB" -s "$SERIAL" shell am broadcast -f 0x00400000 \
    -n "$PKG/.PhoneRelayControlReceiver" -a "$PKG.PHONE_RELAY" \
    --es action forget >/dev/null
  remote_flag=$("$ADB" -s "$SERIAL" shell settings get global phonestation_remote | tr -d '\r[:space:]')
  if [[ $remote_flag == 1 ]]; then
    print -u2 -- "手机没有解除配对。"
    exit 1
  fi
  "$GATEWAY_BIN" unpair
  print -r -- "已解除远程通道配对。"
  exit 0
fi

if [[ ${1:-} == status ]]; then
  if (( $# != 1 )); then
    print -u2 -- "用法: mcp.sh status"
    exit 2
  fi
  refresh_local_route || true
  "$GATEWAY_BIN" status
  exit 0
fi

if [[ ${1:-} == stop ]]; then
  if (( $# != 1 )); then
    print -u2 -- "用法: mcp.sh stop"
    exit 2
  fi
  if ! ADB=$(adb_bin) || ! read_one_online_serial; then
    print -u2 -- "停止手机 MCP 与远程通道需要 adb 在线；手机仍可从应用里关闭这两项。"
    exit 1
  fi
  if ! stop_reply=$(bounded_adb -s "$SERIAL" shell am broadcast -f 0x10400000 \
    -n "$PKG/.McpControlReceiver" -a "$PKG.MCP" --ez on false 2>/dev/null); then
    print -u2 -- "停止请求失败或超时；未确认手机已停止，保留本机通道状态。"
    exit 1
  fi
  if ! print -r -- "$stop_reply" | rg -q 'Broadcast completed: result=1(,|[[:space:]]|$)'; then
    print -u2 -- "手机没有确认停止请求；旧版应用请先更新。本机通道状态已保留。"
    exit 1
  fi
  stopped=0
  for _ in {1..10}; do
    if ! flags=$(bounded_adb -s "$SERIAL" shell 'settings get global phonestation_mcp; settings get global phonestation_remote' 2>/dev/null); then
      break
    fi
    if ! services=$(bounded_adb -s "$SERIAL" shell dumpsys activity services "$PKG" 2>/dev/null); then
      break
    fi
    # 明确的两个关闭标记，加上实际服务退出；空输出不能冒充成功。
    if [[ $(print -r -- "$flags" | tr -d '\r') == $'0\n0' && $services == *'ACTIVITY MANAGER SERVICES'* ]] \
      && ! print -r -- "$services" | rg -q 'ServiceRecord.*FileMcpService'; then
      stopped=1
      break
    fi
    sleep 0.2
  done
  if (( ! stopped )); then
    print -u2 -- "停止请求已接受，但服务退出结果未确认；保留本机通道状态，请核实手机后再查询。"
    exit 1
  fi
  if ! bounded_adb -s "$SERIAL" forward --remove "tcp:${LOCAL_PORT}" >/dev/null 2>&1 \
    || ! "$GATEWAY_BIN" clear-local >/dev/null 2>&1; then
    print -u2 -- "手机服务已停止，但本机转发或网关状态清理失败。"
    exit 1
  fi
  print -r -- "已停止手机 MCP 与远程通道。"
  exit 0
fi

if (( $# != 0 )); then
  print -u2 -- "用法: mcp.sh [status|profile|stop|pair <https-endpoint> <SPKI-SHA256> [--stdin]|unpair]"
  exit 2
fi

"$GATEWAY_BIN" start
if ! "$DIR/connect.sh" >/dev/null 2>&1; then
  for _ in {1..30}; do
    if "$GATEWAY_BIN" status | rg -q '^http://127\.0\.0\.1:18765/mcp$'; then
      "$GATEWAY_BIN" credentials
      exit 0
    fi
    sleep 0.5
  done
  print -u2 -- "adb 不可用，且远程通道还没有连上手机。"
  exit 1
fi
ADB=$(adb_bin)
SERIAL=$(online_serial "$ADB")
"$ADB" -s "$SERIAL" forward "tcp:${LOCAL_PORT}" "tcp:${REMOTE_PORT}" >/dev/null
"$ADB" -s "$SERIAL" shell am broadcast -f 0x00400000 \
  -n "$PKG/.McpControlReceiver" -a "$PKG.MCP" --ez on true >/dev/null 2>&1 || true
start_out=$("$ADB" -s "$SERIAL" shell am start-foreground-service -n "$PKG/.FileMcpService" 2>&1 || true)
if [[ $start_out == *Error* || $start_out == *Exception* ]]; then
  print -u2 -- "$start_out"
  exit 1
fi

for _ in {1..30}; do
  token=$(read_phone_token)
  if [[ -n $token ]]; then
    print -r -- "$token" | "$GATEWAY_BIN" local-token >/dev/null
    body=$(curl -sS -m 3 -X POST "http://127.0.0.1:${GATEWAY_PORT}/mcp" \
      -H 'Content-Type: application/json' \
      -H 'Accept: application/json, text/event-stream' \
      -H "Authorization: Bearer $("$GATEWAY_BIN" credentials | sed -n 's/^Authorization: Bearer //p')" \
      -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"phone","version":"0"}}}' \
      || true)
    if [[ $body == *手机工位* ]]; then
      "$GATEWAY_BIN" credentials
      exit 0
    fi
  fi
  sleep 0.4
done

print -u2 -- "MCP 网关还没有收到手机响应。"
if [[ -n ${start_out:-} ]]; then
  print -u2 -- "$start_out"
fi
"$ADB" -s "$SERIAL" logcat -d -t 30 -s StationMcp:I StationMcp:E StationMcp:W \
  | tr -d '\r' \
  | sed '/mcp token/d' \
  >&2 || true
exit 1
