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

python3 "$here/test_signing_key.py"

print -r -- "检查打开时机…"
javac --release 17 -d "$work/policy" \
  "$here/src/dev/phonestation/adbkeep/KeeperPolicy.java" \
  "$here/src/dev/phonestation/adbkeep/WirelessControl.java" \
  "$here/src/dev/phonestation/adbkeep/InstallRecovery.java" \
  "$here/src/dev/phonestation/adbkeep/KeeperCopy.java" \
  "$here/src/dev/phonestation/adbkeep/HostLink.java" \
  "$here/src/dev/phonestation/adbkeep/RelayRetry.java" \
  "$here/src/dev/phonestation/adbkeep/RelayConnection.java" \
  "$here/src/dev/phonestation/adbkeep/RelayProfile.java" \
  "$here/src/dev/phonestation/adbkeep/AlertNote.java" \
  "$here/src/dev/phonestation/adbkeep/AlertSoundPlan.java" \
  "$here/src/dev/phonestation/adbkeep/AlertSender.java" \
  "$here/src/dev/phonestation/adbkeep/PermissionCopy.java" \
  "$here/src/dev/phonestation/adbkeep/McpHelp.java" \
  "$here/src/dev/phonestation/adbkeep/AboutCopy.java" \
  "$here/src/dev/phonestation/adbkeep/StationNote.java" \
  "$here/src/dev/phonestation/adbkeep/FilePolicy.java" \
  "$here/src/dev/phonestation/adbkeep/Json.java" \
  "$here/src/dev/phonestation/adbkeep/StationPlaces.java" \
  "$here/src/dev/phonestation/adbkeep/StayAwake.java" \
  "$here/src/dev/phonestation/adbkeep/FileTypes.java" \
  "$here/src/dev/phonestation/adbkeep/StationHost.java" \
  "$here/src/dev/phonestation/adbkeep/ClipboardState.java" \
  "$here/src/dev/phonestation/adbkeep/ClipboardAvailability.java" \
  "$here/src/dev/phonestation/adbkeep/HealthStatus.java" \
  "$here/src/dev/phonestation/adbkeep/NotificationSyncState.java" \
  "$here/src/dev/phonestation/adbkeep/ClipboardMethods.java" \
  "$here/src/dev/phonestation/adbkeep/ShellRequest.java" \
  "$here/src/dev/phonestation/adbkeep/OperationJobs.java" \
  "$here/src/dev/phonestation/adbkeep/ShellRunner.java" \
  "$here/src/dev/phonestation/adbkeep/ScreenCapture.java" \
  "$here/src/dev/phonestation/adbkeep/FileOps.java" \
  "$here/src/dev/phonestation/adbkeep/McpProtocol.java" \
  "$here/src/dev/phonestation/adbkeep/LoopbackBoundary.java" \
  "$here/src/dev/phonestation/adbkeep/McpHttp.java" \
  "$here/test/KeeperPolicyTest.java" \
  "$here/test/WirelessControlTest.java" \
  "$here/test/InstallRecoveryTest.java" \
  "$here/test/KeeperCopyTest.java" \
  "$here/test/HostLinkTest.java" \
  "$here/test/RelayRetryTest.java" \
  "$here/test/RelayConnectionTest.java" \
  "$here/test/RelayProfileTest.java" \
  "$here/test/AlertNoteTest.java" \
  "$here/test/AlertSoundPlanTest.java" \
  "$here/test/AlertSenderTest.java" \
  "$here/test/PermissionCopyTest.java" \
  "$here/test/McpHelpTest.java" \
  "$here/test/AboutCopyTest.java" \
  "$here/test/StationNoteTest.java" \
  "$here/test/JsonTest.java" \
  "$here/test/FilePolicyTest.java" \
  "$here/test/FileOpsTest.java" \
  "$here/test/StayAwakeTest.java" \
  "$here/test/ScreenCaptureTest.java" \
  "$here/test/McpProtocolTest.java" \
  "$here/test/McpHttpTest.java" \
  "$here/test/McpLoopbackTest.java" \
  "$here/test/OperationJobsTest.java" \
  "$here/test/ShellRunnerTest.java" \
  "$here/test/ClipboardStateTest.java" \
  "$here/test/HealthStatusTest.java" \
  "$here/test/NotificationSyncStateTest.java"
java -cp "$work/policy" dev.phonestation.adbkeep.KeeperPolicyTest
java -cp "$work/policy" dev.phonestation.adbkeep.WirelessControlTest
java -cp "$work/policy" dev.phonestation.adbkeep.InstallRecoveryTest
java -cp "$work/policy" dev.phonestation.adbkeep.KeeperCopyTest
java -cp "$work/policy" dev.phonestation.adbkeep.HostLinkTest
java -cp "$work/policy" dev.phonestation.adbkeep.RelayRetryTest
java -cp "$work/policy" dev.phonestation.adbkeep.RelayConnectionTest
java -cp "$work/policy" dev.phonestation.adbkeep.RelayProfileTest
java -cp "$work/policy" dev.phonestation.adbkeep.AlertNoteTest
java -cp "$work/policy" dev.phonestation.adbkeep.AlertSoundPlanTest
java -cp "$work/policy" dev.phonestation.adbkeep.AlertSenderTest
java -cp "$work/policy" dev.phonestation.adbkeep.PermissionCopyTest
java -cp "$work/policy" dev.phonestation.adbkeep.McpHelpTest
java -cp "$work/policy" dev.phonestation.adbkeep.AboutCopyTest
java -cp "$work/policy" dev.phonestation.adbkeep.StationNoteTest
java -cp "$work/policy" dev.phonestation.adbkeep.JsonTest
java -cp "$work/policy" dev.phonestation.adbkeep.FilePolicyTest
java -cp "$work/policy" dev.phonestation.adbkeep.FileOpsTest
java -cp "$work/policy" dev.phonestation.adbkeep.StayAwakeTest
java -cp "$work/policy" dev.phonestation.adbkeep.ScreenCaptureTest
java -cp "$work/policy" dev.phonestation.adbkeep.McpProtocolTest
java -cp "$work/policy" dev.phonestation.adbkeep.McpHttpTest
java -cp "$work/policy" dev.phonestation.adbkeep.McpLoopbackTest
java -cp "$work/policy" dev.phonestation.adbkeep.OperationJobsTest
java -cp "$work/policy" dev.phonestation.adbkeep.ShellRunnerTest
java -cp "$work/policy" dev.phonestation.adbkeep.ClipboardStateTest
java -cp "$work/policy" dev.phonestation.adbkeep.HealthStatusTest
java -cp "$work/policy" dev.phonestation.adbkeep.NotificationSyncStateTest

print -r -- "打包资源…"
"$bt/aapt2" compile --dir "$here/res" -o "$work/compiled.zip"
mkdir -p "$work/flats"
unzip -q "$work/compiled.zip" -d "$work/flats"
flats=("${(@f)$(find "$work/flats" -name '*.flat')}")
"$bt/aapt2" link \
  -I "$jar" \
  --manifest "$here/AndroidManifest.xml" \
  --java "$work/gen" \
  --min-sdk-version 29 \
  --target-sdk-version 35 \
  --compile-sdk-version-code 35 \
  --compile-sdk-version-name 35 \
  -o "$work/unsigned.apk" \
  "${flats[@]}"

print -r -- "取 Shizuku API…"
shizuku_dir=$repo/build/third_party/shizuku
shizuku_ver=13.1.5
shizuku_base=https://repo1.maven.org/maven2/dev/rikka/shizuku
mkdir -p "$shizuku_dir"
shizuku_jars=()
for artifact in api aidl shared provider; do
  aar=$shizuku_dir/${artifact}-${shizuku_ver}.aar
  classes=$shizuku_dir/${artifact}-${shizuku_ver}.jar
  if [[ ! -f $aar ]]; then
    curl -fsSL -o "$aar" "$shizuku_base/$artifact/$shizuku_ver/${artifact}-${shizuku_ver}.aar"
  fi
  if [[ ! -f $classes || $aar -nt $classes ]]; then
    unzip -p "$aar" classes.jar > "$classes"
  fi
  shizuku_jars+=("$classes")
done
shizuku_cp=${(j.:.)shizuku_jars}

print -r -- "编译…"
javac --release 17 -classpath "$jar:$shizuku_cp" -d "$work/classes" \
  "$work/gen/dev/phonestation/adbkeep/R.java" \
  "$here"/src/dev/phonestation/adbkeep/*.java
mkdir -p "$work/dex"
class_files=("${(@f)$(find "$work/classes" -name '*.class')}")
"$bt/d8" --min-api 29 --lib "$jar" --output "$work/dex" "${class_files[@]}" "${shizuku_jars[@]}"
zip -j -q "$work/unsigned.apk" "$work/dex/classes.dex"

mkdir -p "$repo/build"
# 签过名的钥匙不放进 build/。删掉构建目录或换一台电脑时，把这一份拷到同一路径再编，才不用卸掉重装。
ks_dir=${PHONE_STATION_KEY_DIR:-$HOME/.phonestation}
legacy=$repo/build/adb-keep.keystore
if ! signing_output=$(python3 "$here/signing_key.py" "$ks_dir" "$legacy"); then
  exit 1
fi
signing_paths=("${(@f)signing_output}")
ks=${signing_paths[1]}
ks_password=${signing_paths[2]}

out=$repo/build/adb-keep.apk
"$bt/zipalign" -f 4 "$work/unsigned.apk" "$work/aligned.apk"
"$bt/apksigner" sign \
  --ks "$ks" \
  --ks-key-alias adbkeep \
  --ks-pass "file:$ks_password" \
  --out "$out" \
  "$work/aligned.apk"
print -r -- "写入 $out"
