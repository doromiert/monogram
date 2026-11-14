#!/usr/bin/env bash
# Run the installed APK's real Smack initialization in a sterile process; no app UI/accounts/sockets.
set -euo pipefail
smack_state=.state/smack-smoke
smack_sdk=${ANDROID_HOME:?Set ANDROID_HOME to an Android SDK with platform36/build-tools35.0.0}
smack_keystore=${MONOGRAM_ANDROID_KEYSTORE:?Set MONOGRAM_ANDROID_KEYSTORE to the local debug keystore}
mkdir -p "$smack_state/classes" "$smack_state/dex"
javac --release 8 -cp "$smack_sdk/platforms/android-36/android.jar" -d "$smack_state/classes" tests/android/smack-smoke/SmackSmokeInstrumentation.java
find "$smack_state/classes" -name '*.class' -print0 | xargs -0 "$smack_sdk/build-tools/35.0.0/d8" --lib "$smack_sdk/platforms/android-36/android.jar" --min-api 23 --output "$smack_state/dex"
"$smack_sdk/build-tools/35.0.0/aapt2" link -o "$smack_state/unsigned.apk" --manifest tests/android/smack-smoke/AndroidManifest.xml -I "$smack_sdk/platforms/android-36/android.jar"
python3 - <<'PY2'
import zipfile
with zipfile.ZipFile('.state/smack-smoke/unsigned.apk','a') as z:z.write('.state/smack-smoke/dex/classes.dex','classes.dex')
PY2
"$smack_sdk/build-tools/35.0.0/apksigner" sign --ks "$smack_keystore" --ks-pass pass:android --ks-key-alias androiddebugkey --out "$smack_state/fixture.apk" "$smack_state/unsigned.apk"
adb install -r "$smack_state/fixture.apk"
adb shell am instrument -w "$@" com.negzero.monogram.smacksmoke/com.negzero.monogram.smacksmoke.SmackSmokeInstrumentation
