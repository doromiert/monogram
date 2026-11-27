#!/usr/bin/env bash
# Native rendering regression; always use a disposable emulator, never a user's phone.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Run inside nix develop}"
device=${1:?Usage: test-android-link-previews.sh emulator-SERIAL APK}
apk=${2:?Usage: test-android-link-previews.sh emulator-SERIAL APK}
case "$device" in emulator-*) ;; *) echo 'Use a disposable emulator.' >&2; exit 1 ;; esac
key="${ANDROID_USER_HOME:-$HOME/.android}/debug.keystore"
[[ -f "$key" ]] || { echo 'Established debug signing key is unavailable.' >&2; exit 1; }
state=$(mktemp -d /tmp/monogram-preview-rendering.XXXXXX)
trap 'rm -rf "$state"' EXIT
export HOME="$state/home"
mkdir -p "$HOME" "$state/classes"
tools="$ANDROID_HOME/build-tools/36.0.0"
main=TMessagesProj/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes
javac -cp "$ANDROID_HOME/platforms/android-36/android.jar:$main" -d "$state/classes" tests/android/org/telegram/ui/Cells/LinkPreviewRenderingTest.java
mapfile -t classes < <(find "$state/classes" -name '*.class')
"$tools/d8" --min-api 23 --lib "$ANDROID_HOME/platforms/android-36/android.jar" --classpath "$main" --output "$state/tests.zip" "${classes[@]}"
cat > "$state/AndroidManifest.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.negzero.monogram.previewtests"><uses-sdk android:minSdkVersion="23" android:targetSdkVersion="36"/><application android:debuggable="true"/><instrumentation android:name="org.telegram.ui.Cells.LinkPreviewRenderingTest" android:targetPackage="com.negzero.monogram.beta"/></manifest>
XML
"$tools/aapt2" link -I "$ANDROID_HOME/platforms/android-36/android.jar" --manifest "$state/AndroidManifest.xml" -o "$state/unsigned.apk"
python3 - "$state" <<'PY'
import sys,zipfile
from pathlib import Path
root=Path(sys.argv[1])
with zipfile.ZipFile(root/'tests.zip') as src,zipfile.ZipFile(root/'unsigned.apk','a') as dest:
    for name in src.namelist():dest.writestr(name,src.read(name))
PY
"$tools/apksigner" sign --ks "$key" --ks-pass pass:android --key-pass pass:android --out "$state/test.apk" "$state/unsigned.apk"
adb -s "$device" install --no-streaming -r "$apk"
adb -s "$device" install --no-streaming -r "$state/test.apk"
adb -s "$device" shell am instrument -w -r com.negzero.monogram.previewtests/org.telegram.ui.Cells.LinkPreviewRenderingTest | tee "$state/result"
grep -q 'PASS: 8 plain/rich mixed-link previews' "$state/result"
grep -q 'INSTRUMENTATION_CODE: -1' "$state/result"
