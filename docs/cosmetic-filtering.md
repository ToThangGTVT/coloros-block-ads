# Ẩn phần tử quảng cáo trong Chrome

BlockAds có thể chèn CSS vào trang HTML qua bộ lọc HTTPS cục bộ. CSS ẩn phần tử
và thường thu gọn chỗ của phần tử đó; không xóa node khỏi DOM. Tính năng không
cần extension hoặc root, nhưng cần trình duyệt tin chứng chỉ CA của BlockAds.

## Bật và sử dụng

1. Vào **Cài đặt → Lọc HTTPS**, tạo CA, xuất và cài chứng chỉ CA trong Android
   Settings, rồi kiểm tra lại trạng thái đã cài.
2. Chọn Chrome trong phần **Chọn ứng dụng**, rồi bật **Bật lọc HTTPS**.
   HTTPS áp dụng cho mọi website của ứng dụng đã chọn; không cần nhập domain.
   Tắt công tắc sẽ dừng giải mã HTTPS, bộ lọc DNS vẫn hoạt động.
3. Bật danh sách có CSS, chẳng hạn **ABPVN · Tiếng Việt**, và cập nhật bộ lọc.
   Hoặc vào **Bộ lọc → Quy tắc riêng**, nhập quy tắc theo phần tử thực tế của trang:

   ```text
   example.com##.banner-ad
   example.com###sponsored-box
   example.com##div > .advertisement
   example.com##[id^="ad-slot-"]
   ```

   `example.com` là ví dụ; thay bằng website của bạn. Có thể thêm URL HTTPS của
   danh sách có quy tắc cosmetic trong mục thêm danh sách lọc. Các danh sách chỉ
   chứa tên miền DNS không cung cấp CSS ẩn phần tử.
4. Bật VPN, giữ **Cài đặt → Chặn QUIC** bật để Chrome dùng HTTPS qua TCP.
   Tải lại trang. Nội dung có sẵn trong cache/service worker có thể cần xóa cache
   của website để tải lại qua proxy.

Ngoại lệ và phạm vi:

```text
example.com,~account.example.com##.banner-ad
news.example.com#@#.banner-ad
```

Dòng đầu áp dụng cho example.com và tên miền con, trừ account.example.com cùng
các tên miền con của nó. Dòng thứ hai bỏ quy tắc có selector `.banner-ad` cho
news.example.com và tên miền con. Ngoại lệ thắng quy tắc ẩn có selector trùng
khớp; đây không phải bộ phân tích đầy đủ mọi biến thể cú pháp Adblock Plus.

## Phạm vi và giới hạn

- Hỗ trợ CSS ASCII đơn giản: tag, class, ID, thuộc tính, danh sách selector và
  tổ hợp descendant/`>`/`+`/`~`. Không hỗ trợ pseudo-class, CSS escape,
  procedural filtering hoặc scriptlet. Có hỗ trợ quy tắc generic `##.ad`
  và ngoại lệ như `~cafef.vn##[id^="adm-slot"]`; CSS vẫn được cache theo host.
  Quy tắc không hỗ trợ trong danh sách tải về được tính vào số bỏ qua; trong
  quy tắc riêng sẽ hiện lỗi và giữ nguyên bộ lọc cũ.
- Chỉ GET HTML document/frame có khai báo Accept text/html, HTTP 200, không có
  Range hoặc Cache-Control: no-transform. Không sửa ảnh, video, JSON hay download.
- Proxy yêu cầu `Accept-Encoding: identity` khi có CSS phù hợp, tránh giải nén/nén
  lại. Nếu máy chủ vẫn gửi dữ liệu nén thì giữ nguyên. HTML có thể tốn nhiều dữ
  liệu mạng hơn; chưa benchmark CPU/pin trên thiết bị thật.
- Chỉ xem tối đa 32 KiB đầu HTML, 256 frame hoặc 2 giây để tìm cuối `<head>`.
  Thiếu head rõ ràng, head quá dài, UTF-16, charset không hỗ trợ, template trong
  head, script kiểu legacy escaped hoặc meta CSP thì bỏ qua. Các meta
  Content-Type UTF-8 và x-dns-prefetch-control thông thường được giữ nguyên. Phần còn lại
  truyền theo luồng, không gom toàn bộ body.
- Với CSP trong HTTP header, chỉ cho phép riêng thẻ style được chèn bằng nonce
  ngẫu nhiên; giữ nguyên các nguồn CSS cũ và chính sách script/thuộc tính style.
  Không thêm nonce vào chính sách vốn cho phép inline để tránh làm mất hiệu lực
  của các style inline đang dùng. Không sửa CSP trong meta HTML.
- Trang đã sửa bỏ Content-Length và các validator/digest cũ, đặt no-store để
  tránh lưu CSS cũ qua lần cập nhật bộ lọc. Asset ảnh/video/script vẫn đi theo
  đường truyền và cache thông thường.
- Cache CSS theo tên miền nằm trong từng bản bộ lọc, tự mất hiệu lực khi reload.
  Tối đa 128 domain trong cache, 64 KiB CSS/domain, 50.000 mục cosmetic và
  2.048 byte/selector. Không chạy JavaScript/MutationObserver/timer quét DOM;
  phần tử mới khớp selector được CSS của trình duyệt áp dụng tự nhiên.

## Kiểm tra

`nativecore/tests/cosmetic.rs` kiểm tra domain/subdomain, loại trừ, ngoại lệ,
cache, giới hạn và từ chối nội dung CSS/HTML không được hỗ trợ.
Unit test trong `mitm/html.rs` kiểm tra phân mảnh head, CSP, trailer, body lớn và
giữ nguyên các phản hồi không tương thích. `nativecore/tests/https.rs` kiểm tra
CSS qua cả bốn tổ hợp HTTP/1.1 và HTTP/2 cùng xác minh nonce/CSP.

Các test proxy chạy trên host với TLS thật; không thay thế kiểm thử hiển thị và
đo CPU/pin trực tiếp trong Chrome Android sau khi cài CA.
