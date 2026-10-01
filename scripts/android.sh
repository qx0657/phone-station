#!/bin/zsh
# 安装、查看或卸下手机上的「手机工位」。无线调试保持是这个应用里的一项功能。说明见 docs/adb-keep.md。
set -euo pipefail
DIR=${0:A:h}
source "$DIR/../lib/common.sh"
ROOT=${DIR:h}
PKG=dev.phonestation.adbkeep

popup_importance() {
  "$ADB" -s "$SERIAL" shell dumpsys notification --noredact | tr -d '\r' | awk '
    /mId='\''popup2'\''/ && /mDeleted=false/ {
      if (match($0, /mImportance=[0-9]+/)) {
        print substr($0, RSTART + 12, RLENGTH - 12)
        exit
      }
    }
  '
}

# 这台 MagicOS 把新建的高重要程度通道降到默认。勾选「横幅通知」才会锁回 4。
# 已经是 4 或 5 时不打开设置，避免把勾选点掉。
enable_alert_banner() {
  local importance action x y
  importance=$(popup_importance || true)
  importance=${importance//[[:space:]]/}
  if [[ $importance == 4 || $importance == 5 ]]; then
    return 0
  fi
  "$ADB" -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  # 设置页若还停在上一条通道，普通 start 不会换内容。清掉任务再打开这一条。
  "$ADB" -s "$SERIAL" shell am start --activity-clear-task \
    -a android.settings.CHANNEL_NOTIFICATION_SETTINGS \
    -p com.android.settings \
    --es android.provider.extra.APP_PACKAGE "$PKG" \
    --es android.provider.extra.CHANNEL_ID popup2 >/dev/null
  sleep 1
  action=$(banner_checkbox_action || true)
  if [[ $action == missing ]]; then
    sleep 0.8
    action=$(banner_checkbox_action || true)
  fi
  if [[ $action == tap\ * ]]; then
    x=${action#tap }
    y=${x#* }
    x=${x%% *}
    "$ADB" -s "$SERIAL" shell input tap "$x" "$y"
    sleep 1
  fi
  "$ADB" -s "$SERIAL" shell am start -n "$PKG/.MainActivity" >/dev/null
  importance=$(popup_importance || true)
  importance=${importance//[[:space:]]/}
  if [[ $importance == 4 || $importance == 5 ]]; then
    print -r -- "已勾选横幅通知。"
    return 0
  fi
  print -u2 -- "横幅通知没打开。解锁后打开「手机工位」的权限页，点「通知」，勾选「横幅通知」。"
}

banner_checkbox_action() {
  local dump=/sdcard/phonestation-banner.xml
  "$ADB" -s "$SERIAL" shell uiautomator dump "$dump" >/dev/null
  "$ADB" -s "$SERIAL" exec-out cat "$dump" | python3 -c '
import re, sys
xml = sys.stdin.read()
match = re.search(
    r"<node[^>]*resource-id=\"com.hihonor.systemmanager:id/banner_notification_style_checkbox\"[^>]*>",
    xml)
if not match:
    print("missing")
    raise SystemExit(0)
node = match.group(0)
if re.search(r"checked=\"true\"", node):
    print("checked")
    raise SystemExit(0)
bounds = re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", node)
if not bounds:
    print("missing")
    raise SystemExit(0)
x1, y1, x2, y2 = (int(value) for value in bounds.groups())
print("tap", (x1 + x2) // 2, (y1 + y2) // 2)
'
  "$ADB" -s "$SERIAL" shell rm -f "$dump" >/dev/null 2>&1 || true
}

if [[ ${1:-} == -h || ${1:-} == --help ]]; then
  print -r -- "用法: android.sh"
  print -r -- "      android.sh status"
  print -r -- "      android.sh --remove"
  print -r -- "在手机上安装「手机工位」。无线调试保持是里面的一项功能。"
  print -r -- "同时授所有文件访问，给同一应用里的 MCP服务用。"
  print -r -- "并把「提醒」通道的横幅通知打开。这台 MagicOS 不会让新装应用自己弹出。"
  print -r -- "Wi-Fi 连着且 USB 调试开着时，会把被关掉的无线调试重新打开。"
  print -r -- "界面顶部是电脑有没有连上。"
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
  print -r -- "$("$ADB" -s "$SERIAL" shell appops get "$PKG" MANAGE_EXTERNAL_STORAGE)"
  exit 0
fi

if [[ -x $ROOT/lib/android/build.sh ]]; then
  "$ROOT/lib/android/build.sh"
  apk=$ROOT/build/adb-keep.apk
elif [[ -f $DIR/../phone-app.apk ]]; then
  apk=$DIR/../phone-app.apk
else
  print -u2 -- "找不到手机工位的安装包。"
  exit 1
fi

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
# 所有文件访问不是运行时权限，pm grant 授不了。按 uid 用 appops 授。
"$ADB" -s "$SERIAL" shell appops set --uid "$PKG" MANAGE_EXTERNAL_STORAGE allow
storage=$("$ADB" -s "$SERIAL" shell appops get "$PKG" MANAGE_EXTERNAL_STORAGE)
print -r -- "$storage"
if [[ $storage != *allow* ]]; then
  print -u2 -- "没有授上所有文件访问。"
  exit 1
fi
"$ADB" -s "$SERIAL" shell dumpsys deviceidle whitelist +"$PKG" || true
# 服务没有 exported。这台 Android 15 上 shell 直接拉前台服务会被拒绝，由应用自己的界面启动。
# -S 先停掉旧进程。增量安装不会自己重启，不停的话新通道建不出来。
"$ADB" -s "$SERIAL" shell am start -S -n "$PKG/.MainActivity"
sleep 1
enable_alert_banner
"$ADB" -s "$SERIAL" logcat -d -t 20 -s AdbKeep:I AdbKeep:W AdbKeep:E
print -r -- "已安装。桌面上的「手机工位」可以暂停，或看权限是否还在。"
print -r -- "重启后要自己起来，到应用启动管理里允许自启动和后台活动。"
