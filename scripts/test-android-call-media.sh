#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Run inside nix develop}"
device=${1:?Usage: test-android-call-media.sh ADB_DEVICE NEW_APK}
apk=${2:?Pass the newly built APK explicitly; the installed APK is never used}
[[ -f "$apk" ]] || { echo 'APK missing' >&2; exit 1; }
mkdir -p .state
state=$(mktemp -d "$PWD/.state/call-media-smoke.XXXXXX")
remote=
cleanup() {
    if [[ "$remote" =~ ^/data/local/tmp/monogram-call-media-smoke\.[A-Za-z0-9]+$ ]]; then
        adb -s "$device" shell rm -rf "$remote" >/dev/null
    fi
}
trap cleanup EXIT
cp "$apk" "$state/app.apk"
abi=$(adb -s "$device" shell getprop ro.product.cpu.abi | tr -d '\r')
[[ "$abi" == arm64-v8a || "$abi" == x86_64 ]] || { echo '64-bit device required for this isolated smoke' >&2; exit 1; }
sdk=$(adb -s "$device" shell getprop ro.build.version.sdk | tr -d '\r')
[[ "$sdk" =~ ^[0-9]+$ && "$sdk" -ge 26 ]] || { echo 'Android 8+ required for this isolated smoke' >&2; exit 1; }
python3 - "$state/app.apk" "$state/libtmessages.49.so" "$abi" <<'PY'
import hashlib, pathlib, sys, zipfile
apk, output, abi = sys.argv[1:]
with zipfile.ZipFile(apk) as archive:
    data = archive.read(f'lib/{abi}/libtmessages.49.so')
pathlib.Path(output).write_bytes(data)
print('Supplied APK SHA256:', hashlib.sha256(pathlib.Path(apk).read_bytes()).hexdigest())
print('Supplied native SHA256:', hashlib.sha256(data).hexdigest())
PY
classpath="$ANDROID_HOME/platforms/android-36/android.jar:TMessagesProj/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes:xmpp-core/build/classes/java/main"
while IFS= read -r jar; do classpath+=":$jar"; done < <(rg --files --hidden .m2/repository/org/igniterealtime .m2/repository/org/jxmpp | rg '\.jar$' | rg -v 'sources|javadoc')
mkdir -p "$state/classes"
javac --release 8 -cp "$classpath" -d "$state/classes" tests/android/org/telegram/messenger/xmpp/MonogramCallMediaDeviceTest.java
mapfile -t classes < <(rg --files "$state/classes" -g '*.class')
# Match the APK's minSdk so interface statics (EglBase.create, etc.) use its D8
# companion classes even though this harness itself requires an Android 8+ device.
"$ANDROID_HOME/build-tools/36.0.0/d8" --min-api 23 --lib "$ANDROID_HOME/platforms/android-36/android.jar" --classpath TMessagesProj/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes --classpath xmpp-core/build/classes/java/main --output "$state/tests.zip" "${classes[@]}"
remote=$(adb -s "$device" shell mktemp -d /data/local/tmp/monogram-call-media-smoke.XXXXXX | tr -d '\r')
[[ "$remote" =~ ^/data/local/tmp/monogram-call-media-smoke\.[A-Za-z0-9]+$ ]] || { echo 'Invalid disposable smoke directory' >&2; exit 1; }
adb -s "$device" push "$state/tests.zip" "$remote/tests.zip" >/dev/null
adb -s "$device" push "$state/app.apk" "$remote/app.apk" >/dev/null
adb -s "$device" push "$state/libtmessages.49.so" "$remote/libtmessages.49.so" >/dev/null
adb -s "$device" shell chmod 400 "$remote/tests.zip" "$remote/app.apk" "$remote/libtmessages.49.so"
# Shell UID, separate process, no run-as/app launch, no account context, no capture or signaling.
adb -s "$device" shell "CLASSPATH=$remote/tests.zip:$remote/app.apk app_process64 /system/bin org.telegram.messenger.xmpp.MonogramCallMediaDeviceTest $remote" | tee "$state/result.log"
