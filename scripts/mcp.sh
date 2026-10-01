#!/bin/zsh
# 说明见仓库根目录 README.md，实现见 docs/mcp.md
# 在本机启动统一 MCP 网关，优先使用 adb，也可经深圳远程中继访问手机。
set -euo pipefail
DIR=${0:A:h}
source "$DIR/../lib/common.sh"

GATEWAY_PORT=18765
LOCAL_PORT=18766
REMOTE_PORT=8765
PKG=dev.phonestation.adbkeep
GATEWAY_BIN=""

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
  "$ADB" -s "$SERIAL" shell dumpsys activity service "$PKG/.FileMcpService" \
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
    "$ADB" forward --remove "tcp:${LOCAL_PORT}" >/dev/null 2>&1 || true
  fi
  "$GATEWAY_BIN" clear-local >/dev/null 2>&1 || true
}

refresh_local_route() {
  if ! ADB=$(adb_bin 2>/dev/null) || ! read_one_online_serial; then
    clear_local_route
    return 1
  fi
  local flag
  if ! flag=$("$ADB" -s "$SERIAL" shell settings get global phonestation_mcp 2>/dev/null | tr -d '\r[:space:]'); then
    clear_local_route
    return 1
  fi
  if [[ $flag != 1 ]]; then
    clear_local_route
    return 1
  fi
  if ! "$ADB" -s "$SERIAL" forward "tcp:${LOCAL_PORT}" "tcp:${REMOTE_PORT}" >/dev/null; then
    clear_local_route
    return 1
  fi
  local token
  token=$(read_phone_token)
  if [[ -z $token ]]; then
    clear_local_route
    return 1
  fi
  print -r -- "$token" | "$GATEWAY_BIN" local-token >/dev/null
}

if [[ ${1:-} == -h || ${1:-} == --help ]]; then
  print -r -- "用法: mcp.sh [status|stop|pair <https-endpoint> <SPKI-SHA256>|unpair]"
  print -r -- "打开本机 MCP 网关。它优先走 adb，必要时经深圳远程通道。"
  print -r -- "pair 需先有 adb；手机令牌和电脑令牌都会隐藏提示输入。"
  print -r -- "stop 关闭手机 MCP 与远程通道；unpair 清除两端远程凭据。"
  exit 0
fi

find_gateway

if [[ ${1:-} == pair ]]; then
  if (( $# != 3 )); then
    print -u2 -- "用法: mcp.sh pair <https-endpoint> <SPKI-SHA256>"
    exit 2
  fi
  endpoint=$2
  pin=$3
  if [[ ! $endpoint =~ '^https://[A-Za-z0-9.-]+:[0-9]{1,5}$' || ! $pin =~ '^[[:xdigit:]]{64}$' ]]; then
    print -u2 -- "地址必须是 https://主机:端口，SPKI SHA-256 必须是 64 位十六进制。"
    exit 2
  fi
  if ! ADB=$(adb_bin) || ! "$DIR/connect.sh" </dev/null >/dev/null; then
    print -u2 -- "配对需要 adb 在线。"
    exit 1
  fi
  SERIAL=$(online_serial "$ADB" </dev/null)
  read -r -s "phone_token?手机端 64 位十六进制令牌："
  print
  read -r -s "desktop_token?电脑端 64 位十六进制令牌："
  print
  if [[ ! $phone_token =~ '^[[:xdigit:]]{64}$' || ! $desktop_token =~ '^[[:xdigit:]]{64}$' ||
        ${phone_token:l} == ${desktop_token:l} ]]; then
    unset phone_token desktop_token
    print -u2 -- "令牌必须是两枚不同的 64 位十六进制随机值。"
    exit 2
  fi
  print -r -- "{\"endpoint\":\"$endpoint\",\"pin\":\"${pin:l}\",\"token\":\"${desktop_token:l}\"}" \
    | "$GATEWAY_BIN" configure
  "$GATEWAY_BIN" start
  remote_cmd="IFS= read -r token || exit 1; am broadcast -f 0x00400000 -n $PKG/.PhoneRelayControlReceiver -a $PKG.PHONE_RELAY --es action pair --es endpoint $endpoint --es pin ${pin:l} --es token \"\$token\" >/dev/null"
  print -r -- "${phone_token:l}" | "$ADB" -s "$SERIAL" shell "$remote_cmd"
  unset phone_token desktop_token
  remote_flag=$("$ADB" -s "$SERIAL" shell settings get global phonestation_remote | tr -d '\r[:space:]')
  if [[ $remote_flag != 1 ]]; then
    "$GATEWAY_BIN" unpair >/dev/null 2>&1 || true
    print -u2 -- "手机没有接受配对；检查中继服务、SPKI 指纹和手机令牌。"
    exit 1
  fi
  for _ in {1..30}; do
    if "$GATEWAY_BIN" status | rg -q 'relay=online'; then
      print -r -- "已配对，深圳中继往返正常。手机设置里的「远程通道」已打开。"
      exit 0
    fi
    sleep 0.5
  done
  print -u2 -- "配对资料已写入，但尚未确认深圳中继往返。可稍后运行 ./scripts/mcp.sh status 查看；不要重复提交有副作用的操作。"
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
  "$ADB" -s "$SERIAL" shell am broadcast -f 0x10000000 \
    -n "$PKG/.McpControlReceiver" -a "$PKG.MCP" --ez on false >/dev/null 2>&1 || \
    "$ADB" -s "$SERIAL" shell am stopservice -n "$PKG/.FileMcpService" >/dev/null 2>&1 || true
  "$ADB" -s "$SERIAL" forward --remove "tcp:${LOCAL_PORT}" >/dev/null 2>&1 || true
  "$GATEWAY_BIN" clear-local >/dev/null 2>&1 || true
  print -r -- "已停止手机 MCP 与远程通道。"
  exit 0
fi

if (( $# != 0 )); then
  print -u2 -- "用法: mcp.sh [status|stop|pair <https-endpoint> <SPKI-SHA256>|unpair]"
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
