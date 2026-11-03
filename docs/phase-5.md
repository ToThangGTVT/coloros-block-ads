# Phase 5 — Lọc HTTPS tùy chọn (local CA)

## File và thiết kế

- `core/certificate/CertificateRepository`: root key EC P-256 tạo trong Android
  Keystore, alias `blockads-local-ca-p256-v1`, không export private key
  (`PrivateKey.encoded == null`). Native rcgen dựng TBS rồi gọi `RootCaSigner` qua
  JNI để Keystore ký `SHA256withECDSA`. Chỉ public DER lưu ở
  `files/certificate/root-ca.der` (ghi tạm rồi rename). Root 5 năm, `pathLen 0`.
- Trạng thái “đã cài” = SHA-256 của DER khớp một alias `user:` trong
  `AndroidCAStore`. Chưa cài/không đọc được → `setCa(null)`, MITM tắt. Kiểm tra lại
  khi `onResume`, khi start VPN và khi bấm “Kiểm tra trạng thái đã cài”.
- `core/common/HttpsSettings`, DataStore `https_local_settings`: mặc định tắt, app
  là các trình duyệt phổ biến, domain cho phép **rỗng**. Domain chuẩn hóa IDN →
  ASCII, không nhận URL/IP/wildcard. Cấu hình JSON gửi native bị từ chối thì
  repository tự gửi `{}` (tắt) và báo lỗi, không crash process.
- `nativecore/src/mitm/mod.rs`: `eligible` yêu cầu enabled + UID có package nằm
  trong app đã chọn + domain/subdomain được cho phép + không nằm trong excluded +
  không sensitive + có CA. Danh sách sensitive cố định (ngân hàng/thanh toán/y tế,
  nhãn chứa `bank`/`pay`/`health`/…), luôn thắng cấu hình người dùng.
- `mitm/ca.rs`: leaf P-256 7 ngày, cache 128 domain/24 giờ, ALPN h2 + http/1.1.
- `mitm/proxy.rs`: upstream TLS verify WebPKI + hostname, socket protect trước
  connect. Proxy streaming HTTP/1.1 ↔ HTTP/2 theo cả 4 tổ hợp, chuẩn hóa version,
  bỏ hop-by-hop header, HTTP/2 upstream multiplex trên một socket. URL policy xét
  từng request bằng rule hiện hành (hot reload). Chỉ log hostname, không log
  path/query/body. Client ngắt kết nối (EOF/reset/broken pipe khi shutdown) được
  coi là kết thúc bình thường; lỗi giao thức khác vẫn trả về.
- `tcp/mod.rs`: chỉ intercept TLS port 443/8443 khi `eligible`; upstream không
  verify được → log `tls-bypass` và relay TLS nguyên trạng cho app.
- Thay đổi cấu hình HTTPS hoặc CA đóng các proxy đang mở (watch revision).
- `EngineBridge.supportsHttps()` dùng `nativeHttpsApiVersion`; `.so` cũ thiếu API
  không làm app crash, màn Chứng chỉ báo cần build lại native.
- UI: màn Chứng chỉ dạng wizard 3 bước (tạo CA → xuất .crt và cài qua Settings →
  phạm vi domain/app) và switch bật; Apps có switch “Xét lọc HTTPS” theo app.

## Giới hạn đã biết

- Chỉ app tin user CA (thường là trình duyệt) mới lọc được. App pinning hoặc từ
  chối cert: handshake đó lỗi, UID+host được bỏ qua 6 giờ cho **lần kết nối kế
  tiếp**; không thể fallback trong chính handshake đã gửi cert giả.
- HTTP Upgrade (WebSocket) / CONNECT chưa hỗ trợ: trả 501 và bỏ qua host đó ở lần
  sau. Client HTTP/2 → upstream HTTP/1.1 xử lý tuần tự trên một kết nối upstream.
- Không giải mã QUIC/HTTP3 (QUIC vẫn theo chính sách chặn Phase 3), ECH, port khác
  443/8443.
- Android 11+ bắt buộc người dùng tự cài CA trong Settings; gỡ app không tự gỡ CA.

## Kiểm tra

```sh
cargo fmt --manifest-path nativecore/Cargo.toml --check
cargo clippy --manifest-path nativecore/Cargo.toml --locked --all-targets -- -D warnings
cargo test --manifest-path nativecore/Cargo.toml --locked -- --test-threads=1
scripts/build-native.sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.utc.color.blockads.PhaseFiveHttpsTest
```

- Native 19 test qua (`--test-threads=1` vì cấu hình MITM/CA là global), fmt và
  clippy `-D warnings` sạch. `tests/https.rs`: default-off/scope/sensitive/cache
  leaf/bypass sau client reject; upstream không tin cậy bị từ chối sau protect;
  4 tổ hợp HTTP/1.1↔HTTP/2 chặn path `||domain/ads/*` (kể cả port ≠ 443), không
  chạm upstream, body 256 KiB stream đủ, keep-alive một socket; 8 stream HTTP/2
  đồng thời chồng lấn trên một upstream socket.
- `scripts/build-native.sh` đủ 4 ABI, stamp xác minh mọi JNI export; APK debug chứa
  `nativeHttpsApiVersion/GenerateCa/SetCa/ConfigureHttps`. Debug, androidTest,
  release R8, lint (0 lỗi) và unit test `core:common`/`core:data` qua.
- Emulator API 37 arm64: 8/8 instrumentation test qua, không skip, gồm
  `PhaseFiveHttpsTest` (Keystore CA không export private key, chữ ký root xác minh
  bằng public key Keystore, `pathLen 0`, hạn ~5 năm, chưa cài CA thì không bật được
  HTTPS, tạo lại không đổi root) và toàn bộ regression Phase 0–4.
- Chưa tự động hóa: cài CA vào kho user (Android 11+ bắt buộc thao tác Settings),
  nên chặn URL trong Chrome và pinning bypass end-to-end chưa thử trên thiết bị;
  logic tương ứng được kiểm tra ở native host test. Không cài CA/bật MITM trên máy
  thật CPH2825.
- Ghi chú build: nếu APK debug crash `ClassNotFoundException BlockAdsApplication`,
  cache KSP debug bị hỏng (thiếu `Hilt_BlockAdsApplication`); xóa
  `app/build/kspCaches/debug` và `app/build/generated/ksp/debug` rồi build lại.
