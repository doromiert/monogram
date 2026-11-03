#!/usr/bin/env bash
# Build the Monocles fork from source; never substitute the standard Signal artifact.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
revision=10861bff671740174f4e99f7e30dc492fcf6ec8b
source_dir=${MONOGRAM_PQ_SOURCE:-"$root/.state/pq-omemo2-libsignal"}
output="$root/.state/pq/backend/$revision"
target=${1:-java}
case "$target" in java|host|arm64-v8a|armeabi-v7a|x86_64|x86) ;; *) echo 'Expected java, host or Android ABI' >&2; exit 2;; esac
if [[ ! -d "$source_dir/.git" ]]; then
    git clone --no-checkout https://codeberg.org/monocles/pq-omemo-2.git "$source_dir"
    git -C "$source_dir" checkout --detach "$revision"
fi
[[ $(git -C "$source_dir" rev-parse HEAD) == "$revision" ]] || { echo 'PQ source revision mismatch' >&2; exit 1; }
[[ -z $(git -C "$source_dir" status --porcelain --untracked-files=no) ]] || { echo 'PQ source has tracked modifications' >&2; exit 1; }
mkdir -p "$output"
cp "$source_dir/LICENSE" "$output/LICENSE-libsignal"
if [[ $target == java ]]; then
    # The unused Android plugin declaration needs to resolve under Monogram's Gradle 8.13.
    # Neither Android nor native source is changed by this build-only override.
    cat > "$output/java-init.gradle" <<'EOF'
settingsEvaluated { settings ->
    settings.pluginManagement.resolutionStrategy.eachPlugin {
        if (requested.id.id == 'com.android.library') useVersion('8.13.2')
        if (requested.id.id == 'org.jetbrains.kotlin.android') useVersion('2.2.20')
    }
}
EOF
    "$root/gradlew" -p "$source_dir/java" -I "$output/java-init.gradle" \
        -PskipAndroid -PskipDesktopJni --console=plain --max-workers=2 :client:jar
    cp "$source_dir/java/client/build/libs/libsignal-client-0.94.1.jar" "$output/libsignal-client.jar"
    printf '%s\n' "$revision" > "$output/source-revision"
    exit 0
fi
export CARGO_HOME=${CARGO_HOME:-"$root/.state/pq/cargo"}
export CARGO_TARGET_DIR=${CARGO_TARGET_DIR:-"$root/.state/pq/target"}
export CARGO_BUILD_JOBS=${CARGO_BUILD_JOBS:-4}
export RUSTFLAGS="--cfg aes_armv8 --cfg tokio_unstable ${RUSTFLAGS:-}"
args=()
if [[ $target != host ]]; then
    : "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to NDK 28.2.13676358}"
    grep -q 'Pkg.Revision = 28.2.13676358' "$ANDROID_NDK_HOME/source.properties"
    case "$(uname -s)" in Linux) host=linux-x86_64;; Darwin) host=darwin-x86_64;; *) exit 2;; esac
    toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$host/bin"
    # bindgen must discover Android headers, not the host compiler's libc include paths.
    export CLANG_PATH="$toolchain/clang"
    case "$target" in
        arm64-v8a) triple=aarch64-linux-android; cc=aarch64-linux-android23-clang;;
        armeabi-v7a) triple=armv7-linux-androideabi; cc=armv7a-linux-androideabi23-clang;;
        x86_64) triple=x86_64-linux-android; cc=x86_64-linux-android23-clang;;
        x86) triple=i686-linux-android; cc=i686-linux-android23-clang;;
    esac
    export "CC_${triple//-/_}=$toolchain/$cc"
    export "CXX_${triple//-/_}=$toolchain/${cc/clang/clang++}"
    export "CARGO_TARGET_$(printf %s "$triple" | tr '[:lower:]-' '[:upper:]_')_LINKER=$toolchain/$cc"
    export TARGET_AR="$toolchain/llvm-ar"
    export RUSTFLAGS="--cfg curve25519_dalek_bits=\"64\" $RUSTFLAGS"
    args=(--target "$triple")
fi
cargo build --manifest-path "$source_dir/Cargo.toml" -p libsignal-jni --locked --release "${args[@]}"
mkdir -p "$output/jni/$target"
if [[ $target == host ]]; then
    case "$(uname -s)" in Darwin) lib=libsignal_jni.dylib;; *) lib=libsignal_jni.so;; esac
    cp "$CARGO_TARGET_DIR/release/$lib" "$output/jni/host/$lib"
else
    cp "$CARGO_TARGET_DIR/$triple/release/libsignal_jni.so" "$output/jni/$target/libsignal_jni.so"
fi
printf '%s\n' "$revision" > "$output/source-revision"
