#!/bin/zsh
# 说明见仓库根目录 README.md，做法见 docs/adb-keep.md
# 电脑心跳改由网关的已认证请求记在手机内存里。这三个命令不再写系统设置。
set -euo pipefail
DIR=${0:A:h}
source "$DIR/../lib/common.sh"

if [[ ${1:-} == -h || ${1:-} == --help ]]; then
  print -r -- "用法: host-state.sh [mark|watch|clear]"
  print -r -- "电脑是否连着由手机内存里的已认证请求判断。这三个命令不再写系统设置，直接退出。"
  exit 0
fi
if [[ ${1:-mark} != mark && ${1:-} != watch && ${1:-} != clear ]]; then
  print -u2 -- "用法: host-state.sh [mark|watch|clear]"
  exit 1
fi
exit 0
