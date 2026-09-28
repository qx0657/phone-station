#!/bin/zsh
# 说明见仓库根目录 README.md，实现见 docs/mt-mcp.md
# 打开 MT 的 MCP，并把端口转到本机。
set -euo pipefail
DIR=${0:A:h}
source "$DIR/../lib/common.sh"

LOCAL_PORT=18787
REMOTE_PORT=8787

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  print -r -- "用法: mt.sh [stop]"
  print -r -- "打开 MT 管理器的 MCP，转发到 http://127.0.0.1:${LOCAL_PORT}/mcp"
  print -r -- "stop  在 MT 里点停止，并去掉这次转发"
  exit 0
fi
if [[ -n "${1:-}" && "$1" != "stop" ]]; then
  print -u2 -- "用法: mt.sh [stop]"
  exit 1
fi

"$DIR/connect.sh"
ADB=$(adb_bin)
SERIAL=$(online_serial "$ADB")

forward_port() {
  "$ADB" -s "$SERIAL" forward "tcp:${LOCAL_PORT}" "tcp:${REMOTE_PORT}" >/dev/null
}

mcp_ready() {
  local body
  body=$(curl -s -m 2 "http://127.0.0.1:${LOCAL_PORT}/mcp" 2>/dev/null || true)
  [[ "$body" == *"MT MCP"* ]]
}

if [[ "${1:-}" == "stop" ]]; then
  python3 "$DIR/../lib/mt_mcp.py" ui-stop --adb "$ADB" --serial "$SERIAL"
  "$ADB" -s "$SERIAL" forward --remove "tcp:${LOCAL_PORT}" >/dev/null 2>&1 || true
  print -r -- "已停止。"
  exit 0
fi

forward_port
if mcp_ready; then
  print -r -- "http://127.0.0.1:${LOCAL_PORT}/mcp"
  exit 0
fi

python3 "$DIR/../lib/mt_mcp.py" ui-start --adb "$ADB" --serial "$SERIAL"

for _ in {1..30}; do
  if mcp_ready; then
    print -r -- "http://127.0.0.1:${LOCAL_PORT}/mcp"
    exit 0
  fi
  sleep 0.5
done
print -u2 -- "已经点了启动，但本机 ${LOCAL_PORT} 还没有应答。MT 里的端口如果不是 ${REMOTE_PORT}，以设置里的为准。"
exit 1
