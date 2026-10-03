#!/bin/zsh
# 在本机生成可直接打开的 Mac 菜单栏应用。
set -euo pipefail
DIR=${0:A:h}
source "$DIR/../lib/common.sh"
ROOT=${DIR:h}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  print -r -- "用法: build-mac-app.sh"
  print -r -- "生成 build/.phone-station/手机工位.app；可复制到 /Applications 使用。"
  exit 0
fi
if (( $# > 0 )); then
  print -u2 -- "用法: build-mac-app.sh"
  exit 1
fi

SWIFTC=$(xcrun --find swiftc)
SDK=$(xcrun --show-sdk-path)
TARGET="$(uname -m)-apple-macosx13.0"
mkdir -p "$ROOT/build/.phone-station"
GO=$(command -v go || true)
if [[ -z $GO ]]; then
  print -u2 -- "找不到 Go。先安装 Go 1.23 或更新版本，再构建 Mac App。"
  exit 1
fi
"$GO" -C "$ROOT/lib/remote-gateway" test -race ./...
"$GO" -C "$ROOT/lib/remote-gateway" build -trimpath \
  -o "$ROOT/build/.phone-station/.phone-relay-gateway-$$" .
mv -f "$ROOT/build/.phone-station/.phone-relay-gateway-$$" "$ROOT/build/.phone-station/phone-relay-gateway"
python3 "$ROOT/lib/test_mcp_pair.py"
python3 "$ROOT/lib/test_mcp_stop.py"
"$SWIFTC" -O -target "$TARGET" -sdk "$SDK" -framework Security \
  -o "$ROOT/build/.phone-station/.phone-relay-keychain-$$" \
  "$ROOT/lib/remote-gateway/keychain/main.swift"
mv -f "$ROOT/build/.phone-station/.phone-relay-keychain-$$" "$ROOT/build/.phone-station/phone-relay-keychain"
POLICY_TEST="$ROOT/build/.phone-station/reconnect-policy-test"
"$SWIFTC" -parse-as-library -swift-version 5 \
  -target "$TARGET" -sdk "$SDK" \
  "$ROOT/app/mac/Reconnect.swift" \
  "$ROOT/app/mac/ReconnectPolicyTest.swift" \
  -o "$POLICY_TEST"
"$POLICY_TEST"
ADB_TEST="$ROOT/build/.phone-station/adb-command-line-test"
"$SWIFTC" -parse-as-library -swift-version 5 \
  -target "$TARGET" -sdk "$SDK" \
  "$ROOT/app/mac/AdbCommandLine.swift" \
  "$ROOT/app/mac/AdbCommandLineTest.swift" \
  -o "$ADB_TEST"
"$ADB_TEST"
RELAY_TEST="$ROOT/build/.phone-station/remote-relay-profile-test"
"$SWIFTC" -parse-as-library -swift-version 5 \
  -target "$TARGET" -sdk "$SDK" \
  "$ROOT/app/mac/RemoteRelayProfile.swift" "$ROOT/app/mac/StationRunner.swift" \
  "$ROOT/app/mac/RemoteRelayProfileTest.swift" -o "$RELAY_TEST"
"$RELAY_TEST"
RUNNER_TEST="$ROOT/build/.phone-station/station-runner-test"
"$SWIFTC" -parse-as-library -swift-version 5 -target "$TARGET" -sdk "$SDK" \
  "$ROOT/app/mac/StationRunner.swift" "$ROOT/app/mac/StationRunnerTest.swift" -o "$RUNNER_TEST"
"$RUNNER_TEST"
CLIPBOARD_TEST="$ROOT/build/.phone-station/clipboard-sync-policy-test"
"$SWIFTC" -parse-as-library -swift-version 5 -target "$TARGET" -sdk "$SDK" \
  "$ROOT/app/mac/ClipboardProtocol.swift" "$ROOT/app/mac/ClipboardSyncPolicyTest.swift" \
  -o "$CLIPBOARD_TEST"
"$CLIPBOARD_TEST"
CLIPBOARD_SESSION_TEST="$ROOT/build/.phone-station/clipboard-session-test"
"$SWIFTC" -parse-as-library -swift-version 5 -target "$TARGET" -sdk "$SDK" \
  "$ROOT/app/mac/ClipboardProtocol.swift" "$ROOT/app/mac/ClipboardSession.swift" \
  "$ROOT/app/mac/ClipboardSessionTest.swift" -o "$CLIPBOARD_SESSION_TEST"
"$CLIPBOARD_SESSION_TEST"
CLIPBOARD_TRANSPORT_TEST="$ROOT/build/.phone-station/clipboard-transport-test"
"$SWIFTC" -parse-as-library -swift-version 5 -target "$TARGET" -sdk "$SDK" \
  "$ROOT/app/mac/ClipboardProtocol.swift" "$ROOT/app/mac/ClipboardSession.swift" \
  "$ROOT/app/mac/ClipboardTransportTest.swift" -o "$CLIPBOARD_TRANSPORT_TEST"
"$CLIPBOARD_TRANSPORT_TEST"
HEALTH_TEST="$ROOT/build/.phone-station/device-health-session-test"
"$SWIFTC" -parse-as-library -swift-version 5 -target "$TARGET" -sdk "$SDK" \
  "$ROOT/app/mac/ClipboardProtocol.swift" "$ROOT/app/mac/DeviceHealthSession.swift" \
  "$ROOT/app/mac/DeviceHealthSessionTest.swift" -o "$HEALTH_TEST"
"$HEALTH_TEST"
NOTIFICATION_TEST="$ROOT/build/.phone-station/notification-session-test"
"$SWIFTC" -parse-as-library -swift-version 5 -target "$TARGET" -sdk "$SDK" \
  -framework UserNotifications \
  "$ROOT/app/mac/ClipboardProtocol.swift" "$ROOT/app/mac/NotificationProtocol.swift" \
  "$ROOT/app/mac/NotificationDelivery.swift" "$ROOT/app/mac/NotificationSession.swift" \
  "$ROOT/app/mac/NotificationBanner.swift" \
  "$ROOT/app/mac/NotificationSessionTest.swift" -o "$NOTIFICATION_TEST"
"$NOTIFICATION_TEST"
BANNER_TEST="$ROOT/build/.phone-station/notification-banner-test"
"$SWIFTC" -parse-as-library -swift-version 5 -target "$TARGET" -sdk "$SDK" \
  "$ROOT/app/mac/NotificationProtocol.swift" "$ROOT/app/mac/NotificationBanner.swift" \
  "$ROOT/app/mac/NotificationBannerTest.swift" -o "$BANNER_TEST"
"$BANNER_TEST"
python3 "$ROOT/lib/test_notify_mcp.py"
python3 "$ROOT/lib/test_remote_ops.py"
"$SWIFTC" -parse-as-library -swift-version 5 -target "$TARGET" -sdk "$SDK" \
  "$ROOT/app/mac/InstallTask.swift" "$ROOT/app/mac/InstallTaskTest.swift" \
  -o "$ROOT/build/.phone-station/install-task-test"
"$ROOT/build/.phone-station/install-task-test"
"$ROOT/lib/android/build.sh"
APP="$ROOT/build/.phone-station/手机工位.app"
STAGING="$ROOT/build/.phone-station/.build-$$.app"
CONTENTS="$STAGING/Contents"
RESOURCES="$CONTENTS/Resources/phone-station"

trap 'rm -rf "$STAGING"' EXIT INT TERM
mkdir -p "$CONTENTS/MacOS" "$RESOURCES/scripts" "$RESOURCES/lib"
cp "$ROOT/app/mac/Info.plist" "$CONTENTS/Info.plist"
cp "$ROOT/README.md" "$RESOURCES/README.md"
for name in connect.sh disconnect.sh host-state.sh status.sh mirror.sh record.sh screenshot.sh stay-awake.sh torch.sh mcp.sh pair-code.sh android.sh notify.sh shell.sh install-apk.sh; do
  cp "$ROOT/scripts/$name" "$RESOURCES/scripts/$name"
done
cp "$ROOT/build/adb-keep.apk" "$RESOURCES/phone-app.apk"
cp "$ROOT/lib/common.sh" "$ROOT/lib/adb_mdns.py" "$ROOT/lib/torch.dex" "$ROOT/lib/torch_beat.py" "$RESOURCES/lib/"
cp "$ROOT/lib/notify_mcp.py" "$ROOT/lib/remote_ops.py" "$ROOT/lib/notify-sound.dex" "$RESOURCES/lib/"
cp "$ROOT/build/.phone-station/phone-relay-gateway" "$RESOURCES/lib/phone-relay-gateway"
cp "$ROOT/build/.phone-station/phone-relay-keychain" "$RESOURCES/lib/phone-relay-keychain"
chmod +x "$RESOURCES/lib/phone-relay-gateway" "$RESOURCES/lib/phone-relay-keychain"
mkdir -p "$RESOURCES/lib/torch-audio"
cp "$ROOT/lib/torch-audio/main.swift" "$RESOURCES/lib/torch-audio/"
audio_src="$ROOT/lib/torch-audio/main.swift"
audio_bin="$ROOT/lib/torch-audio/torch-audio"
if [[ -x "$audio_bin" && ! "$audio_src" -nt "$audio_bin" ]]; then
  cp "$audio_bin" "$RESOURCES/lib/torch-audio/torch-audio"
else
  # Process tap 从 14.2 才有。沿用 App 的 13.0 时，swiftc 会直接拒绝这些调用。
  "$SWIFTC" -O -framework CoreAudio -framework AudioToolbox \
    -target "$(uname -m)-apple-macosx14.2" -sdk "$SDK" \
    -o "$RESOURCES/lib/torch-audio/torch-audio" "$audio_src"
fi
chmod +x "$RESOURCES/lib/torch-audio/torch-audio"

sources=()
for file in "$ROOT"/app/mac/*.swift; do
  name=${file:t}
  if [[ $name == *Test.swift || $name == MakeIcon.swift ]]; then
    continue
  fi
  sources+=("$file")
done
CONNECTION_TEST="$ROOT/build/.phone-station/connection-status-test"
test_sources=()
for file in "${sources[@]}"; do
  [[ ${file:t} == PhoneStationApp.swift ]] || test_sources+=("$file")
done
"$SWIFTC" -parse-as-library -swift-version 5 \
  -target "$TARGET" -sdk "$SDK" \
  -framework SwiftUI -framework AppKit -framework ServiceManagement -framework QuartzCore \
  -framework AVFoundation -framework ImageIO -framework UserNotifications \
  "${test_sources[@]}" "$ROOT/app/mac/ConnectionStatusTest.swift" \
  -o "$CONNECTION_TEST"
"$CONNECTION_TEST"
"$SWIFTC" -parse-as-library -swift-version 5 -O \
  -target "$TARGET" -sdk "$SDK" \
  -framework SwiftUI -framework AppKit -framework ServiceManagement -framework QuartzCore \
  -framework AVFoundation -framework ImageIO -framework UserNotifications \
  "${sources[@]}" \
  -o "$CONTENTS/MacOS/PhoneStation"

ICON_WORK="$ROOT/build/.phone-station/icon-work"
ICONSET="$ICON_WORK/AppIcon.iconset"
mkdir -p "$ICONSET"
"$SWIFTC" -O -target "$(uname -m)-apple-macosx13.0" -sdk "$SDK" \
  -framework AppKit "$ROOT/app/mac/MakeIcon.swift" -o "$ICON_WORK/make-icon"
"$ICON_WORK/make-icon" "$ICON_WORK/icon-1024.png"
for size in 16 32 128 256 512; do
  sips -z "$size" "$size" "$ICON_WORK/icon-1024.png" --out "$ICONSET/icon_${size}x${size}.png" >/dev/null
  double=$((size * 2))
  sips -z "$double" "$double" "$ICON_WORK/icon-1024.png" --out "$ICONSET/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns "$ICONSET" -o "$CONTENTS/Resources/AppIcon.icns"

plutil -lint "$CONTENTS/Info.plist"
codesign --force --deep --sign - "$STAGING"
rm -rf "$APP"
mv "$STAGING" "$APP"
python3 "$ROOT/lib/release_manifest.py" android "$ROOT/build/adb-keep.apk" "$ROOT/build/.phone-station/android-manifest.json"
python3 "$ROOT/lib/release_manifest.py" mac "$APP" "$ROOT/build/.phone-station/mac-manifest.json"
print -r -- "已生成 $APP"
