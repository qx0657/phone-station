#!/bin/zsh
# 全局 hook 入口。事件和安装见 docs/notify.md。
set -euo pipefail
DIR=${0:A:h}
source "$DIR/../lib/common.sh"

if [[ ${1:-} == -h || ${1:-} == --help ]]; then
  print -r -- "用法: agent-notify-hook.sh"
  print -r -- "      agent-notify-hook.sh --dry-run"
  print -r -- "Codex、Claude、Grok 的 hook。从标准输入读 JSON，该响时播放通知铃声。"
  print -r -- "直接在终端运行也会响一声。--dry-run 只打印判断，不播放。"
  exit 0
fi

if [[ ${1:-} == --dry-run ]]; then
  exec python3 "$DIR/../lib/agent_notify_hook.py" "$@"
fi

python3 "$DIR/../lib/agent_notify_hook.py" "$@" || exit 0
