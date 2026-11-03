# Phát hành và cập nhật GitHub

Repo: https://github.com/ToThangGTVT/coloros-block-ads

## Phát hành APK

Workflow `.github/workflows/release.yml` chạy khi push tag `vX.Y.Z`, hoặc chọn
**Actions → Release APK → Run workflow**. Tăng cả `versionCode` và `versionName`
trong `app/build.gradle.kts` trước mỗi bản mới; tag phải là `v` + `versionName`.
Không ghi đè release đã tồn tại.

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

Bản debug ký bằng debug key không thể cập nhật trực tiếp sang bản release ký bằng
release key. Khi chuyển lần đầu, xuất cấu hình trong Cài đặt rồi cài bản release
phù hợp; các lần cập nhật release tiếp theo dùng cùng khóa và giữ dữ liệu app.

## Chạy nền, tường lửa và sheet

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
