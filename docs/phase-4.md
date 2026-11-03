# Phase 4 — Điều khiển sản phẩm và background work

## File và thiết kế

- `core/data`: Room migration 2→3 thêm daily_stats; counts tổng hợp riêng với query
  metadata. Native JSON envelope gửi events + totals theo phút; quy về ngày theo
  timezone thiết bị. Tắt log vẫn đếm tổng/blocked, không gửi domain mới qua JNI.
  Stats không backfill từ log cũ. Top domain lấy từ log còn giữ.
- `nativecore/src/dns`: batch totals không có hostname/UID khi tắt log; giới hạn
  writer channel như trước. `tcp`: thay giới hạn thời gian tổng 2 giờ bằng idle
  timeout 2 giờ, cập nhật activity trên cả đọc/ghi để không cắt stream đang chạy.
- `feature/home`, `core/designsystem/TrafficChart.kt`: switch lớn căn giữa,
  today total/blocked/%, top domain và stacked bar 7 ngày. MPAndroidChart v4.0.1
  Compose được chọn theo yêu cầu mới, đọc đúng source tag trước khi dùng; bọc ở
  design system, màu primary/error/onSurface, typeface, chữ và radius từ COUI.
  Theme được cập nhật trong `update`; dữ liệu remember theo snapshot. Có mô tả
  accessibility và tắt chart gesture để không giành thao tác scroll của Home.
- `core/data/SettingsRepository`, `core/filter`: custom HTTPS/DoT endpoint và IP
  bootstrap literal; không resolve bootstrap hostname để tránh loop. Kiểm tra
  scheme, port, credentials, path, IP; IPv6 được canonicalize. Presets AdGuard
  Base/EasyList/EasyPrivacy/ABPVN thêm ở trạng thái tắt; chỉ StevenBlack mặc định bật.
  Nêu rõ network subset, đếm rule không hỗ trợ, không quảng cáo full EasyList.
- Filter download gửi If-None-Match/If-Modified-Since; 304 giữ file, 200 validate và
  atomic rename; tối đa 20 MB/list, 30 list và 32 MB source đang bật. Lỗi giữ rule
  cũ, hiển thị trong Filters. Hot reload không restart TUN.
- `app/MaintenanceWorker`, AppWorkerFactory và Application custom WorkManager:
  unique periodic update 24h khi có mạng; exponential retry. Cleanup retention
  chạy riêng, không cần mạng. Settings chọn 1/7/30 ngày hoặc xóa query log.
- `core/tunnel/BootReceiver`: auto-start opt-in sau BOOT_COMPLETED khi đã có VPN
  consent; Always-on do người dùng bật trong Settings hệ thống. IPv6 off vẫn giữ
  route trong VPN và drop IPv6, không cho IPv6 đi ngoài tunnel. Private DNS được
  đọc từ LinkProperties mạng nền, Home hiển thị hướng dẫn mở cài đặt mạng.
- `feature/settings`: coui-preference cho các mục Bảo vệ/DNS/Giao diện/Dữ liệu/
  Giới thiệu. Export/import qua Storage Access Framework, schema JSON 1, giới hạn
  2 MB. Validate toàn bộ và custom rules trước atomic DataStore edit. Imported
  filter IDs/metadata được làm sạch, không trỏ cached file sang URL khác. File
  không chứa query log hoặc khóa/chứng chỉ CA. Notification cập nhật counts 5s.
- `baselineprofile`, `app/src/nonMinifiedRelease`: generator seed 5.000 log mẫu ở
  variant dành riêng profiling. Activity seed không có trong APK debug/release.
  Baseline đã thu thật, lọc owner app/COUI/Navigation3/chart; AndroidX tự cung cấp
  profile của thư viện. Release bật R8/resource shrink và đóng gói baseline.

## Kiểm tra

```sh
cargo fmt --manifest-path nativecore/Cargo.toml --check
cargo clippy --manifest-path nativecore/Cargo.toml --locked --all-targets -- -D warnings
cargo test --manifest-path nativecore/Cargo.toml --locked -- --test-threads=1
scripts/build-native.sh
./gradlew :core:common:testDebugUnitTest :core:data:testDebugUnitTest :app:lintDebug :app:assembleRelease
ANDROID_SERIAL=emulator-5554 scripts/test-phase1.sh -Pandroid.testInstrumentationRunnerArguments.externalNetworkChecks=true
ANDROID_SERIAL=emulator-5554 ./gradlew :app:generateReleaseBaselineProfile
```

- Native 15 test qua, gồm aggregate counts khi logging off và không lộ hostname
  trong batch. DNS endpoint unit tests kiểm tra URL/query/port/IP sai và IPv6.
- Bộ 7 instrumentation test trên emulator API 37 đã qua, không skip: config
  roundtrip/invalid atomic/no-log stats/WorkManager, SNI/app policy, DNS/cache/log,
  ba encrypted upstream, UI/theme/back, HTTPS và lifecycle/20 cycle/handover.
- CPH2825 API 36: backend Phase 4 configuration/no-log stats qua. Các test backend
  Phase 2/3 và public HTTPS đã qua ở phase trước. Sau khi người dùng cho biết đang
  dùng máy, dừng toàn bộ UI automation trên điện thoại; không thay cấu hình mới
  người dùng đang chỉnh. Theme/chart tiếp tục kiểm tra trên emulator.
- Baseline Profile thu được từ Home và paged Query log 5.000 dòng. Bản lọc có
  3.877 dòng quy tắc quan sát thực. Chưa công bố phần trăm tăng tốc hoặc FPS.
- APK release R8/resource shrink, profile compile và lint đã build thành công.
  Cần theo dõi CI API 35; run Phase 3 còn lỗi timing/network/UI. Có thêm logcat
  test-only trong artifact để chẩn đoán; không coi emulator API 37 là bằng chứng
  cho mọi API/OEM.

## Nghiệm thu còn thiếu

Chưa chạy đủ 24 giờ hoặc đo Battery Historian ở điều kiện rút sạc. USB/charging
và chạy instrumentation không đại diện pin thực tế. Không tuyên bố mức tiêu thụ
pin chấp nhận được khi chưa có dữ liệu; đây là phần nghiệm thu thực địa còn mở.
Upstream netstack-smoltcp vẫn có idle polling fallback 5 ms; cần đo trên thiết bị
trước khi quyết định tối ưu/thay đổi stack. Reboot/OEM background restrictions và
video dài/throughput cần thử thêm. IPv6 off chủ yếu chặn packet mới; kết nối đang
có có thể nhận phần reply đã in-flight. Private DNS của app dùng riêng/ECH vẫn
có giới hạn như Phase 3. HTTPS filtering chưa bật hay tạo CA tự động.

Nguồn API: Android WorkManager custom configuration, LinkProperties Private DNS,
Android Baseline Profiles; source MPAndroidChart tag v4.0.1, Charts.kt,
ComposeInterop.kt, BarDataSet/BarEntry/BarChartRenderer. License và NOTICE đã lưu.
