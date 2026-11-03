# Phase 2 — Lọc DNS bằng Rust

## Thiết kế và file

- `nativecore/src/filter`: matcher immutable exact/suffix, compile ngoài worker,
  hot swap Arc. Custom allow > custom block > important > exception > block.
  Hosts exact; `||domain^` bao gồm subdomain; unsupported/cosmetic đếm ignored.
  Custom sai cú pháp báo dòng và giữ nguyên bộ rule đang hoạt động.
- `nativecore/src/dns/{mod,upstream,cache}.rs`: intercept mọi port 53 UDP/TCP,
  block A/AAAA bằng zero hoặc NXDOMAIN, full-message upstream, TTL cache dương/âm,
  giữ SOA và transaction ID. Cache tối đa 4096 entry / 8 MB wire estimate; không
  cache TTL 0, truncated, signed hoặc SERVFAIL. Giảm TTL khi hit, xóa khi restart,
  đổi mạng, đổi upstream hoặc thay rule.
- Hickory 0.26.3 thay dnsproxy Go vì người dùng đã chọn native Rust. RuntimeProvider
  riêng cho TCP/UDP/QUIC bắt buộc protect trước connect/bind/send. UDP có TCP
  fallback; DoH/DoT/DoQ dùng rustls/ring và WebPKI, không bỏ qua TLS verification.
- `core/filter/FilterRepository`: HTTPS download giới hạn 20 MB, timeout, atomic
  file rename; SHA-256 chống compile lặp, giữ rule cũ khi cập nhật lỗi.
- `core/data`: DataStore lists/custom/DNS, bounded metadata batch writer vào Room,
  Paging 3 page 50, key theo ID, tìm domain/lọc trạng thái, giữ log 7 ngày.
- `feature/filters`, `feature/querylog`, `feature/settings`, `core/designsystem`:
  UI COUI thật; TextField, OverlayBottomSheet lấy API từ source 1.1.0. Log dùng
  finite LazyColumn thay vì lồng list có chiều cao vô hạn. Không dùng M3 component.
- `core/tunnel`, `core/engine-bridge`: Hilt inject repository, JNI validate/reload,
  callback batch mỗi 500 ms/100 event; không callback từng packet. Notification
  hiển thị số rule thực. Tắt log xóa event đang đợi, không xóa log lịch sử.

## Cách kiểm tra

```sh
cargo fmt --manifest-path nativecore/Cargo.toml --check
cargo clippy --manifest-path nativecore/Cargo.toml --locked --all-targets -- -D warnings
cargo test --manifest-path nativecore/Cargo.toml --locked -- --test-threads=1
cargo bench --manifest-path nativecore/Cargo.toml --locked --bench matcher
scripts/build-native.sh
ANDROID_SERIAL=emulator-5554 scripts/test-phase1.sh -Pandroid.testInstrumentationRunnerArguments.externalNetworkChecks=true
./gradlew :core:data:testDebugUnitTest :app:lintDebug
```

Fixture có HTTP 18080, UDP echo 18081, DNS 18082. Chỉ chạy integration script trên
emulator. Test chặn A/AAAA UDP/TCP/hardcoded DNS/IPv6, exception, hot reload không
restart VPN, lỗi editor giữ rule cũ, positive TTL/expiry, NXDOMAIN, Room logging
và tắt log. Native test thêm negative SOA cache, protect denied không mở socket.
Public test gửi DNS qua DoH Cloudflare, DoT Cloudflare và DoQ AdGuard; cần Internet.
Có cả hồi quy Phase 1: HTTPS, handover Wi-Fi/cellular, 20 vòng start/stop, fd/recovery.

## Kết quả và giới hạn

- CI lần đầu phát hiện setup-android v3 cố cài package `tools` đã bị gỡ; workflow
  đã chỉ định `platform-tools`. Theo dõi lại run sau push.
- Native 10 test qua; fmt/clippy qua. Build bốn ABI Android.
- Emulator API 37 arm64: 5 test qua, không skip, gồm ba upstream mã hóa thực và
  hồi quy tunnel. Báo cáo ở `app/build/reports/androidTests/connected/debug/`.
- Benchmark Mac 500.000 hosts / 1.000.000 lookup: khoảng 100,8 ms compile,
  0,126 µs/lookup và peak RSS 58,7 MiB. Đạt mục tiêu host; chưa phải số đo Android.
- Chưa lọc DoH/DoT riêng của app, TLS SNI, HTTP URL, QUIC hay gắn UID (Phase 3).
  DNSSEC không được app tự validate; giữ nội dung upstream, AD bị bỏ trên block.
- Chưa có ETag/WorkManager/preset EasyList đầy đủ, custom encrypted upstream,
  retention tùy chọn, app icons/filter và Baseline Profile (Phase 3/4).
- Download ban đầu cần mạng; khi lỗi app hiển thị lỗi và VPN vẫn có thể hoạt động
  với rule đã lưu. Không báo bảo vệ khi chưa có rule. Không gửi payload/log ra ngoài.
- Chưa nghiệm thu 24 giờ, pin/throughput trên điện thoại thật hay video dài.
