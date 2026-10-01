#!/bin/zsh
# 用本目录的源码生成仓库 build/adb-keep.apk。
# 说明见 ../../docs/adb-keep.md
set -euo pipefail
here=${0:A:h}
repo=${here:h:h}
JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk}
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

if [[ ! -x "$JAVA_HOME/bin/javac" ]]; then
  print -u2 -- "找不到 javac。JAVA_HOME=$JAVA_HOME"
  exit 1
fi

sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}
jar=$sdk/platforms/android-35/android.jar
if [[ ! -f $jar ]]; then
  print -u2 -- "找不到 $jar"
  print -u2 -- "先安装：android sdk install platforms/android-35 build-tools/35.0.0"
  exit 1
fi
bt=$(print -l "$sdk"/build-tools/*(/N) | sort | tail -n 1)
if [[ -z $bt || ! -x $bt/aapt2 ]]; then
  print -u2 -- "找不到 build-tools。先安装：android sdk install build-tools/35.0.0"
  exit 1
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

print -r -- "检查打开时机…"
javac --release 17 -d "$work/policy" \
  "$here/src/dev/phonestation/adbkeep/KeeperPolicy.java" \
  "$here/test/KeeperPolicyTest.java"
java -cp "$work/policy" dev.phonestation.adbkeep.KeeperPolicyTest

print -r -- "打包资源…"
"$bt/aapt2" link \
  -I "$jar" \
  --manifest "$here/AndroidManifest.xml" \
  --min-sdk-version 26 \
  --target-sdk-version 35 \
  --compile-sdk-version-code 35 \
  --compile-sdk-version-name 35 \
  -o "$work/unsigned.apk"

print -r -- "编译…"
javac --release 17 -classpath "$jar" -d "$work/classes" \
  "$here"/src/dev/phonestation/adbkeep/*.java
mkdir -p "$work/dex"
class_files=("${(@f)$(find "$work/classes" -name '*.class')}")
"$bt/d8" --min-api 26 --lib "$jar" --output "$work/dex" "${class_files[@]}"
zip -j -q "$work/unsigned.apk" "$work/dex/classes.dex"

mkdir -p "$repo/build"
ks=$repo/build/adb-keep.keystore
if [[ ! -f $ks ]]; then
  keytool -genkeypair \
    -keystore "$ks" \
    -storepass android \
    -keypass android \
    -alias adbkeep \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Phone Station Adb Keep, O=Phone Station, C=CN"
fi

out=$repo/build/adb-keep.apk
"$bt/zipalign" -f 4 "$work/unsigned.apk" "$work/aligned.apk"
"$bt/apksigner" sign \
  --ks "$ks" \
  --ks-key-alias adbkeep \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$out" \
  "$work/aligned.apk"
print -r -- "写入 $out"
