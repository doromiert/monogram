#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
abi=${1:-arm64-v8a}
case "$abi" in arm64-v8a) triple=aarch64-linux-android; cc=aarch64-linux-android23-clang;; armeabi-v7a) triple=armv7-linux-androideabi; cc=armv7a-linux-androideabi23-clang;; x86_64) triple=x86_64-linux-android; cc=x86_64-linux-android23-clang;; x86) triple=i686-linux-android; cc=i686-linux-android23-clang;; *) exit 2;; esac
ndk=${ANDROID_NDK_HOME:-"$ANDROID_HOME/ndk/27.2.12479018"}
case "$(uname -s)" in Darwin) host=darwin-x86_64;; *) host=linux-x86_64;; esac
toolchain="$ndk/toolchains/llvm/prebuilt/$host/bin"
export "CC_${triple//-/_}=$toolchain/$cc"
export "CARGO_TARGET_$(printf %s "$triple" | tr '[:lower:]-' '[:upper:]_')_LINKER=$toolchain/$cc"
export TARGET_AR="$toolchain/llvm-ar"
export CARGO_TARGET_DIR="$root/.state/lan-sync/target"
cargo build --locked --release --manifest-path "$root/sync-core/Cargo.toml" --features android-jni --target "$triple"
mkdir -p "$root/.state/lan-sync/jni/$abi"
cp "$CARGO_TARGET_DIR/$triple/release/libmonogram_sync.so" "$root/.state/lan-sync/jni/$abi/"
