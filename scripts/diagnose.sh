#!/bin/zsh
# 把本机说明和网关日志尾部写成脱敏诊断目录。不连接手机，不打印日志内容。
set -euo pipefail
DIR=${0:A:h}
source "$DIR/../lib/common.sh"

if [[ ${1:-} == "-h" || ${1:-} == "--help" || $# -ne 1 ]]; then
  print -u2 -- "用法: scripts/diagnose.sh <空的输出目录>"
  [[ ${1:-} == "-h" || ${1:-} == "--help" ]]
  exit
fi

output=$1
log="$HOME/Library/Caches/Phone Station/remote-gateway.log"
python3 - "$DIR" "$output" "$log" <<'PY'
import sys
from pathlib import Path

sys.path.insert(0, str(Path(sys.argv[1]) / "../lib"))
from diagnose import gateway_excerpt, write_bundle

dest = Path(sys.argv[2])
log = Path(sys.argv[3])
parts = {"notes.txt": "phone-station diagnose\n"}
if log.is_file():
    parts["gateway-log.txt"] = gateway_excerpt(log)
write_bundle(dest, parts)
print(dest)
PY
