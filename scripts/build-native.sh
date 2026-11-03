#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/28.2.13676358}"
case "$(uname -s)" in
    Darwin) NDK_HOST=darwin-x86_64 ;;
    Linux) NDK_HOST=linux-x86_64 ;;
    *) echo 'Use macOS or Linux to build Android native libraries.' >&2; exit 1 ;;
esac
NDK_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$NDK_HOST/bin"
command -v cargo >/dev/null || { echo 'Install Rust via rustup and add cargo to PATH.' >&2; exit 1; }
[[ -d "$NDK_BIN" ]] || { echo "Missing NDK toolchain: $NDK_BIN" >&2; exit 1; }
cd "$PROJECT_DIR"
for spec in \
    'aarch64-linux-android:arm64-v8a:aarch64-linux-android' \
    'armv7-linux-androideabi:armeabi-v7a:armv7a-linux-androideabi' \
    'x86_64-linux-android:x86_64:x86_64-linux-android' \
    'i686-linux-android:x86:i686-linux-android'; do
    IFS=: read -r rust_target android_abi clang_target <<< "$spec"
    linker_key="CARGO_TARGET_$(echo "$rust_target" | tr '[:lower:]-' '[:upper:]_')_LINKER"
    env "$linker_key=$NDK_BIN/${clang_target}33-clang" \
        RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384" \
        cargo build --manifest-path nativecore/Cargo.toml --locked --release --target "$rust_target"
    output="$PROJECT_DIR/core/engine-bridge/src/main/jniLibs/$android_abi"
    mkdir -p "$output"
    cp "nativecore/target/$rust_target/release/libblockads_core.so" "$output/"
done
