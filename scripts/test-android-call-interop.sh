#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Run inside nix develop}"
device=${1:?Usage: test-android-call-interop.sh ADB_DEVICE NEW_APK [native-offer|browser-offer]}
apk=${2:?Pass the newly built APK explicitly}
mode=${3:-native-offer}
[[ "$mode" == native-offer || "$mode" == browser-offer ]] || { echo 'Invalid offer mode' >&2; exit 1; }
[[ -f "$apk" ]] || { echo 'APK missing' >&2; exit 1; }
[[ -f tests/calls/firefox-peer.mjs ]] || { echo 'Firefox peer missing' >&2; exit 1; }
command -v node >/dev/null
command -v firefox >/dev/null
mkdir -p .state
state=$(mktemp -d "$PWD/.state/call-interop.XXXXXX")
remote=
node_pid=
reverse_port=
cleanup() {
    if [[ -n "$node_pid" ]]; then kill "$node_pid" 2>/dev/null || true;wait "$node_pid" 2>/dev/null || true; fi
    if [[ -n "$reverse_port" ]]; then adb -s "$device" reverse --remove "tcp:$reverse_port" >/dev/null || true; fi
    if [[ "$remote" =~ ^/data/local/tmp/monogram-call-interop\.[A-Za-z0-9]+$ ]]; then
        adb -s "$device" shell rm -rf "$remote" >/dev/null || true
    fi
    rm -f "$state/app.apk" "$state/libtmessages.49.so" "$state/tests.zip" "$state/peer.json"
    rm -rf "$state/classes" "$state/firefox-profile" "$state/browser-cache"
}
trap cleanup EXIT
cp "$apk" "$state/app.apk"
abi=$(adb -s "$device" shell getprop ro.product.cpu.abi | tr -d '\r')
[[ "$abi" == arm64-v8a || "$abi" == x86_64 ]] || { echo '64-bit device required' >&2; exit 1; }
sdk=$(adb -s "$device" shell getprop ro.build.version.sdk | tr -d '\r')
[[ "$sdk" =~ ^[0-9]+$ && "$sdk" -ge 26 ]] || { echo 'Android 8+ required' >&2; exit 1; }
python3 - "$state" "$abi" "$mode" <<'PY'
import hashlib, json, pathlib, subprocess, sys, zipfile
root, abi, mode = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
apk = (root/'app.apk').read_bytes()
with zipfile.ZipFile(root/'app.apk') as archive:
    native = archive.read(f'lib/{abi}/libtmessages.49.so')
(root/'libtmessages.49.so').write_bytes(native)
(root/'metadata.json').write_text(json.dumps(dict(mode=mode, browserVersion=subprocess.check_output(['firefox','--version'],text=True,timeout=10).strip(), apkSha256=hashlib.sha256(apk).hexdigest(), nativeSha256=hashlib.sha256(native).hexdigest())))
PY
classpath="$ANDROID_HOME/platforms/android-36/android.jar:TMessagesProj/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes:xmpp-core/build/classes/java/main"
while IFS= read -r jar; do classpath+=":$jar"; done < <(rg --files --hidden .m2/repository/org/igniterealtime .m2/repository/org/jxmpp | rg '\.jar$' | rg -v 'sources|javadoc')
mkdir -p "$state/classes"
javac --release 8 -cp "$classpath" -d "$state/classes" tests/android/org/telegram/messenger/xmpp/MonogramCallMediaDeviceTest.java tests/android/org/telegram/messenger/xmpp/MonogramCallInteropDeviceTest.java
mapfile -t classes < <(rg --files "$state/classes" -g '*.class')
# Match the APK interface desugaring, regardless of the device's Android version.
"$ANDROID_HOME/build-tools/36.0.0/d8" --min-api 23 --lib "$ANDROID_HOME/platforms/android-36/android.jar" --classpath TMessagesProj/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes --classpath xmpp-core/build/classes/java/main --output "$state/tests.zip" "${classes[@]}"
node tests/calls/firefox-peer.mjs "$state" "$mode" >"$state/browser.log" 2>&1 &
node_pid=$!
python3 - "$state/peer.json" "$node_pid" <<'PY'
import json, os, pathlib, re, sys, time
path, pid = pathlib.Path(sys.argv[1]), int(sys.argv[2])
for _ in range(200):
    os.kill(pid, 0)
    if path.is_file():
        data = json.loads(path.read_text())
        if isinstance(data.get('port'), int) and 1 <= data['port'] <= 65535 and re.fullmatch('[0-9a-f]{32}', str(data.get('token', ''))):
            break
        raise SystemExit('Invalid Firefox peer metadata')
    time.sleep(.1)
else:
    raise SystemExit('Firefox peer did not initialize')
PY
mapfile -t peer < <(python3 - "$state/peer.json" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
print(data['port']);print(data['token'])
PY
)
port=${peer[0]}
token=${peer[1]}
adb -s "$device" reverse --no-rebind "tcp:$port" "tcp:$port"
reverse_port=$port
remote=$(adb -s "$device" shell mktemp -d /data/local/tmp/monogram-call-interop.XXXXXX | tr -d '\r')
[[ "$remote" =~ ^/data/local/tmp/monogram-call-interop\.[A-Za-z0-9]+$ ]] || { echo 'Invalid disposable interop directory' >&2; exit 1; }
adb -s "$device" push "$state/tests.zip" "$remote/tests.zip" >/dev/null
adb -s "$device" push "$state/app.apk" "$remote/app.apk" >/dev/null
adb -s "$device" push "$state/libtmessages.49.so" "$remote/libtmessages.49.so" >/dev/null
adb -s "$device" shell chmod 400 "$remote/tests.zip" "$remote/app.apk" "$remote/libtmessages.49.so"
# ADB reverse carries only fixture HTTP signaling. ICE media needs a real reachable LAN route.
adb -s "$device" shell "CLASSPATH=$remote/tests.zip:$remote/app.apk app_process64 /system/bin org.telegram.messenger.xmpp.MonogramCallInteropDeviceTest $remote http://127.0.0.1:$port/$token/ $mode" | tee "$state/native.log"
python3 - "$state/result.json" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
for name in ('browser', 'native'):
    result = data.get(name)
    if not isinstance(result, dict) or result.get('pass') is not True:
        raise SystemExit('Independent media endpoint failed: ' + name)
print('PASS: independent Firefox/native synthetic media, both endpoint results recorded')
PY
printf 'Interop result: %s/result.json\n' "$state"
