# Build và kiểm tra

Cần JDK 25 (Gradle daemon), Android SDK 37, build tools 36.0.0, NDK 28.2.13676358 và
Rust qua rustup. `rust-toolchain.toml` pin Rust 1.99.1 cùng bốn target Android.
`local.properties` chứa đường dẫn SDK riêng máy và không được commit.

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk" # Linux: đặt theo SDK của máy
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
scripts/build-native.sh
./gradlew :app:assembleDebug
```

Bản debug dùng application ID `com.utc.color.blockads.dev` để cài song song với
bản phát hành. Bản release (kể cả GitHub Actions) giữ `com.utc.color.blockads`.

Gradle tự chạy script native khi nguồn hoặc binary chưa khớp. Script tạo
`libblockads_core.so` cho arm64-v8a, armeabi-v7a, x86_64 và x86, link cho
API 33 và page alignment 16 KB. Các binary được đóng gói qua `core/engine-bridge`,
không commit binary. Xem [phát hành, khóa ký và cập nhật](releases.md).

```sh
cargo fmt --manifest-path nativecore/Cargo.toml --all --check
cargo clippy --manifest-path nativecore/Cargo.toml --locked --workspace --all-targets -- -D warnings
cargo test --manifest-path nativecore/Cargo.toml --locked --workspace
./gradlew :core:data:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
```
