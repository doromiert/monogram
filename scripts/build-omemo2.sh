#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${LIBOMEMO_C_SOURCE:?Run inside nix develop}"
: "${PROTOBUF_C_SOURCE:?Run inside nix develop}"
mode=${1:-host}
args=()
case "$mode" in
    host) ;;
    arm64-v8a|armeabi-v7a|x86|x86_64)
        : "${ANDROID_HOME:?Use the Android Nix shell}"
        args+=("-DCMAKE_TOOLCHAIN_FILE=$ANDROID_HOME/ndk/27.2.12479018/build/cmake/android.toolchain.cmake"
            "-DANDROID_ABI=$mode" -DANDROID_PLATFORM=android-23 -DANDROID_STL=c++_static) ;;
    *) echo 'Expected host, arm64-v8a, armeabi-v7a, x86 or x86_64' >&2; exit 1 ;;
esac
cmake -S xmpp-core/src/main/c -B ".state/omemo2-$mode" -G Ninja -DCMAKE_BUILD_TYPE=RelWithDebInfo "${args[@]}"
cmake --build ".state/omemo2-$mode" --parallel 4
