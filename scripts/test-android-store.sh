#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Run inside nix develop}"
device=${1:?Usage: test-android-store.sh ADB_DEVICE [APK]}
apk=${2:-TMessagesProj_App/build/outputs/apk/afat/debug/app.apk}
package=com.negzero.monogram.beta
mkdir -p .state
state=$(mktemp -d "$PWD/.state/store-test.XXXXXX")
remote=
upload="/data/local/tmp/monogram-store-test-${state##*.}.zip"
upload_apk="/data/local/tmp/monogram-store-test-${state##*.}.apk"
upload_cert="/data/local/tmp/monogram-store-test-${state##*.}.crt"
network_root="/data/local/tmp/monogram-transfer-test-${state##*.}"
xmpp_reverse=
https_reverse=
cleanup() {
    if [ -n "$remote" ]; then adb -s "$device" shell run-as "$package" rm -r "$remote" >/dev/null; fi
    adb -s "$device" shell rm -f "$upload" "$upload_apk" "$upload_cert" >/dev/null
    adb -s "$device" shell rm -rf "$network_root" >/dev/null
    if [ -n "$xmpp_reverse" ]; then adb -s "$device" reverse --remove "tcp:$xmpp_reverse"; fi
    if [ -n "$https_reverse" ]; then adb -s "$device" reverse --remove "tcp:$https_reverse"; fi
}
trap cleanup EXIT
classpath="$ANDROID_HOME/platforms/android-36/android.jar:TMessagesProj/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes:xmpp-core/build/classes/java/main"
while IFS= read -r jar; do classpath+=":$jar"; done < <(rg --files --hidden .m2/repository/org/igniterealtime .m2/repository/org/jxmpp | rg '\.jar$' | rg -v 'sources|javadoc')
mkdir -p "$state/classes"
mapfile -t sources < <(rg --files tests/android -g '*.java')
javac --release 8 -cp "$classpath" -d "$state/classes" "${sources[@]}"
mapfile -t classes < <(rg --files "$state/classes" -g '*.class')
"$ANDROID_HOME/build-tools/36.0.0/d8" --min-api 23 --lib "$ANDROID_HOME/platforms/android-36/android.jar" --classpath xmpp-core/build/classes/java/main --output "$state/tests.zip" "${classes[@]}"
adb -s "$device" push "$state/tests.zip" "$upload" >/dev/null
adb -s "$device" push "$apk" "$upload_apk" >/dev/null
remote=$(adb -s "$device" shell run-as "$package" mktemp -d cache/monogram-store-test.XXXXXX | tr -d '\r')
[[ "$remote" == cache/monogram-store-test.* ]] || { echo 'Invalid disposable test path' >&2; exit 1; }
adb -s "$device" shell run-as "$package" cp "$upload" "$remote/tests.zip"
adb -s "$device" shell run-as "$package" cp "$upload_apk" "$remote/app.apk"
# ART requires immutable code files. This process uses only the disposable test database.
adb -s "$device" shell run-as "$package" chmod 400 "$remote/tests.zip" "$remote/app.apk"
adb -s "$device" shell run-as "$package" sh -c "'CLASSPATH=$remote/tests.zip:$remote/app.apk app_process /system/bin org.telegram.messenger.xmpp.MonogramStoreDeviceTest $remote/databases'"
adb -s "$device" shell run-as "$package" sh -c "'CLASSPATH=$remote/tests.zip:$remote/app.apk app_process /system/bin org.telegram.messenger.xmpp.MonogramReplyStoreDeviceTest $remote/databases'"
adb -s "$device" shell run-as "$package" sh -c "'CLASSPATH=$remote/tests.zip:$remote/app.apk app_process /system/bin org.telegram.messenger.xmpp.MonogramCorrectionStoreDeviceTest $remote/databases'"
adb -s "$device" shell run-as "$package" sh -c "'CLASSPATH=$remote/tests.zip:$remote/app.apk app_process /system/bin org.telegram.messenger.xmpp.MonogramCaptionStoreDeviceTest $remote/databases'"

if [ -n "${MONOGRAM_IT_PORT:-}" ]; then
    adb -s "$device" reverse --no-rebind "tcp:$MONOGRAM_IT_PORT" "tcp:$MONOGRAM_IT_PORT"
    xmpp_reverse=$MONOGRAM_IT_PORT
    adb -s "$device" reverse --no-rebind "tcp:$MONOGRAM_IT_HTTPS_PORT" "tcp:$MONOGRAM_IT_HTTPS_PORT"
    https_reverse=$MONOGRAM_IT_HTTPS_PORT
    adb -s "$device" push "$MONOGRAM_IT_STATE/certs/localhost.crt" "$upload_cert" >/dev/null
    # Shell UID avoids changing the real app's lifecycle or background-network restrictions.
    adb -s "$device" shell chmod 400 "$upload" "$upload_apk"
    adb -s "$device" shell "CLASSPATH=$upload:$upload_apk app_process /system/bin org.telegram.messenger.xmpp.FileTransferDeviceTest $network_root $upload_cert $MONOGRAM_IT_PORT"
fi
