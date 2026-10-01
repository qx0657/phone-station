#!/bin/zsh
# 说明见仓库根目录 README.md，实现见 docs/mcp.md
# 打开手机上「手机工位」的 MCP服务，并把端口转到本机。
set -euo pipefail
DIR=${0:A:h}
source "$DIR/../lib/common.sh"

LOCAL_PORT=18765
REMOTE_PORT=8765
PKG=dev.phonestation.adbkeep

if [[ ${1:-} == -h || ${1:-} == --help ]]; then
  print -r -- "用法: mcp.sh"
  print -r -- "      mcp.sh status"
  print -r -- "      mcp.sh stop"
  print -r -- "打开手机工位的 MCP服务，转发到 http://127.0.0.1:${LOCAL_PORT}/mcp"
  print -r -- "同时打印 Authorization 头。服务只听手机本机，调用时要带这个头。"
  print -r -- "手机会记住开着，重启后还会再打开。"
  print -r -- "status  已经开着就转发并打印地址和令牌，没开就打印 off，不会把它打开。"
  print -r -- "stop  停掉 MCP服务，并去掉这次转发。"
  exit 0
fi
if [[ -n ${1:-} && ${1:-} != stop && ${1:-} != status ]]; then
  print -u2 -- "用法: mcp.sh [status|stop]"
  exit 1
fi

"$DIR/connect.sh"
ADB=$(adb_bin)
SERIAL=$(online_serial "$ADB")

mcp_token() {
  "$ADB" -s "$SERIAL" shell logcat -d -t 80 -s StationMcp:I \
    | tr -d '\r' \
    | sed -n 's/.*mcp token //p' \
    | tail -n 1 \
    | tr -d '[:space:]'
}

if [[ ${1:-} == stop ]]; then
  "$ADB" -s "$SERIAL" shell am broadcast -f 0x10000000 \
    -n "$PKG/.McpControlReceiver" -a "$PKG.MCP" --ez on false >/dev/null 2>&1 || \
    "$ADB" -s "$SERIAL" shell am stopservice -n "$PKG/.FileMcpService" >/dev/null 2>&1 || true
  "$ADB" -s "$SERIAL" forward --remove "tcp:${LOCAL_PORT}" >/dev/null 2>&1 || true
  print -r -- "已停止。"
  exit 0
fi

if [[ ${1:-} == status ]]; then
  flag=$("$ADB" -s "$SERIAL" shell settings get global phonestation_mcp | tr -d '\r' | tr -d '[:space:]')
  if [[ $flag != 1 ]]; then
    "$ADB" -s "$SERIAL" forward --remove "tcp:${LOCAL_PORT}" >/dev/null 2>&1 || true
    print -r -- "off"
    exit 0
  fi
  "$ADB" -s "$SERIAL" forward "tcp:${LOCAL_PORT}" "tcp:${REMOTE_PORT}" >/dev/null
  token=$(mcp_token)
  if [[ -z $token ]]; then
    print -r -- "on"
    exit 0
  fi
  print -r -- "http://127.0.0.1:${LOCAL_PORT}/mcp"
  print -r -- "Authorization: Bearer ${token}"
  exit 0
fi

"$ADB" -s "$SERIAL" forward "tcp:${LOCAL_PORT}" "tcp:${REMOTE_PORT}" >/dev/null
"$ADB" -s "$SERIAL" shell am broadcast -f 0x10000000 \
  -n "$PKG/.McpControlReceiver" -a "$PKG.MCP" --ez on true >/dev/null 2>&1 || true
start_out=$("$ADB" -s "$SERIAL" shell am start-foreground-service -n "$PKG/.FileMcpService" 2>&1 || true)
if [[ $start_out == *Error* || $start_out == *Exception* ]]; then
  print -u2 -- "$start_out"
  exit 1
fi

token=""
body=""
for _ in {1..30}; do
  token=$(mcp_token)
  if [[ -n $token ]]; then
    body=$(curl -sS -m 2 -X POST "http://127.0.0.1:${LOCAL_PORT}/mcp" \
      -H 'Content-Type: application/json' \
      -H 'Accept: application/json, text/event-stream' \
      -H "Authorization: Bearer ${token}" \
      -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"phone","version":"0"}}}' \
      || true)
    if [[ $body == *手机工位* ]]; then
      print -r -- "http://127.0.0.1:${LOCAL_PORT}/mcp"
      print -r -- "Authorization: Bearer ${token}"
      exit 0
    fi
  fi
  sleep 0.4
done

print -u2 -- "MCP服务还没有应答。"
if [[ -n $start_out ]]; then
  print -u2 -- "$start_out"
fi
"$ADB" -s "$SERIAL" logcat -d -t 30 -s StationMcp:I StationMcp:E StationMcp:W \
  | tr -d '\r' \
  | sed '/mcp token/d' \
  >&2 || true
exit 1
