#!/bin/zsh
# 把 agent-notify-hook.sh 接到本机用户目录。说明见 docs/notify.md。
set -euo pipefail
DIR=${0:A:h}
source "$DIR/../lib/common.sh"

if [[ ${1:-} == -h || ${1:-} == --help ]]; then
  print -r -- "用法: install-agent-notify.sh"
  print -r -- "      install-agent-notify.sh --remove"
  print -r -- "把会话结束和中途询问的手机铃声接到本机的 Codex、Claude、Grok。"
  print -r -- "配置写在用户目录，这台电脑上所有项目都会响。已打开的会话要重开一次。"
  exit 0
fi

if [[ ${1:-} == --remove ]]; then
  exec python3 "$DIR/../lib/install_agent_notify.py" --remove
fi

if [[ $# -gt 0 ]]; then
  print -u2 -- "不认识的参数: $1"
  exit 1
fi

exec python3 "$DIR/../lib/install_agent_notify.py"
