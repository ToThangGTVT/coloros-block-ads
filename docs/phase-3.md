# Phase 3 — SNI/HTTP và per-app bằng Rust

## File và thiết kế

- `nativecore/src/tcp/sniff.rs`: tối đa 4096 byte, deadline 750 ms; ghép TLS
  handshake qua nhiều record/TCP segment, đọc SNI hoặc HTTP/1 Host và URL đầu tiên.
  Không log payload. Unknown/timeout chuyển tiếp nguyên byte; server-first có thể
  bị trễ tới 750 ms. Parser lỗi không làm worker crash.
- `nativecore/src/tcp/reset.rs`, `tun/mod.rs`: theo dõi ACK metadata có giới hạn,
  trả in-window RST cho block TLS, HTTP trả 204. Chỉ mở protected upstream sau
  policy allow; phát lại nguyên byte đã sniff trước relay hai chiều.
- `nativecore/src/policy.rs`, JNI, `TrafficPolicyRepository`: getConnectionOwnerUid
  (API 29+, minSdk app 33) với TCP/UDP tuple gốc; UDP cache UID 5 giây / 2048 entry.
  Snapshot UID/package/policy hot reload, unknown owner vẫn lọc. DNS queries và
  connection event có UID, protocol và matched rule qua batch như Phase 2.
- `nativecore/src/dns/reverse.rs`: IP→domain theo UID, tôn trọng TTL; IP dùng chung
  bởi nhiều hostname trở thành ambiguous, không đoán. Chỉ dùng fallback khi SNI/
  Host không đọc được; không thay thế ECH hay giải mã DoH.
- `nativecore/src/filter`: giữ hash exact/suffix nhanh; thêm `$app=pkg|pkg2`,
  phủ định app với `~`, URL pattern `*`, `^`, `|`, `/regex/`. Regex Rust không
  backtracking; tối đa 256 URL/regex, 2048 ký tự/rule, program 128 KiB và DFA 32 KiB
  mỗi regex. Modifier chưa hỗ trợ bị ignored ở downloaded list, báo dòng ở custom.
- `nativecore/src/udp`: drop UDP/443 trước tạo socket, gộp event theo flow để tránh
  flood log. Chỉ drop cho app đang lọc HTTPS (đã chọn, có CA), trừ host
  proxy không giải mã (nhạy cảm, pinning); app khác giữ HTTP/3 vì chặn DNS vẫn áp
  dụng như với TCP. Tắt QUIC policy hoặc app bỏ qua lọc sẽ relay UDP bình thường.
- `core/data/AppsRepository`, SettingsRepository: apps có INTERNET permission,
  async icon LRU 8 MiB, theo dõi package add/remove/change. Ba mode DataStore;
  app chung UID dùng chung mode vì Android không tách được socket của từng package.
- `core/tunnel`: per-app filtering bypass hot reload; bypass tunnel gọi
  addDisallowedApplication và dựng lại VPN khi danh sách thay đổi. Đây là thay đổi
  routing nên ngắt flow hiện có. Có xử lý cả trường hợp tự loại BlockAds khỏi VPN.
- `feature/apps`, `feature/querylog`, `core/designsystem`: COUI Icon bitmap tint
  Unspecified được kiểm tra từ source 1.1.0; Apps search/system filter, log app icon
  + protocol + app filter; Paging stable key. Room migration 1→2 thêm protocol,
  giữ log và UID cũ. Không gắn nhầm UID khi Android không xác định được.

## Kiểm tra và kết quả

```sh
cargo fmt --manifest-path nativecore/Cargo.toml --check
cargo clippy --manifest-path nativecore/Cargo.toml --locked --all-targets -- -D warnings
cargo test --manifest-path nativecore/Cargo.toml --locked -- --test-threads=1
cargo bench --manifest-path nativecore/Cargo.toml --locked --bench matcher
scripts/build-native.sh
ANDROID_SERIAL=emulator-5554 scripts/test-phase1.sh -Pandroid.testInstrumentationRunnerArguments.externalNetworkChecks=true
./gradlew :core:data:testDebugUnitTest :app:lintDebug
```

- 14 native test qua, fmt/clippy qua, build bốn ABI. Thêm TLS fragmented/truncated,
  HTTP Host casing/duplicate, regex limits, scope/precedence, URL path không block
  opaque TLS, raw TUN hardcoded-IP SNI→RST không mở upstream, QUIC toggle/app bypass.
- Benchmark actual `Rules.matches` 500k hosts / 1M lookup: 0,159 µs, 58,9 MiB peak
  RSS trên Mac; compile khoảng 108 ms. Chưa phải số đo Android.
- Emulator API 37 arm64: 6/6 test qua, không skip. HTTP bytes được replay đúng,
  app UID đúng, SNI block trên IP trực tiếp, bypass filtering vẫn tăng native TCP
  counter, bypass tunnel đổi Android Network rồi quay lại lọc. Hồi quy DNS,
  DoH/DoT/DoQ, HTTPS, theme/predictive back, handover và 20 chu kỳ/fd đều qua.
- Theo yêu cầu mới đã cài/test CPH2825 Android 16/API 36 arm64 bằng ADB. Bốn test
  mục tiêu qua qua các lần chạy: SNI/HTTP/UID/app policies; DNS/cache/Room/UI;
  ba DNS mã hóa thực; HTTPS example.com qua VPN. Fixture LAN từ Mac; không đổi
  Wi-Fi/mobile, không cài CA. Test trả custom rules/policy/DNS và VPN về trạng thái
  trước (VPN tắt), mở lại app cho người dùng.
- Test UI trên máy thật cần tìm domain trước khi chọn row do lượng truy vấn nền
  lớn; sửa selector để chọn card đã chặn thay vì trường tìm kiếm cùng nội dung.
- CI build/native/lint trên Linux đã qua sau sửa package SDK 37.0. Run emulator
  API 35 đầu tiên không quay lại Settings bằng swipe và timeout chờ handover;
  đây có thể do cấu hình AVD. Script nay chủ động bật gesture,
  Wi-Fi/data trước test. Cần theo dõi run mới, chưa tuyên bố CI toàn bộ xanh.

Máy thật có thể chạy lại fixture với `BLOCKADS_FIXTURE_BIND=0.0.0.0`, cài hai APK,
cho phép VPN qua màn consent hệ thống, rồi `adb -s SERIAL shell am instrument -w`
với `-e fixtureHost IP_LAN_CUA_MAC` và các class PhaseThreeTrafficTest/PhaseTwoDnsTest.
Không chạy method 20-cycle/handover trên điện thoại đang dùng.

Nguồn đã đọc: Android ConnectivityManager.getConnectionOwnerUid, tài liệu regex
1.13.1/RegexBuilder, AdGuard network rule syntax; COUI release source Icon.kt.
https://developer.android.com/reference/android/net/ConnectivityManager#getConnectionOwnerUid(int,java.net.InetSocketAddress,java.net.InetSocketAddress)
https://docs.rs/regex/1.13.1/regex/struct.RegexBuilder.html
https://adguard.com/kb/general/ad-filtering/create-own-filters/

## Giới hạn còn lại

- HTTP path chỉ được xét ở request đầu kết nối; chưa parse toàn bộ HTTP keep-alive
  hay HTTP/2 plaintext. HTTPS chỉ thấy SNI, không thấy path. ECH, SNI quá giới hạn
  hoặc lưu lượng opaque chỉ dùng fallback IP nếu biết duy nhất domain.
- Bypass filtering không sniff TCP payload nên log có thể chỉ có IP; UID vẫn có.
  Android resolver/shared UID có thể chỉ cho system UID hoặc unknown; cache tuple
  ngắn hạn có giới hạn khi UDP port tái sử dụng giữa process.
- Đây là subset network syntax, không phải toàn bộ EasyList/AdGuard. Không hỗ trợ
  cosmetic, scriptlet, resource type/third-party/domain modifier, redirect/rewrite.
  URL/regex list bị giới hạn và đếm ignored để tránh tải regex vô hạn.
- QUIC drop có thể làm ứng dụng lọc HTTPS chỉ hỗ trợ QUIC không kết nối; người dùng có thể
  tắt policy hoặc bỏ qua lọc app đó. Không có plaintext DNS fallback tự ý.
- Vẫn chưa nghiệm thu 24 giờ/pin, all OEM Always-on hay HTTPS MITM Phase 5.
