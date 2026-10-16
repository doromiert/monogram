#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../TMessagesProj/jni/prebuild"
: "${ANDROID_HOME:?Run inside nix develop}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
export CMAKE_BIN="$ANDROID_HOME/cmake/3.22.1/bin/cmake"
export NINJA_BIN
NINJA_BIN=$(command -v ninja)
case "${1:-arm64-v8a}" in
    arm64|arm64-v8a) export ABIS=arm64-v8a ;;
    arm|armeabi-v7a) export ABIS=armeabi-v7a ;;
    x86|x86_64) export ABIS="$1" ;;
    all) export ABIS='arm64-v8a armeabi-v7a x86_64 x86' ;;
    *) echo 'Expected arm64-v8a, armeabi-v7a, x86, x86_64 or all' >&2; exit 1 ;;
esac
exec ./build_all.sh
