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
mkdir -p "$ROOT/build/.phone-station"
POLICY_TEST="$ROOT/build/.phone-station/reconnect-policy-test"
"$SWIFTC" -parse-as-library -swift-version 5 \
  -target "$(uname -m)-apple-macosx13.0" -sdk "$SDK" \
  "$ROOT/app/mac/Reconnect.swift" \
  "$ROOT/app/mac/ReconnectPolicyTest.swift" \
  -o "$POLICY_TEST"
"$POLICY_TEST"
APP="$ROOT/build/.phone-station/手机工位.app"
STAGING="$ROOT/build/.phone-station/.build-$$.app"
CONTENTS="$STAGING/Contents"
RESOURCES="$CONTENTS/Resources/phone-station"

trap 'rm -rf "$STAGING"' EXIT INT TERM
mkdir -p "$CONTENTS/MacOS" "$RESOURCES/scripts" "$RESOURCES/lib"
cp "$ROOT/app/mac/Info.plist" "$CONTENTS/Info.plist"
cp "$ROOT/README.md" "$RESOURCES/README.md"
for name in connect.sh disconnect.sh status.sh mirror.sh record.sh screenshot.sh stay-awake.sh torch.sh; do
  cp "$ROOT/scripts/$name" "$RESOURCES/scripts/$name"
done
cp "$ROOT/lib/common.sh" "$ROOT/lib/adb_mdns.py" "$ROOT/lib/torch.dex" "$ROOT/lib/torch_beat.py" "$RESOURCES/lib/"
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

"$SWIFTC" -parse-as-library -swift-version 5 -O \
  -target "$(uname -m)-apple-macosx13.0" -sdk "$SDK" \
  -framework SwiftUI -framework AppKit -framework ServiceManagement -framework QuartzCore \
  -framework AVFoundation -framework ImageIO \
  "$ROOT/app/mac/AdbCommandLine.swift" \
  "$ROOT/app/mac/Reconnect.swift" \
  "$ROOT/app/mac/PhoneStationApp.swift" \
  "$ROOT/app/mac/RecentFiles.swift" \
  "$ROOT/app/mac/StationModel.swift" \
  "$ROOT/app/mac/StationView.swift" \
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
print -r -- "已生成 $APP"
