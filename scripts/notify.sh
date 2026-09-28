#!/bin/zsh
# 说明见仓库根目录 README.md，编译见 docs/notify.md
# 播放手机当前的通知铃声。
# 震动/静音模式下系统会把通知音量关掉，所以这里改走媒体音量，按脚本时听得到。
# 转好的 PCM 和播放程序留在手机上。铃声文件和 dex 都没变时直接播，不再 pull / 转码 / push。
# 用法: notify.sh
#       notify.sh /system/media/audio/notifications/Bell.ogg
set -euo pipefail
DIR=${0:A:h}
source "$DIR/../lib/common.sh"

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  print -r -- "用法: notify.sh [音频文件]"
  print -r -- "不带参数时播放系统设置里的通知铃声。"
  exit 0
fi

dex="$DIR/../lib/notify-sound.dex"
if [[ ! -f "$dex" ]]; then
  print -u2 -- "缺少 $dex"
  exit 1
fi
FFMPEG=$(command -v ffmpeg || true)
if [[ -z "$FFMPEG" && -x /opt/homebrew/bin/ffmpeg ]]; then
  FFMPEG=/opt/homebrew/bin/ffmpeg
fi

"$DIR/connect.sh"
ADB=$(adb_bin)
SERIAL=$(online_serial "$ADB")

# 手机上的缓存。stamp 一行，制表符分隔：铃声标识、文件路径、大小、修改时间。
# 标识是参数里的路径，或者 settings 里的 notification_sound。
remote_dex=/data/local/tmp/notify-sound.dex
remote_pcm=/data/local/tmp/notify-sound.pcm
remote_stamp=/data/local/tmp/notify-sound.stamp

run_device() {
  local script=$1 b64 arg quoted=""
  shift
  b64=$(print -rn -- "$script" | base64 | tr -d '\n')
  for arg in "$@"; do
    quoted+=" ${(q)arg}"
  done
  "$ADB" -s "$SERIAL" shell "printf %s $b64 | base64 -d | sh -s --$quoted"
}

check_script=$(cat <<'EOF'
set -eu
expected_dex=$1
sound_arg=${2-}
dex=/data/local/tmp/notify-sound.dex
pcm=/data/local/tmp/notify-sound.pcm
stamp=/data/local/tmp/notify-sound.stamp

if [ -n "$sound_arg" ]; then
  key=$sound_arg
else
  key=$(settings get system notification_sound | tr -d "\r")
fi
if [ -z "$key" ] || [ "$key" = null ]; then
  echo "没有读到通知铃声。" >&2
  exit 1
fi

dex_ok=0
if [ -f "$dex" ]; then
  have=$(md5sum "$dex")
  have=${have%% *}
  if [ "$have" = "$expected_dex" ]; then
    dex_ok=1
  fi
fi

skey=
sfile=
sbytes=
smtime=
if [ -f "$stamp" ]; then
  tab=$(printf "\t")
  IFS=$tab read -r skey sfile sbytes smtime < "$stamp" || true
fi

file=
bytes=
mtime=
source_ok=0
if [ -n "$skey" ] && [ "$skey" = "$key" ] && [ -n "$sfile" ] && [ -f "$sfile" ]; then
  file=$sfile
  bytes=$(stat -c "%s" "$file")
  mtime=$(stat -c "%Y" "$file")
  if [ -f "$pcm" ] && [ "$bytes" = "$sbytes" ] && [ "$mtime" = "$smtime" ]; then
    source_ok=1
  fi
fi

if [ "$source_ok" = 1 ] && [ "$dex_ok" = 1 ]; then
  echo "播放通知音 $file" >&2
  CLASSPATH=$dex exec app_process /data/local/tmp PlayPcm $pcm 44100
fi

if [ -z "$file" ]; then
  if [ -n "$sound_arg" ]; then
    file=$sound_arg
  else
    base=${key%%\?*}
    case $base in
      content://*)
        row=$(content query --uri "$base" --projection _data | tr -d "\r")
        file=${row#*_data=}
        file=${file%%,*}
        ;;
      file://*)
        file=${base#file://}
        ;;
      *)
        file=$base
        ;;
    esac
  fi
  if [ -z "$file" ] || [ "$file" = null ] || [ ! -f "$file" ]; then
    echo "没有读到通知铃声。" >&2
    exit 1
  fi
  bytes=$(stat -c "%s" "$file")
  mtime=$(stat -c "%Y" "$file")
fi

printf "MISS\t%s\t%s\t%s\t%s\t%s\t%s\n" "$source_ok" "$dex_ok" "$key" "$file" "$bytes" "$mtime"
exit 10
EOF
)

commit_script=$(cat <<'EOF'
set -eu
printf "%s\t%s\t%s\t%s\n" "$1" "$2" "$3" "$4" > /data/local/tmp/notify-sound.stamp
echo "播放通知音 $2" >&2
CLASSPATH=/data/local/tmp/notify-sound.dex exec app_process /data/local/tmp PlayPcm /data/local/tmp/notify-sound.pcm 44100
EOF
)

if command -v md5 >/dev/null; then
  dex_sum=$(md5 -q "$dex")
else
  dex_sum=$(md5sum "$dex")
  dex_sum=${dex_sum%% *}
fi

sound="${1:-}"
set +e
out=$(run_device "$check_script" "$dex_sum" "$sound")
rc=$?
set -e
if (( rc == 0 )); then
  [[ -n "$out" ]] && print -r -- "$out"
  exit 0
fi
if (( rc != 10 )); then
  [[ -n "$out" ]] && print -u2 -- "$out"
  exit $rc
fi

miss_line=""
for line in ${(f)out}; do
  line=${line//$'\r'/}
  if [[ "$line" == MISS$'\t'* ]]; then
    miss_line=$line
  fi
done
if [[ -z "$miss_line" ]]; then
  [[ -n "$out" ]] && print -u2 -- "$out"
  print -u2 -- "没有读到通知铃声。"
  exit 1
fi
IFS=$'\t' read -r _tag source_ok dex_ok key file bytes mtime <<<"$miss_line"
if [[ -z "$file" || -z "$key" || "$bytes" != <-> || "$mtime" != <-> ]]; then
  print -u2 -- "没有读到通知铃声。"
  exit 1
fi

if [[ "$source_ok" != 1 || "$dex_ok" != 1 ]]; then
  typeset -a parts
  parts=()
  [[ "$source_ok" != 1 ]] && parts+=("铃声")
  [[ "$dex_ok" != 1 ]] && parts+=("播放程序")
  print -u2 -- "${(j:和:)parts}传到手机。"
fi

if [[ "$source_ok" != 1 ]]; then
  if [[ -z "$FFMPEG" ]]; then
    print -u2 -- "找不到 ffmpeg，无法把通知铃声转成手机能播的音频。"
    exit 1
  fi
  work=$(mktemp -d)
  trap 'rm -rf "$work"' EXIT
  "$ADB" -s "$SERIAL" pull "$file" "$work/in" >/dev/null
  "$FFMPEG" -y -v error -i "$work/in" -ac 1 -ar 44100 -f s16le "$work/out.pcm"
  "$ADB" -s "$SERIAL" push "$work/out.pcm" "$remote_pcm" >/dev/null
fi
if [[ "$dex_ok" != 1 ]]; then
  "$ADB" -s "$SERIAL" push "$dex" "$remote_dex" >/dev/null
fi

set +e
out=$(run_device "$commit_script" "$key" "$file" "$bytes" "$mtime")
rc=$?
set -e
[[ -n "$out" ]] && print -r -- "$out"
exit $rc
