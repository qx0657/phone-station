#!/bin/zsh
# 安装、查看或卸下手机上的「无线调试保持」。说明见 docs/adb-keep.md。
set -euo pipefail
DIR=${0:A:h}
source "$DIR/../lib/common.sh"
ROOT=${DIR:h}
PKG=dev.phonestation.adbkeep

if [[ ${1:-} == -h || ${1:-} == --help ]]; then
  print -r -- "用法: adb-keep.sh"
  print -r -- "      adb-keep.sh status"
  print -r -- "      adb-keep.sh --remove"
  print -r -- "在手机上安装「无线调试保持」，并授予写入系统设置的权限。"
  print -r -- "Wi-Fi 连着且 USB 调试开着时，应用会把被关掉的无线调试重新打开。"
  exit 0
fi
if [[ $# -gt 1 ]]; then
  print -u2 -- "不认识的参数: $2"
  exit 1
fi
if [[ -n ${1:-} && ${1:-} != status && ${1:-} != --remove ]]; then
  print -u2 -- "不认识的参数: $1"
  exit 1
fi

"$DIR/connect.sh"
ADB=$(adb_bin)
SERIAL=$(online_serial "$ADB")

if [[ ${1:-} == --remove ]]; then
  "$ADB" -s "$SERIAL" uninstall "$PKG"
  exit 0
fi

if [[ ${1:-} == status ]]; then
  print -r -- "无线调试 $("$ADB" -s "$SERIAL" shell settings get global adb_wifi_enabled)"
  print -r -- "USB 调试 $("$ADB" -s "$SERIAL" shell settings get global adb_enabled)"
  pid=$("$ADB" -s "$SERIAL" shell pidof "$PKG" || true)
  if [[ -n ${pid//[[:space:]]/} ]]; then
    print -r -- "进程 $pid"
  else
    print -r -- "进程 没在跑"
  fi
  "$ADB" -s "$SERIAL" shell dumpsys package "$PKG" | awk '
    /WRITE_SECURE_SETTINGS/ { show=1 }
    show { print }
    show && /granted=/ { exit }
  '
  exit 0
fi

"$ROOT/lib/adb-keep/build.sh"
apk=$ROOT/build/adb-keep.apk

set +e
install_out=$("$ADB" -s "$SERIAL" install -r "$apk" 2>&1)
install_code=$?
set -e
print -r -- "$install_out"
if [[ $install_out == *INSTALL_FAILED_UPDATE_INCOMPATIBLE* ]]; then
  print -r -- "签名和手机上的旧版本不一样，先卸下再装。"
  "$ADB" -s "$SERIAL" uninstall "$PKG"
  "$ADB" -s "$SERIAL" install -r "$apk"
elif (( install_code != 0 )); then
  exit "$install_code"
fi

"$ADB" -s "$SERIAL" shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS
"$ADB" -s "$SERIAL" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
"$ADB" -s "$SERIAL" shell dumpsys deviceidle whitelist +"$PKG" || true
# 服务没有 exported。这台 Android 15 上 shell 直接拉前台服务会被拒绝，由应用自己的界面启动。
"$ADB" -s "$SERIAL" shell am start -n "$PKG/.MainActivity"
sleep 1
"$ADB" -s "$SERIAL" logcat -d -t 20 -s AdbKeep:I AdbKeep:W AdbKeep:E
print -r -- "已安装。桌面上的「无线调试保持」可以暂停，或看权限是否还在。"
print -r -- "重启后要自己起来，到应用启动管理里允许自启动和后台活动。"
