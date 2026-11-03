# Khắc phục APK Kotlin/Rust không đồng bộ

Điện thoại CPH2825 báo crash ở APK 0.5.0: UnsatisfiedLinkError tại nativeSetCa.
Kotlin đã có API CA mới nhưng APK chứa .so Phase 4, do Gradle trước đây chỉ kiểm
tra file .so có tồn tại mà không kiểm tra nguồn hay JNI exports.

Đã khôi phục APK 0.4.1/versionCode 7 từ commit Phase 4 ở nhánh
`hotfix/native-package-recovery`; cài bằng adb install -r, không xóa dữ liệu.
Test backend NativePackagingSmokeTest xác nhận core 0.4.0-phase4 và Application
khởi tạo thành công. Không dùng UI automation trên điện thoại vì người dùng đang
sử dụng máy. Không tạo/cài CA hoặc bật HTTPS trên điện thoại.

Sửa quy trình build:

- scripts/native-stamp.py băm nội dung nguồn Rust, Cargo.lock/toolchain, script
  build và Kotlin JNI bridge; xác minh mọi JNI export bằng llvm-nm cho cả 4 ABI.
- scripts/build-native.sh chỉ ghi stamp khi nguồn không thay đổi trong lúc build
  và tất cả binary đã đủ symbol; stamp có hash của từng binary.
- Gradle verifyNativeCore kiểm tra stamp, nguồn và từng binary trước preBuild.
  Binary thiếu/cũ không thể được đóng vào APK. Cần chạy build-native.sh sau khi
  thay đổi Rust hoặc JNI API; source/binary mismatch báo lỗi rõ trước packaging.
- APK WIP bị lỗi đã chuyển ra khỏi output app-debug.apk để tránh cài lại nhầm.

Bản 0.5.0 tiếp theo dùng versionCode 8, chỉ triển khai sau khi build native mới,
kiểm tra symbol của .so trong APK và chạy test emulator. Điện thoại giữ bản ổn
định và cấu hình người dùng đang chỉnh.
