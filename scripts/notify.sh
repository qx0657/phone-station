#!/bin/zsh
# 说明见仓库根目录 README.md，编译见 docs/notify.md
# 播放手机当前的通知铃声，并在下拉栏里更新同一条通知。
# 震动/静音模式下系统会把通知音量关掉，所以默认由「手机工位」用媒体音量播放。
# 下拉通知也由这个应用发出，不发声、不震动。连续调用改同一条的标题和内容。
# --shell 改回原来的方式：shell 发通知，并把铃声转成 PCM。转好的文件没变就直接播。
# --stack 每次另发一条，原来的留着，也不覆盖上面那一条。
# 用法: notify.sh [--title 标题] [--text 内容] [--sound 音频文件] [--agent 名称] [--stack] [--shell]
set -euo pipefail
DIR=${0:A:h}
source "$DIR/../lib/common.sh"

title="手机工位"
text="有一条提醒"
sound=""
agent=""
mode=replace
poster=app
while (( $# )); do
  case $1 in
    -h|--help)
      print -r -- "用法: notify.sh [--title 标题] [--text 内容] [--sound 音频文件] [--agent 名称] [--stack] [--shell]"
      print -r -- "播放系统设置里的通知铃声，并在下拉栏里更新同一条通知。"
      print -r -- "不写标题时是「手机工位」，不写内容时是「有一条提醒」。"
      print -r -- "不写 --sound 时用系统设置里的通知铃声。"
      print -r -- "连续调用会改这一条的文字，不会在下拉里叠成多条。"
      print -r -- "--agent 在通知右侧放这个 Agent 的图标，可以是 Grok、Claude、Codex。不写就不放。"
      print -r -- "再跑一次不带 --agent，会把右侧那张图去掉。"
      print -r -- "--stack 每次另发一条，原来的留着，也不覆盖上面那一条。"
      print -r -- "下拉通知和铃声默认都由手机上的「手机工位」完成。铃声走媒体音量。"
      print -r -- "--shell 改回原来的方式：用 shell 发一条，应用名写成「手机工位」，铃声转成 PCM。这条没有右侧图标。"
      exit 0
      ;;
    --title)
      shift
      if (( $# == 0 )); then
        print -u2 -- "缺少标题。"
        exit 2
      fi
      title=$1
      ;;
    --text)
      shift
      if (( $# == 0 )); then
        print -u2 -- "缺少内容。"
        exit 2
      fi
      text=$1
      ;;
    --sound)
      shift
      if (( $# == 0 )) || [[ -z $1 ]]; then
        print -u2 -- "缺少音频文件。"
        exit 2
      fi
      if [[ -n $sound ]]; then
        print -u2 -- "音频文件只能写一个。"
        exit 2
      fi
      sound=$1
      ;;
    --agent)
      shift
      if (( $# == 0 )) || [[ -z $1 ]]; then
        print -u2 -- "缺少 Agent。"
        exit 2
      fi
      if [[ -n $agent ]]; then
        print -u2 -- "Agent 只能写一个。"
        exit 2
      fi
      case $1 in
        Grok|Claude|Codex)
          agent=$1
          ;;
        *)
          print -u2 -- "不认识的 Agent: $1"
          exit 2
          ;;
      esac
      ;;
    --stack)
      mode=stack
      ;;
    --shell)
      poster=shell
      ;;
    --)
      shift
      if (( $# )); then
        print -u2 -- "不认识的参数: $1"
        exit 2
      fi
      break
      ;;
    *)
      print -u2 -- "不认识的参数: $1"
      exit 2
      ;;
  esac
  shift
done
if [[ -z $title || -z $text ]]; then
  print -u2 -- "标题和内容不能是空的。"
  exit 2
fi

dex="$DIR/../lib/notify-sound.dex"
if [[ "$poster" == shell && ! -f "$dex" ]]; then
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

if [[ "$poster" == app ]]; then
  app_script=$(cat <<'EOF'
set -eu
title=$1
text=$2
mode=${3:-replace}
agent=${4-}
sound=${5-}
set -- am broadcast -f 0x10000000 -n dev.phonestation.adbkeep/.AlertReceiver \
  -a dev.phonestation.adbkeep.ALERT \
  --es title "$title" --es text "$text" --es mode "$mode" --es agent "$agent"
if [ -n "$sound" ]; then
  set -- "$@" --es sound "$sound"
fi
result=$("$@" 2>&1 | tr -d "\r") || true
code=${result##*result=}
code=${code%%[!0-9-]*}
case $code in
  1)
    if [ "$mode" = stack ]; then
      echo "通知已发出" >&2
    else
      echo "通知已更新" >&2
    fi
    echo played
    ;;
  2)
    if [ "$mode" = stack ]; then
      echo "通知已发出" >&2
    else
      echo "通知已更新" >&2
    fi
    echo "铃声没有播放。" >&2
    exit 1
    ;;
  3)
    echo "没有读到通知铃声。" >&2
    exit 1
    ;;
  4)
    if [ "$mode" = stack ]; then
      echo "通知已发出" >&2
    else
      echo "通知已更新" >&2
    fi
    echo "铃声已静音。" >&2
    ;;
  *)
    echo "通知没有发出。" >&2
    printf '%s\n' "$result" >&2
    exit 1
    ;;
esac
EOF
)
  set +e
  out=$(run_device "$app_script" "$title" "$text" "$mode" "$agent" "$sound")
  rc=$?
  set -e
  if (( rc == 0 )); then
    [[ -n "$out" ]] && print -r -- "$out"
    exit 0
  fi
  [[ -n "$out" ]] && print -u2 -- "$out"
  exit $rc
fi

check_script=$(cat <<'EOF'
set -eu
expected_dex=$1
sound_arg=${2-}
title=$3
text=$4
mode=${5:-replace}
poster=${6:-app}
agent=${7-}
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

post_notice() {
  if [ "$poster" = shell ]; then
    if ! CLASSPATH=/data/local/tmp/notify-sound.dex app_process /data/local/tmp Notify "$title" "$text" "$mode"; then
      echo "通知没有发出。" >&2
    fi
    return 0
  fi
  result=$(am broadcast -n dev.phonestation.adbkeep/.AlertReceiver \
    -a dev.phonestation.adbkeep.ALERT \
    --es title "$title" --es text "$text" --es mode "$mode" --es agent "$agent" 2>&1 | tr -d "\r") || true
  case $result in
    *"Broadcast completed: result=1"*)
      if [ "$mode" = stack ]; then
        echo "通知已发出" >&2
      else
        echo "通知已更新" >&2
      fi
      ;;
    *)
      echo "通知没有发出。" >&2
      ;;
  esac
}

if [ "$source_ok" = 1 ] && [ "$dex_ok" = 1 ]; then
  post_notice
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
title=$5
text=$6
mode=${7:-replace}
poster=${8:-app}
agent=${9-}
post_notice() {
  if [ "$poster" = shell ]; then
    if ! CLASSPATH=/data/local/tmp/notify-sound.dex app_process /data/local/tmp Notify "$title" "$text" "$mode"; then
      echo "通知没有发出。" >&2
    fi
    return 0
  fi
  result=$(am broadcast -n dev.phonestation.adbkeep/.AlertReceiver \
    -a dev.phonestation.adbkeep.ALERT \
    --es title "$title" --es text "$text" --es mode "$mode" --es agent "$agent" 2>&1 | tr -d "\r") || true
  case $result in
    *"Broadcast completed: result=1"*)
      if [ "$mode" = stack ]; then
        echo "通知已发出" >&2
      else
        echo "通知已更新" >&2
      fi
      ;;
    *)
      echo "通知没有发出。" >&2
      ;;
  esac
}
printf "%s\t%s\t%s\t%s\n" "$1" "$2" "$3" "$4" > /data/local/tmp/notify-sound.stamp
post_notice
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

set +e
out=$(run_device "$check_script" "$dex_sum" "$sound" "$title" "$text" "$mode" "$poster" "$agent")
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
out=$(run_device "$commit_script" "$key" "$file" "$bytes" "$mtime" "$title" "$text" "$mode" "$poster" "$agent")
rc=$?
set -e
[[ -n "$out" ]] && print -r -- "$out"
exit $rc
