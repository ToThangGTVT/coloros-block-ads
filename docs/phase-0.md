# Phase 0 — Khung Android/COUI và native Rust

Phạm vi nghiệm thu: app chạy, hiển thị demo COUI và gọi native trả version.
Dừng tại Phase 0 theo đặc tả; chưa triển khai VPN hoặc lọc mạng.

## Quyết định đã thống nhất

- COUI 1.1.0 qua đúng năm tọa độ KMP trong version catalog; Android-only.
- minSdk **33**, theo lựa chọn của người dùng sau khi xác minh coui-blur yêu cầu 33.
- Native **Rust + JNI**, theo yêu cầu cập nhật. Không còn module Go/gomobile.
- Giữ AGP 9.5.0/Gradle 9.6.0 và compile/targetSdk 37 của project ban đầu.
- Kotlin/Compose compiler 2.4.10 đồng bộ với bản COUI được phát hành; dùng Kotlin
  tích hợp của AGP 9. Không dùng cờ bỏ qua Kotlin metadata compatibility.
- Rust 1.99.1, NDK 28.2.13676358, JNI crate 0.21.1 được pin; Cargo.lock được commit.
- Mọi display component đi qua core/designsystem; feature không import Material
  hoặc COUI trực tiếp. Compose foundation chỉ dùng để bố trí nội dung.

## File/module đã tạo hoặc sửa

| Nhóm | Nội dung |
| --- | --- |
| `settings.gradle.kts`, Gradle các module, version catalog | app + 6 core + 6 feature modules, Hilt/KSP/Compose/Room/DataStore/WorkManager/Paging |
| `core/designsystem` | AppTheme bọc COUITheme/ThemeController, wrapper card/switch/text/preference, top bar co giãn, hai bar blur, inset handling, COUI NavDisplay |
| `core/common` | Model ThemeMode độc lập UI |
| `core/data` | DataStore theme, Room metadata schema v1, stable ID, PagingSource sắp timestamp/id, insert batch và retention query |
| `core/engine-bridge` | Load libblockads_core, JNI nativeVersion, keep rules và kiểm tra đủ binary trước build |
| `nativecore` | Rust cdylib, JNI trả version thật, ranh giới tun/dns/tcp/udp/filter/mitm cho phase sau |
| `scripts/build-native.sh` | Cross-compile bốn ABI, API 33, ELF page alignment 16 KB |
| `app` | Hilt Application/DI/ViewModel, lifecycle-aware Flow, edge-to-edge, system bar contrast theo theme, Navigation3 state restoration |
| `feature/home`, `feature/settings` | Demo COUI thật và picker bốn theme lưu vào DataStore |
| Các feature còn lại | Màn giải thích phase dự kiến, Apps/Certificate mở từ Settings |
| `.github/workflows/build.yml` | Rust fmt/clippy/test, native build, APK, instrumentation APK, unit test và lint |
| `THIRD_PARTY_LICENSES.md`, `licenses/` | License review và inventory dependency Rust từ lockfile |

## Luồng chạy

MainViewModel gọi EngineBridge.version trên Dispatchers.IO. EngineBridge load
libblockads_core.so và gọi JNI nativeVersion. Chuỗi `0.0.1-phase0` được tạo trong
Rust từ Cargo package version và hiển thị ở Home; không dùng version giả ở Kotlin.

Theme được đọc bằng Flow từ DataStore, collect theo lifecycle. Settings dùng
ListPreference của COUI. Giá trị không còn hợp lệ được fallback về System;
lỗi ghi được phản hồi trên UI. Monet giữ keyColor 0xFF3482FF như snippet yêu cầu,
nên đây là palette Monet theo seed, không phải màu wallpaper.

Navigation3 runtime dùng với implementation NavDisplay của COUI, không thêm
navigation3-ui thứ hai. Home là root, back từ tab khác về Home; Apps/Certificate
được push trên Settings. Back stack và state demo được lưu qua saved state.
Scaffold giữ overlay host cho ListPreference. COUI tự xử lý inset dọc của hai bar,
page chỉ áp dụng content padding và inset ngang một lần.

Không có permission INTERNET, VpnService, CA hoặc native packet handler ở Phase 0.
Backup/transfer dữ liệu app bị tắt; không có analytics hoặc upload query log.

## Cách build và kiểm tra

Lệnh đầy đủ trong README.md. Cần Rust/rustup trên PATH và SDK/NDK theo cấu hình.

```sh
scripts/build-native.sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :core:data:testDebugUnitTest :app:lintDebug
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
```

Kiểm tra thủ công:

1. Mở Home: thấy card, switch và preference; version native là `0.0.1-phase0`.
2. Thử hai switch demo: chúng chỉ đổi trạng thái minh họa, không bật VPN.
3. Settings → Chủ đề: chọn sáng/tối/Monet/theo hệ thống; đóng/mở app kiểm tra lưu.
4. Settings → Apps/Certificate, thử vuốt back và xoay màn hình/tạo lại Activity.
5. Cuộn Home để xem top bar co giãn; nội dung không bị che bởi status/navigation bar.

## Kết quả xác minh

- Build native đủ arm64-v8a, armeabi-v7a, x86_64 và x86.
- ELF LOAD alignment của arm64 đã kiểm tra là 0x4000 (16 KB).
- APK chứa libblockads_core cho đủ bốn ABI; không chứa thư viện Go.
- Rust fmt/clippy qua. Cargo test build qua, hiện 0 unit case vì native chưa có
  thuật toán mạng. Hàm JNI thực được xác minh bằng instrumentation.
- Unit test DataStore qua: mặc định, ghi Monet, đóng/mở store giữ giá trị,
  giá trị không hợp lệ fallback System.
- Instrumentation trên emulator API 37/arm64: gọi JNI thật, bốn lựa chọn theme,
  navigation Apps, khôi phục Activity và vuốt back về Settings.
- Đã xem ảnh sáng/tối/Monet: không bị cắt chữ, nội dung không bị hai bar che,
  icon status bar đổi tương phản theo theme. Ảnh kiểm tra cục bộ ở
  `build/review/blockads-phase0/theme-{0,1,2,3}.png` (không commit build output).
- Lint không có lỗi; còn cảnh báo phiên bản dependency mới và API test Compose
  cũ được đánh dấu deprecated. Không nâng dependency COUI/Navigation3 tùy tiện.
- Workflow CI đã cấu hình; chưa có remote để chạy GitHub Actions.

## Giới hạn và công việc phase sau

- **Chưa bảo vệ/chặn request nào.** Home hiện là demo, các thống kê không được giả lập.
- Room/Paging mới là nền tảng; chưa có producer event, truy vấn filter hay log UI.
- WorkManager được khai báo ở module filter; chưa schedule công việc tải list.
- VpnService, protect socket, netstack, đổi mạng, DNS, UID attribution, QUIC và
  rule matcher triển khai theo các phase 1–3. Phải chọn/review thư viện Rust cho
  mạng và license trước khi tích hợp; không áp dụng thư viện Go vào Rust core.
- Home/Log/Filters/Apps/Settings đầy đủ và Baseline Profile Home/Query log thuộc
  Phase 4; không tạo baseline từ màn placeholder.
- CA wizard và HTTPS interception chỉ Phase 5, opt-in. Chưa có CA hoặc MITM.
- Chưa chạy trên điện thoại thật, chưa kiểm tra runtime ba ABI còn lại, chưa có
  phép đo pin, 24h stability hoặc performance benchmark filter.
- Target API 37 cần recheck quy định foreground-service/VPN ở phase triển khai
  service; Phase 0 không đưa manifest service giả vào app.

Nguồn API và các điểm khác biệt của COUI được ghi trong docs/coui-api.md.
