# WIP: HTTPS cosmetic filtering — 2026-11-04

Chưa hoàn tất. Người dùng yêu cầu lưu/push để tiếp tục sau. Không phát hành bản
này như bản HTTPS đã hoạt động ổn trên máy thật.

## Đã thay đổi

- Thêm cosmetic CSS qua proxy HTTPS, cache theo host, giới hạn CSS 64 KiB/host,
  tối đa 128 host; không có scriptlet hoặc quét DOM bằng JavaScript.
- Hỗ trợ selector CSS đơn giản, quy tắc theo domain, generic và ngoại lệ
  (ví dụ ABPVN `~cafef.vn##[id^="adm-slot"]`).
- Giữ nguyên meta Content-Type UTF-8 và x-dns-prefetch-control thông thường;
  trước đây bộ chèn bỏ qua mọi meta http-equiv, bao gồm các meta của GenK.
- HTTPS bật/tắt bằng công tắc và áp dụng theo ứng dụng đã chọn. Đã bỏ phần chọn
  domain khỏi UI/model/native eligibility; bỏ qua các trường domains/excluded
  trong cấu hình cũ. Vẫn bỏ qua domain ngân hàng/thanh toán/y tế tự động.
- Xóa CA: tắt HTTPS, gỡ signer/cache native, xóa alias Android Keystore và
  root-ca.der/root-ca.tmp nội bộ; không tự gỡ chứng chỉ trong kho tin cậy Android.
  Có bước xác nhận trong UI. Serialize cập nhật native và vòng đời CA để tránh race.
- Hiện các danh sách lọc có sẵn trước khi tải, để người dùng có thể bật ABPVN.
- Bổ sung sự kiện `https-error` với chuỗi lỗi upstream. Chưa cài bản có chẩn đoán
  này lên máy thật tại thời điểm dừng.
- Relay TLS nguyên trạng của ứng dụng đã chọn sẽ đóng khi thay cấu hình HTTPS,
  để lần kết nối sau áp dụng công tắc mới. Thay đổi này đã build, chưa test máy thật.

## Test máy thật và lỗi còn lại

Đã cài đè bản debug `com.utc.color.blockads.dev`, giữ nguyên dữ liệu và CA.
Máy có cả bản release và debug, VPN đang dùng bản debug trong quá trình test.
CA đã được Android xác nhận cài và khớp. Chrome có package com.android.chrome,
phiên bản 158.0.8127.2. Bật chặn QUIC và tải ABPVN (729 quy tắc được hỗ trợ).

Với HTTPS tắt, phép đo DevTools trên https://m.genk.vn/:

- HTTP 200, chứng chỉ GlobalSign, không có style[data-blockads].
- div#adm-slot-7269 có display:block và cao 210 CSS px.

Với HTTPS bật, ban đầu Chrome dùng lại kết nối TLS cũ nên chưa bị giải mã.
Sau khi có kết nối mới, chứng chỉ đã là **BlockAds Local CA**, nhưng trang bị
**HTTP 502** do proxy. Đã gặp trên trang chủ và đường dẫn bài viết:

```text
https://m.genk.vn/may-tinh-anh-em-dinh-mua-se-dat-them-bao-nhieu-trong-nam-toi-3-kich-ban-cho-3-tam-gia-165261104151528916.chn
```

Người dùng đã báo lỗi cả trang. Chưa xác định nguyên nhân gốc của 502. Không
được mô tả việc mất quảng cáo trên trang lỗi là chặn thành công.

Phép thử live trên host bằng chính proxy Rust, có User-Agent Android Chrome:

- Trang chủ trả HTTP 200, có CSS data-blockads và cache-control:no-store.
- Bài viết trả HTTP 200 nhưng chưa được chèn CSS (body khoảng 493 KiB);
  cần xác định điều kiện head/parser/giới hạn nào làm bộ chèn bỏ qua bài viết.
- Không User-Agent mobile thì GenK chuyển hướng 301 về genk.vn.

`nativecore/tests/genk_live.rs` là probe tạm, chỉ chạy bằng --ignored. Nó include
https.rs để dùng helper nên còn chạy trùng các test thường nếu chạy cả file;
cần tách helper hoặc dọn probe trước khi hoàn thiện PR. Đây là test dùng mạng
thật và không nên tự chạy trong CI thông thường.

## Kiểm tra đã chạy

- 36 test Rust workspace đã qua sau thay đổi phạm vi ứng dụng/meta/generic CSS,
  trước bổ sung log lỗi upstream và đóng relay khi đổi cấu hình HTTPS.
- Clippy workspace/all-targets -D warnings đã qua sau các thay đổi cuối.
- Build debug, build Android instrumentation APK, Android lint và unit test
  core:common/core:data đã qua ở bản phạm vi ứng dụng/CSS.
- Build debug cuối (có log https-error và relay revision) đã thành công.
- Instrumentation test xóa CA mới chỉ biên dịch, chưa chạy trên thiết bị.
  Không xóa/tạo lại CA trên máy thật khi debug lỗi Chrome.
- Chưa đo CPU/pin thực tế và chưa xác nhận lọc HTTPS hoạt động ổn trên điện thoại.

## Tiếp tục

1. Build/cài đè APK debug mới nhất, giữ nguyên dữ liệu và CA đã cài.
2. Kiểm tra VPN debug, ABPVN đã tải, QUIC bật; bật HTTPS cho Chrome.
3. Tải lại đúng trang chủ/bài viết GenK và đọc `https-error` trong nhật ký để lấy
   lỗi gốc của 502. So sánh với live probe host; không suy đoán thành công từ UI.
4. Sửa proxy, thêm regression test theo lỗi thực tế; kiểm tra request/response
   HTTP/1.1 và HTTP/2, cả trang chủ và bài viết.
5. Kiểm tra bật/tắt ngay khi Chrome còn kết nối: tắt phải dừng MITM, DNS vẫn lọc;
   bật phải áp dụng cho ứng dụng được chọn mà không cần cấu hình domain.
6. Xác minh DOM có style[data-blockads], ô quảng cáo height=0/display:none,
   nội dung/bài viết vẫn tải được. Kiểm tra lại với cache/service worker bình thường.

Các file .idea đã staged trước phiên làm việc là thay đổi riêng có sẵn của người
dùng; không nằm trong commit WIP này. Không commit log/ảnh hoặc dữ liệu điện thoại.
