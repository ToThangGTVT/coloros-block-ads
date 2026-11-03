# Phase 1 — Full tunnel thông suốt bằng Rust

Phase này đưa TCP/UDP qua VpnService và userspace stack. Chưa chặn quảng cáo,
chưa đọc SNI/HTTP và chưa giải mã TLS. Theo chỉ đạo mới, tiếp tục Phase 2 sau khi
kiểm tra/commit Phase 1.

## Thay đổi và thiết kế

- `nativecore/src/tun`: sở hữu TUN fd được Kotlin detach đúng một lần; fd được đóng
  cả khi khởi tạo lỗi. Worker Tokio riêng, nonblocking I/O, bounded channels,
  shutdown hủy và join toàn bộ task trước khi thread kết thúc.
- `nativecore/src/sockets`: điểm tạo duy nhất của mọi socket outbound. Gọi protect
  trước TCP connect, UDP bind/connect/send; callback lỗi hoặc trả false thì fail
  closed và drop fd. Không có SOCKS/remote VPN server trung gian.
- `nativecore/src/tcp`: smoltcp TCP stream ↔ protected socket, relay hai chiều,
  half-close, giới hạn 256 relay; connect timeout 15s. Relay hiện có giới hạn
  thời gian tổng 2 giờ; sẽ thay bằng idle policy khi hoàn thiện vòng đời sản phẩm.
- `nativecore/src/udp`: NAT theo (client, destination), tối đa 1024 flow, hàng đợi
  32 datagram/flow, idle timeout 120s, socket connected để kiểm tra nguồn reply.
  Tự dựng reply packet/checksum qua etherparse vì upstream writer bỏ qua UDP rỗng.
- `nativecore/src/lib.rs`, `core/engine-bridge`: JNI start/stop/network-update,
  health và counter metadata. Giữ GlobalRef callback trong lifetime engine;
  worker attach/detach JVM khi gọi protect. Không callback JNI theo packet.
- `core/tunnel`: VpnService, notification có nút tắt, state Flow, network monitor,
  permission/revoke/error handling; supports always-on và START_STICKY.
- `app`, `feature/home`: công tắc VPN thật, xin consent hệ thống, trạng thái kết
  nối thực; Settings giữ COUI/DataStore. Không báo đã bảo vệ/chặn ở phase này.
- `scripts/phase1-test-server.py`, `scripts/test-phase1.sh`: fixture HTTP/UDP và
  test emulator có thể chạy lại. Script từ chối serial điện thoại thật.
- CI có native fmt/clippy/tests, build APK/lint và job emulator API 35 x86_64;
  chưa chạy GitHub Actions do project chưa có remote.

## Cấu hình tunnel

MTU 1500; địa chỉ 172.19.0.1/30 và fdfe:dcba:9876::1/126; route 0.0.0.0/0 và ::/0;
DNS ảo 172.19.0.2 và fdfe:dcba:9876::2. DNS ảo được chuyển tới DNS do mạng nền cấp,
giữ nguyên endpoint ảo trên reply. DNS tới đích khác vẫn relay nguyên trạng.
Không có block QUIC ở Phase 1.

Native socket được VpnService.protect qua callback Kotlin, sau đó theo route
mạng vật lý mặc định của Android. Không ép bind vào cellular network nền bị
netd giới hạn quyền (EPERM) trong thời điểm handover. Monitor chỉ chọn network NOT_VPN, ưu tiên mạng đã validated và Wi-Fi.
Khi đổi mạng, setUnderlyingNetworks và reset upstream; giữ nguyên TUN/stack.
Có barrier chờ cả TCP/UDP relay đã hủy flow cũ trước khi công bố network-ready.
Mất mạng giữ TUN, hiển thị đang chờ mạng, từ chối mở socket chưa có mạng nền.

Service chỉ báo Connected sau khi native sẵn sàng và Android công bố default VPN
route. Lệnh start/stop/network được xử lý tuần tự; revoke/stop ngăn socket mới,
đóng native, hủy callback mạng rồi kết thúc foreground service. Health check phát
hiện native thoát ngoài dự kiến và cho phép bật lại.

## Android API 37

Target 37 giữ nguyên. Android 17 yêu cầu ACCESS_LOCAL_NETWORK để app kết nối LAN;
thiếu quyền có thể làm TCP timeout. App xin quyền khi người dùng bật VPN. Từ chối
vẫn chạy Internet, nhưng Android chặn LAN. Runtime POST_NOTIFICATIONS được xin
khi nhận VPN consent. Service khai báo systemExempted/BIND_VPN_SERVICE.

Nguồn API đã kiểm tra:

- https://developer.android.com/privacy-and-security/local-network-permission
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/reference/android/net/VpnService
- https://developer.android.com/reference/android/net/VpnService.Builder
- netstack-smoltcp 0.2.4 sources: https://github.com/cavivie/netstack-smoltcp

## Kiểm tra

```sh
cargo fmt --manifest-path nativecore/Cargo.toml --check
cargo clippy --manifest-path nativecore/Cargo.toml --locked --all-targets -- -D warnings
cargo test --manifest-path nativecore/Cargo.toml --locked -- --test-threads=1
scripts/build-native.sh
ANDROID_SERIAL=emulator-5554 scripts/test-phase1.sh
# Thêm HTTPS công khai, cần Internet:
ANDROID_SERIAL=emulator-5554 scripts/test-phase1.sh -Pandroid.testInstrumentationRunnerArguments.externalNetworkChecks=true
./gradlew :core:data:testDebugUnitTest :app:lintDebug
```

Native tests: protect bị từ chối không thể connect/send; thứ tự protect trước
connect; TCP half-close; TCP handshake/data đi qua raw TUN; UDP IPv4/IPv6,
datagram rỗng, tái sử dụng NAT; DNS endpoint mapping; malformed packet; 20 chu kỳ
start/stop kiểm tra fd đã đóng và tổng fd không tăng.

Instrumentation: HTTP 1 MB, UDP echo, DNS qua endpoint ảo IPv4/IPv6, counter chứng
minh traffic qua native thay vì bypass; Wi-Fi ↔ mobile mô phỏng, 20 chu kỳ có
traffic và đo fd; native exit/restart; HTTPS với trust hệ thống; UI theme/back.
HTTP fixture đóng Connection rõ ràng để test không đo nhầm pool socket của client
vào fd của tunnel. Báo cáo thực tế ở app/build/reports/androidTests và native logs.

## Giới hạn

- Chưa có lọc DNS/cache, filter list, SNI, UID, per-app policy, nhật ký request
  hoặc HTTPS filtering. Đây là phạm vi các phase sau.
- Chưa relay ICMP hay multicast. IP fragmentation chưa được reassemble; malformed
  hoặc fragmented packet bị bỏ qua để không làm dừng UDP worker.
- Per-app tunnel exclusion sẽ nối với repository Apps ở Phase 3; hiện mọi app
  nằm trong tunnel, gồm chính BlockAds, nên test kiểm tra được socket protection.
- Mạng đổi làm các flow cũ đóng; app phải retry. Không hứa giữ TCP session xuyên
  Wi-Fi/cellular khi địa chỉ nguồn thay đổi.
- Chưa đo pin/24h/throughput trên thiết bị thật; upstream có idle polling 5ms.
  Cần nghiệm thu thêm video/YouTube dài và OEM Always-on trên điện thoại thật.
- Không log nội dung payload. Upstream tracing bị tắt ở compile time. Không có
  analytics, upload traffic hoặc thay đổi trust store.

## Kết quả chạy tại máy

- 5 test native qua; clippy và fmt qua. Build đủ bốn ABI.
- Emulator API 37 arm64: 3 test instrumentation qua (UI/theme/back, public HTTPS,
  lifecycle/traffic/handover/recovery). Wi-Fi/cellular phục hồi, 20 vòng có traffic
  không tăng fd; một lần đo baseline 130 và sau 20 vòng 127.
- Room/DataStore unit test và lint qua (còn cảnh báo nâng version/deprecation).
- Lỗi phát hiện khi test: quyền LAN API 37; công bố Connected quá sớm; handover
  cần barrier hủy flow cũ; không được ép bind socket vào cellular network nền
  bị netd giới hạn. Các điểm này đã được sửa và chạy lại cả bộ test.
