#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../TMessagesProj/jni"
: "${ANDROID_HOME:?Run inside nix develop}"
export NDK="$ANDROID_HOME/ndk/21.4.7075529"
export NINJA_PATH
NINJA_PATH=$(command -v ninja)
export PATH="$ANDROID_HOME/cmake/3.10.2.4988404/bin:$PATH"
abi=${1:-arm64}
case "$abi" in arm64|arm|x86|x86_64) ;; *) echo 'Expected arm64, arm, x86 or x86_64' >&2; exit 1 ;; esac
for module in boringssl ffmpeg libvpx libwebp; do
    test -f "$module/.git" || { echo 'Initialize git submodules first' >&2; exit 1; }
done
apply_once() {
    local directory=$1 patch_file=$2
    if patch --dry-run --reverse -d "$directory" -p1 < "$patch_file" >/dev/null 2>&1; then return; fi
    patch --batch -d "$directory" -p1 < "$patch_file"
}
apply_once boringssl patches/boringssl/0001-add-aes-ige-mode.patch
apply_once boringssl patches/boringssl/0001-only-build-what-we-need.patch
./build_libvpx_clang.sh "$abi"
./build_ffmpeg_clang.sh "$abi"
# Upstream's header-copy script also installs private headers in the ABI include directories.
apply_once ffmpeg patches/ffmpeg/0001-compilation-magic.patch
apply_once ffmpeg patches/ffmpeg/0002-compilation-magic-2.patch
for arch in arm64-v8a armeabi-v7a x86 x86_64; do
    for header in libavformat/dv.h libavformat/isom.h libavcodec/bytestream.h libavcodec/get_bits.h libavcodec/golomb.h libavutil/intmath.h; do
        install -D "ffmpeg/$header" "ffmpeg/build/$arch/include/$header"
    done
done
./build_boringssl.sh "$abi"
