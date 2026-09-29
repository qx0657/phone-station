#!/bin/zsh
# 用本目录的 PlayPcm.java 重新生成 ../notify-sound.dex。
# 说明见 ../../docs/notify.md
set -euo pipefail
root=${0:A:h}
out=${root:h}/notify-sound.dex
JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk}
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

if [[ ! -x "$JAVA_HOME/bin/javac" ]]; then
  print -u2 -- "找不到 javac。JAVA_HOME=$JAVA_HOME"
  exit 1
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
print -r -- "下载 D8…"
curl -fsSL -o "$work/r8.jar" \
  https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.9.35/r8-8.9.35.jar

javac --release 17 -d "$work/stubs-classes" \
  "$root"/stubs/android/app/*.java \
  "$root"/stubs/android/content/*.java \
  "$root"/stubs/android/graphics/drawable/*.java \
  "$root"/stubs/android/media/*.java \
  "$root"/stubs/android/net/*.java \
  "$root"/stubs/android/os/*.java
jar cf "$work/stubs.jar" -C "$work/stubs-classes" .
javac --release 17 -cp "$work/stubs.jar" -d "$work/classes" \
  "$root/PlayPcm.java" "$root/Notify.java"
mkdir -p "$work/dex"
java -cp "$work/r8.jar" com.android.tools.r8.D8 \
  --min-api 26 --lib "$work/stubs.jar" \
  --output "$work/dex" "$work/classes"/*.class
cp "$work/dex/classes.dex" "$out"
print -r -- "写入 $out"
