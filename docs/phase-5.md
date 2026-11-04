# Phase 5 — Lọc HTTPS tùy chọn (local CA)

Bổ sung: [ẩn phần tử bằng CSS theo website](cosmetic-filtering.md), dùng lại
phạm vi ứng dụng và CA của bộ lọc HTTPS. Phần ghi nhận kiểm tra phía dưới là
lịch sử Phase 5; hướng dẫn mới và phạm vi cosmetic nằm trong tài liệu liên kết.

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
  là các trình duyệt phổ biến. HTTPS hiện áp dụng theo ứng dụng, không chọn domain.
  Phần mô tả domain dưới đây là thiết kế cũ và đã được thay thế. Domain chuẩn hóa IDN →
  ASCII, không nhận URL/IP/wildcard. Cấu hình JSON gửi native bị từ chối thì
  repository tự gửi `{}` (tắt) và báo lỗi, không crash process.
- `nativecore/src/mitm/mod.rs`: `eligible` yêu cầu enabled + UID có package nằm
  trong app đã chọn + không sensitive + có CA. Các trường domains/excluded cũ
  được bỏ qua khi đọc cấu hình để chuyển sang phạm vi theo ứng dụng. Danh sách sensitive cố định (ngân hàng/thanh toán/y tế,
  nhãn chứa `bank`/`pay`/`health`/…), luôn thắng cấu hình người dùng.
- `mitm/ca.rs`: leaf P-256 7 ngày, cache 128 domain/24 giờ, ALPN h2 + http/1.1.
- `mitm/proxy.rs`: upstream TLS verify WebPKI + hostname, socket protect trước
  connect. Proxy streaming HTTP/1.1 ↔ HTTP/2 theo cả 4 tổ hợp, chuẩn hóa version,
  bỏ hop-by-hop header, HTTP/2 upstream multiplex trên một socket. URL policy xét
  từng request bằng rule hiện hành (hot reload), gồm cú pháp mạng đầy đủ qua
  adblock-rust với loại request/bên thứ ba lấy từ Fetch Metadata
  ([chi tiết](https-wip.md#bộ-lọc-url-đầy-đủ-qua-adblock-rust)). Chỉ log hostname, không log
  path/query/body. Client ngắt kết nối (EOF/reset/broken pipe khi shutdown) được
  coi là kết thúc bình thường; lỗi giao thức khác vẫn trả về.
- `tcp/mod.rs`: chỉ intercept TLS port 443/8443 khi `eligible`; upstream không
  verify được → log `tls-bypass` và relay TLS nguyên trạng cho app.
- Upstream đóng keep-alive/HTTP/2 GOAWAY: mở lại kết nối đã protect và xác minh
  TLS; chuẩn hóa URI/version theo ALPN của kết nối mới. Chỉ thử gửi lại khi Hyper
  trả lại request chưa từng serialize, không phát lại request đã tới máy chủ.
  Giữ driver cũ để stream HTTP/2 đang chạy có thể hoàn tất.
- Thay đổi cấu hình HTTPS hoặc CA đóng các proxy đang mở (watch revision).
- `EngineBridge.supportsHttps()` dùng `nativeHttpsApiVersion`; `.so` cũ thiếu API
  không làm app crash, màn Chứng chỉ báo cần build lại native.
- UI: màn Chứng chỉ dạng wizard 3 bước (tạo CA → xuất .crt và cài qua Settings →
  chọn ứng dụng) và switch bật/tắt; Apps có switch “Xét lọc HTTPS” theo app.
- **Xóa CA đã lưu**: tắt HTTPS, gỡ signer/cache CA khỏi native (đóng các proxy
  HTTPS), xóa alias `blockads-local-ca-p256-v1` trong Android Keystore và file
  `root-ca.der`/`root-ca.tmp` nội bộ. Tạo lại sẽ tạo khóa và fingerprint mới.
  Các cập nhật native, tạo/xóa/refresh được serialize bằng cùng mutex để tránh
  cấu hình cũ bật lại sau khi xóa. Không tự gỡ CA đã cài trong AndroidCAStore
  hoặc các bản `.crt` đã xuất; UI có thông báo và hướng dẫn gỡ trong Settings.

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

Kết quả tiếp tục ngày 04/11/2026: xem [nhật ký sửa lỗi HTTPS](https-wip.md).
Đã sửa 502 do dùng lại upstream đã đóng, rồi chuyển chèn HTML sang tokenizer
streaming (buffer một thẻ 8 KiB, không giới hạn tổng head). HTTP/2 upstream bỏ
Host dư, dùng :authority; H1 dựng lại Host đúng cả khi reconnect đổi ALPN.
Chrome trên CPH2825 đã kiểm tra GenK, bài Tinhte có head >125 KiB, script Google
và bật/tắt HTTPS trên các kết nối đang mở. Các số liệu và giới hạn dưới
đây là biên bản Phase 5 ban đầu, trước lần kiểm tra này.

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
