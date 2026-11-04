# Phát hành và cập nhật GitHub

Repo: https://github.com/ToThangGTVT/coloros-block-ads

## Phát hành APK

Workflow `.github/workflows/release.yml` chỉ tự build và phát hành khi push tag `vX.Y.Z`, hoặc chọn
**Actions → Release APK → Run workflow**. Tăng cả `versionCode` và `versionName`
trong `app/build.gradle.kts` trước mỗi bản mới; tag phải là `v` + `versionName`.
Push code thường lên các nhánh không kích hoạt workflow.
Không ghi đè release đã tồn tại; lần build cùng version chỉ lưu artifact và giữ release cũ.

Workflow kiểm tra Rust, build native cho bốn ABI, chạy unit test/lint và build APK
release có R8/resource shrink. APK được ký bằng khóa cố định từ bốn Actions secrets:

- `BLOCKADS_KEYSTORE_BASE64`: nội dung keystore mã hóa base64.
- `BLOCKADS_KEYSTORE_PASSWORD`: mật khẩu keystore.
- `BLOCKADS_KEY_ALIAS`: alias khóa.
- `BLOCKADS_KEY_PASSWORD`: mật khẩu khóa.

Khóa và mật khẩu không được commit. Trên máy phát triển đã thiết lập lần đầu,
bản sao riêng nằm trong `~/.config/blockads-release/` (quyền thư mục 700, file 600).
Cần sao lưu cả keystore và `signing.json` vào nơi an toàn; mất khóa sẽ không thể
phát hành bản cập nhật cùng chữ ký. Workflow xóa bản keystore tạm khi kết thúc.

Assets của mỗi release gồm `BlockAds-X.Y.Z-armeabi-v7a.apk`, `BlockAds-X.Y.Z-arm64-v8a.apk`, `SHA256SUMS.txt`, `update.json`.
Release được tạo dạng draft rồi mới công khai sau khi cả ba asset đã tải lên.
Chỉ phát hành ARMv7 và ARM64; không có APK x86/x86_64. App tự chọn APK theo ABI của máy. Artifact `blockads-release` cũng
được lưu trong lần chạy Actions.

## Cập nhật trong ứng dụng

Vào **Cài đặt → Cập nhật ứng dụng → Kiểm tra cập nhật GitHub**. App đọc bản stable
mới nhất qua GitHub API công khai; không chứa token. Chỉ đề nghị cập nhật khi
`versionCode` lớn hơn bản đang cài. Chọn tải rồi cài đặt; lần đầu Android sẽ hỏi
quyền cho phép BlockAds cài ứng dụng. Người dùng xác nhận trong trình cài đặt.

App giới hạn kích thước tải, kiểm tra SHA-256, package, versionCode và chữ ký APK
trước khi mở trình cài đặt. Chỉ chấp nhận asset HTTPS thuộc release của repo trên,
chỉ theo redirect HTTPS của GitHub. File tải dở bị xóa khi lỗi hoặc hủy. API 404
được hiểu là chưa có release; lỗi mạng/rate limit được hiển thị và có thể thử lại.

Bản debug dùng package `com.utc.color.blockads.dev`, cài song song và lưu dữ liệu
riêng với bản release `com.utc.color.blockads`. Bản debug không thể cập nhật trực
tiếp bằng APK release từ GitHub. Để chuyển cấu hình, xuất trong Cài đặt rồi nhập
vào bản release; các lần cập nhật release tiếp theo dùng cùng khóa và giữ dữ liệu app.

## Chạy nền, tường lửa và sheet

Android 16 trở lên có **Cài đặt → Thông báo → Thông báo dạng viên thuốc**.
Khi bật, thông báo VPN yêu cầu Live Updates và hiển thị trạng thái ngắn cùng icon
khiên trên status chip nếu Android/hãng máy cho phép. Công tắc áp dụng ngay khi VPN
đang chạy và được lưu trong cấu hình xuất/nhập. Thông báo không hiển thị các nút
thao tác lớn; bật/tắt viên thuốc trong Cài đặt, bật/tắt VPN trong Trang chủ.
Vuốt bỏ Live Updates sẽ tắt viên thuốc, không tắt VPN. Thông báo dịch vụ VPN
thông thường vẫn tồn tại. Hướng dẫn và giới hạn hiển thị nằm trong tooltip cạnh
tiêu đề Thông báo; khi quyền thông báo/Live Updates bị tắt, app có liên kết mở
cài đặt thông báo Android.

Khi đã kết nối, chip hiển thị `cho qua/đã chặn` trong hôm nay (cho qua = tổng
request trừ đã chặn), rút gọn K/M khi số lớn. Số liệu cập nhật theo thống kê VPN.
Android chỉ nhận chuỗi `String` cho nội dung chip và quyết định màu chữ; hai số
trong thẻ thông báo mở rộng dùng xanh lá/đỏ đất qua text spans của template chuẩn.
Live Updates mở rộng dùng `Notification.ProgressStyle`: thanh tỷ lệ cho qua ở
trái (xanh lá), đã chặn ở phải (đỏ đất), kèm phần trăm chặn và số liệu hai nhóm.
Chưa có request thì thanh xám trung tính; tỷ lệ được tính từ thống kê hôm nay.

Tích hợp theo [Android Live Updates](https://developer.android.com/develop/ui/views/notifications/live-update).
Android quyết định vị trí và việc nâng thông báo thành chip; app không ép vị trí
ở giữa màn hình và không tạo overlay giả lập.

**Cài đặt → Ưu tiên chạy ngầm** mở yêu cầu miễn tối ưu hóa pin của Android. Công tắc
hiển thị trạng thái thực từ PowerManager và được cập nhật khi quay lại ứng dụng.
Tắt công tắc mở danh sách tối ưu hóa pin để thu hồi miễn trừ trong hệ thống.

Tab **Tường lửa** cho phép bật/tắt mạng từng ứng dụng, tìm theo tên/package và hiện
app hệ thống. Mặc định mọi app có mạng; quy tắc chỉ áp dụng khi VPN BlockAds bật.
Chặn cả TCP/UDP, DNS, QUIC và IPv4/IPv6, kể cả IP hardcode. Quy tắc theo UID Android,
vì vậy các package dùng chung UID dùng chung quyết định chặn. Tường lửa ưu tiên hơn
bỏ qua lọc/bỏ qua tunnel, nhưng không xóa cấu hình lọc đã lưu. Chính sách được lưu
bằng DataStore, có trong xuất/nhập JSON. Thay đổi danh sách UID bị chặn đóng các
relay hiện có; ứng dụng đang dùng mạng có thể cần kết nối lại. Nếu thay đổi phạm vi
bỏ qua tunnel, VPN được thiết lập lại để cập nhật định tuyến Android.

App dựa vào `getConnectionOwnerUid` để nhận diện lưu lượng; kết nối không được
Android gán UID vẫn theo chính sách lọc mặc định. Tường lửa không quản lý các
profile/người dùng khác hoặc khi VPN đã dừng. Có thể dùng VPN luôn bật của Android
để tăng tính liên tục của bảo vệ.

Root COUI Scaffold bao cả nội dung lẫn thanh tab. Bottom sheet và bảng lựa chọn
render lên root này nên phủ và nhận chạm phía trên thanh điều hướng dưới.

## Build local

Gradle tự chạy `scripts/build-native.sh` khi fingerprint nguồn/binary JNI chưa
khớp, sau đó kiểm tra lại cả bốn ABI và JNI exports. Android Studio không cần
chạy script native thủ công trước mỗi lần sửa Rust. Vẫn cần JDK 25, SDK 37,
NDK 28.2.13676358, Python 3 và rustup theo `rust-toolchain.toml`.

Để ký release local, đặt `BLOCKADS_KEYSTORE_PATH`, `BLOCKADS_KEYSTORE_PASSWORD`,
`BLOCKADS_KEY_ALIAS`, `BLOCKADS_KEY_PASSWORD` rồi chạy:

```sh
./gradlew --no-configuration-cache -Pblockads.splitApks=true :app:assembleRelease
python3 scripts/prepare-release.py
```

Nếu không có các biến ký, build local release vẫn tạo APK unsigned; workflow
phát hành yêu cầu đủ secrets và kiểm tra chữ ký bằng `apksigner`.

## Hiệu năng giao diện 1.0.0

Danh sách app và nhật ký dùng key/content type ổn định, icon từ cache, ánh xạ UID
được tính một lần. Nhật ký giữ tối đa bốn truy vấn phân trang trong cache, tối đa
300 dòng mỗi truy vấn; tìm kiếm đợi 200 ms sau khi nhập để giảm truy vấn liên tục.
Thống kê được chia sẻ qua StateFlow và giới hạn cập nhật biểu đồ mỗi giây.
Cài đặt, bộ lọc và danh mục dùng các item lazy độc lập. Bảng chọn cũng dùng LazyColumn,
bao gồm trường hợp 1.000 lựa chọn; không dựng cả danh sách khi mở sheet. Chuyển tab
sử dụng fade 140 ms, tránh di chuyển hai lớp nội dung có backdrop cùng lúc.
Mục cập nhật chỉ tạo vùng thông báo khi thực sự có nội dung, tránh khoảng trống
thừa giữa hai hàng. Không có số phần trăm cải thiện hiệu năng được công bố khi
chưa đo trên thiết bị đích.

## 1.0.3 — Giảm tác vụ nền

- TCP chờ tín hiệu khi không có hạn giờ cần xử lý, bỏ idle polling 5 ms.
- Gom log/thống kê mỗi 5 giây thay vì 0,5 giây; đủ 100 sự kiện log vẫn đẩy sớm.
- Tắt VPN bình thường đẩy dữ liệu còn chờ; lọc/chặn lưu lượng vẫn diễn ra ngay.
- Thư viện netstack-smoltcp được lưu trong repo để bản sửa có mặt ở mọi bản build.
- Đã kiểm tra 27 test Rust, Clippy và build debug; chưa đo mức tiết kiệm pin trên máy thật.

## 1.0.4 — Giảm chi phí đường đi của packet

- MTU của tunnel tăng từ 1500 lên 9000 nên số packet qua TUN giảm; kernel vẫn chia gói theo đường mạng thật cho socket upstream.
- Thread tunnel gắn vào ART một lần thay vì gắn và gỡ ở mỗi lần xác định app sở hữu kết nối, bảo vệ socket hoặc gửi log.
- Đã kiểm tra 28 test Rust, Clippy, test lifecycle trên máy giả lập có CheckJNI và UDP tới 8.000 byte; chưa đo mức tiết kiệm pin trên máy thật.

## 1.1.0 — Lọc quảng cáo trong trình duyệt (không phát hành)

Tag `v1.1.0` không có release: workflow dừng ở một test kết nối lại có điều kiện tranh chấp thời gian. Nội dung dưới đây phát hành trong 1.1.1.

- Proxy HTTPS dùng engine `adblock` của Brave (MPL-2.0) cho quy tắc mạng có ngữ cảnh request, ẩn phần tử (kể cả quy tắc chung theo class/id và bộ lọc procedural), scriptlet `##+js` và `$redirect` từ AdGuard Scriptlets tải lúc chạy.
- Style/script được chèn ngay sau `<head>`; trang gzip/deflate/br được giải nén trong proxy thay vì ép `identity`.
- DNS bỏ ECH khỏi bản ghi HTTPS/SVCB cho ứng dụng được lọc HTTPS, để proxy biết đúng tên miền.
- Khi mạng thật không có IPv6: AAAA trả rỗng, bỏ `ipv6hint`, kết nối IPv6 bị RST để ứng dụng dùng IPv4. Trước đây Chrome bỏ qua lọc với các host Google/Cloudflare.
- Danh sách DNS và bộ lọc trình duyệt tách riêng; bộ lọc trình duyệt không chặn DNS. Thêm danh mục bộ lọc của tiện ích AdGuard (7 nhóm).
- Tải danh sách không còn bị hủy khi rời màn hình.
- Đã kiểm tra test Rust (tuần tự), Clippy, build/lint debug và release; 16 danh sách AdGuard thật biên dịch được. Chưa chạy test instrumentation trên máy thật.

## 1.1.1 — Phát hành nội dung của 1.1.0

- Gồm toàn bộ thay đổi của 1.1.0 ở trên.
- Test kết nối lại chấp nhận việc proxy đóng kết nối upstream ngay sau khi đã nhận đủ phản hồi `Connection: close`; trước đây test lỗi ngẫu nhiên trên máy chạy CI.

## 1.1.2 — Chỉ chặn QUIC cho app lọc HTTPS

- **Chặn QUIC** giờ chỉ drop UDP/443 của app đang lọc HTTPS (đã chọn, có CA), để chúng dùng TCP qua proxy. App khác như YouTube, Instagram giữ HTTP/3; chặn quảng cáo của chúng dựa vào DNS nên không đổi.
- Với app lọc HTTPS, host proxy không giải mã (nhạy cảm, pinning) cũng giữ QUIC khi biết được tên miền.
- Đã kiểm tra test Rust (tuần tự), Clippy và fmt. Chưa thử trên máy thật.
