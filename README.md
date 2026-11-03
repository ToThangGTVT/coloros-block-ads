# BlockAds

Ứng dụng Android chặn quảng cáo/tracker bằng full-tunnel VPN, triển khai theo từng
phase. **Phase 2: full-tunnel TCP/UDP, lọc DNS, cache TTL và query log.**

- Android-only: Kotlin, Compose, Hilt, Coroutines/Flow, Room, DataStore, WorkManager.
- UI: COUI **1.1.0**, không dùng component hiển thị Material 3.
- Native core: **Rust + JNI**, theo yêu cầu cập nhật; không dùng Go/gomobile.
- minSdk **33** theo lựa chọn của người dùng để dùng coui-blur; compile/target **37**.

## Build

Cần JDK 25 (Gradle daemon), Android SDK 37, build tools 36.0.0, NDK 28.2.13676358 và
Rust qua rustup. `rust-toolchain.toml` pin Rust 1.99.1 cùng bốn target Android.
`local.properties` chứa đường dẫn SDK riêng máy và không được commit.

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk" # Linux: đặt theo SDK của máy
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
scripts/build-native.sh
./gradlew :app:assembleDebug
```

Script tạo `libblockads_core.so` cho arm64-v8a, armeabi-v7a, x86_64 và x86, link cho
API 33 và page alignment 16 KB. Các binary được đóng gói qua `core/engine-bridge`,
không commit binary. Build Gradle sẽ báo rõ nếu chưa chạy script native.

```sh
cargo fmt --manifest-path nativecore/Cargo.toml --check
cargo clippy --manifest-path nativecore/Cargo.toml --locked --all-targets -- -D warnings
cargo test --manifest-path nativecore/Cargo.toml --locked
./gradlew :core:data:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
```

## Trạng thái hiện tại

Bật VPN ở Home để lọc mọi truy vấn DNS UDP/TCP port 53, kể cả DNS hardcode.
StevenBlack hosts được tải bằng HTTPS; Filters có bật/tắt list, thêm URL và editor
custom rules có lỗi theo dòng. Hỗ trợ hosts, `||domain^`, `@@||domain^`, `$important`.
Query log dùng Room/Paging 3, tìm domain, lọc trạng thái và chặn/cho phép nhanh.
Settings có DNS hệ thống, Cloudflare/Quad9/AdGuard, DoH/DoT/DoQ và tắt ghi log.
Mọi socket upstream đều protect trước khi kết nối, TLS kiểm tra hostname/chứng chỉ.
Không có plaintext fallback tự động khi upstream mã hóa không dùng được.

SNI/HTTP, UID/per-app, QUIC policy và thống kê đầy đủ sẽ có ở các phase tiếp theo.
HTTPS MITM mặc định tắt và chưa được triển khai ở Phase 2.

Android 17 / target 37 cần quyền ACCESS_LOCAL_NETWORK để chuyển tiếp LAN. App xin
quyền khi bật VPN; từ chối vẫn cho phép dùng Internet, nhưng LAN bị nền tảng chặn.

Chạy test emulator (chỉ dùng emulator, cần Wi-Fi và dữ liệu di động mô phỏng bật):

```sh
ANDROID_SERIAL=emulator-5554 scripts/test-phase1.sh
# Thêm kiểm tra HTTPS công khai, cần Internet:
ANDROID_SERIAL=emulator-5554 scripts/test-phase1.sh -Pandroid.testInstrumentationRunnerArguments.externalNetworkChecks=true
```

Xem [biên bản Phase 2](docs/phase-2.md), [biên bản Phase 1](docs/phase-1.md), [lịch sử Phase 0](docs/phase-0.md),
[nguồn API COUI](docs/coui-api.md) và [license](THIRD_PARTY_LICENSES.md).
Tiếp tục các phase sau theo chỉ đạo mới của người dùng; mỗi phase có commit và biên bản riêng.
