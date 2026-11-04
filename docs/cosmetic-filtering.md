# Ẩn phần tử quảng cáo trong Chrome

BlockAds có thể chèn CSS và một script nhỏ vào trang HTML qua bộ lọc HTTPS cục
bộ, theo cách Brave/uBlock Origin làm: quy tắc được xử lý bằng engine adblock-rust
của Brave. CSS ẩn phần tử và thường thu gọn chỗ của nó; chỉ quy tắc `:remove()`
mới xóa node. Không cần extension hoặc root, nhưng trình duyệt phải tin CA của BlockAds.

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
   example.com##div:has(> .sponsor)
   example.com##.box:has-text(Quảng cáo)
   example.com##.popup:remove()
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
news.example.com và tên miền con. Ngoại lệ chỉ bỏ quy tắc có selector trùng
khớp. Ngoại lệ chung không gắn tên miền (`#@#.ad`) không được hỗ trợ; dùng
`@@||example.com^$generichide` để tắt mọi quy tắc chung trên một website.

## Cách hoạt động

- **Quy tắc riêng của website** và quy tắc chung không dựa trên class/id (ví dụ
  `##[id^="div-gpt-ad"]`) được gộp thành `<style nonce>` chèn sớm nhất có thể
  (xem vị trí chèn bên dưới), trước style và script của chính trang.
  Mỗi selector một rule, nên selector trình duyệt không hiểu chỉ mất rule của nó.
  Không còn giới hạn 64 KiB; với EasyList + ABPVN, trang thường nhận khoảng 26 KB
  (~435 rule), tính trong ~0,1 ms và được cache cho 128 host gần nhất.
- **Quy tắc chung theo class/id** (`##.ad-banner`, `###sponsor`; EasyList có gần
  9.000 quy tắc loại này) không bị nhét hết vào mọi trang. Script chèn kèm theo
  dõi class/id xuất hiện (cả phần tử thêm sau bằng JavaScript), rồi nạp
  `<link rel=stylesheet nonce>` tới một đường dẫn ngẫu nhiên trên chính website.
  Proxy trả CSS tại chỗ, **không bao giờ gửi request này tới máy chủ website**.
  URL được tạo tuyệt đối từ `location.origin`, nên `<base href>` của trang không
  đẩy được request sang host khác.
  Dùng `<link>` thay cho `fetch` để chỉ phụ thuộc `style-src` (đã có nonce).
- **Procedural/action** chạy trong script: `:has-text`, `:upward`, `:xpath`,
  `:matches-attr`, `:matches-css(-before/-after)`, `:matches-path`,
  `:min-text-length`, và `:remove()`, `:remove-attr()`, `:remove-class()`,
  `:style()`. Chạy lại tối đa 5 lần/giây khi DOM thay đổi. `#?#` và
  `:-abp-has()` được đổi sang `:has()` của CSS.
- Selector và `:style()` được kiểm tra bằng bộ phân tích CSS của Servo; bỏ quy tắc
  có `url(`, `image-set(`, escape hoặc comment trong `:style()`. Ký tự `<` trong
  CSS/JSON được escape nên không thể đóng thẻ `<style>`/`<script>`.

## Phạm vi và giới hạn

- **Scriptlet** (`##+js(...)`, cú pháp uBO): chạy bằng thư viện AdGuard Scriptlets
  (GPL-3.0), app tự tải `scriptlets.corelibs.json` và `redirects.json` từ jsDelivr
  (`@adguard/scriptlets@2`) lần đầu và kiểm tra lại mỗi ngày bằng ETag; không đóng gói
  trong APK. Khoảng 100 scriptlet, nhận cả tên uBO (`set-constant`, `aopr`,
  `ubo-…`). Scriptlet được chèn thành `<script nonce>` riêng, **đứng đầu**, nên chạy trước
  mọi script của trang. Scriptlet `trusted-*` (đặt cookie, localStorage, chèn nội dung)
  không chạy được từ danh sách hay quy tắc riêng. Scriptlet chỉ có trong uBO mà AdGuard
  không có thì bị bỏ qua. Chưa tải được tài nguyên thì quy tắc `+js` chưa có tác dụng.
- **`$redirect`/`$redirect-rule`**: request bị chặn được trả bằng tài nguyên thay thế
  (script rỗng, ảnh 1x1, stub Google Publisher Tag/Analytics/IMA…, 43 loại) thay vì 204,
  để trang phụ thuộc thư viện quảng cáo không bị lỗi. Không có tài nguyên thì vẫn 204.
- **`$removeparam`**: request được gửi tới máy chủ không kèm tham số theo dõi (chỉ khi
  URL mới cùng origin); trình duyệt vẫn thấy URL cũ.
- Chỉ GET HTML document/frame có khai báo Accept text/html, HTTP 200, không có
  Range hoặc Cache-Control: no-transform. Không sửa ảnh, video, JSON hay download.
- Với trang cần chèn, proxy xin máy chủ `Accept-Encoding: gzip, deflate, br`
  (bỏ zstd), nên HTML vẫn đi nén qua mạng di động. Proxy giải nén theo luồng rồi
  gửi bản không nén cho trình duyệt qua kết nối nội bộ; không nén lại. Mã hóa lạ
  hoặc xếp chồng (`zstd`, `gzip, br`) thì truyền nguyên, không chèn. Dữ liệu nén
  hỏng làm hỏng response đó, không làm sập proxy. Trên Mac: ~1,3 MB HTML giải nén
  + chèn trong ~1 ms; chưa benchmark CPU/pin trên điện thoại.
- Vị trí chèn (phân tích HTML theo luồng, chỉ giữ một thẻ chưa hoàn chỉnh tối đa
  8 KiB, chèn xong thì truyền thẳng phần còn lại):
  - Header `Content-Type` có charset (phần lớn website): ngay sau thẻ `<head …>`;
    trang bỏ thẻ head thì chèn trước phần tử đầu tiên. Không bao giờ chèn trước
    doctype, comment hay `<?…?>` (tránh quirks mode). Không phải quét hết head, nên
    meta CSP, `<template>` hay script legacy phía sau không còn làm bỏ qua trang.
  - Header không có charset: ngay sau `<meta charset>`/`http-equiv=content-type`,
    để khai báo charset vẫn nằm trong 1.024 byte đầu trình duyệt dò. Không có meta
    charset thì chèn trước `</head>` như trước; trường hợp này vẫn truyền nguyên
    nội dung khi thiếu head rõ ràng, có template trong head, script legacy escaped,
    meta CSP, charset không hỗ trợ hoặc một thẻ quá dài.
  - UTF-16 hoặc charset không hỗ trợ trong header/meta: luôn truyền nguyên.
  - Meta CSP nằm sau phần chèn chỉ áp dụng cho nội dung sau nó: style/script đã
    chèn vẫn chạy; stylesheet chung nạp sau đó có thể bị chặn nếu meta CSP không
    cho `'self'`.
- CSP trong HTTP header: thêm nonce riêng cho thẻ style và script được chèn
  (`style-src-elem`, `script-src-elem`), giữ nguyên các nguồn cũ. Không thêm nonce
  vào chính sách vốn cho phép inline (trừ khi có `'strict-dynamic'`, vốn làm
  `'unsafe-inline'` mất hiệu lực). Không sửa CSP trong meta HTML.
- HTML được chèn bỏ Content-Length, Content-Encoding và validator/digest cũ, đặt
  `Cache-Control: private, no-cache`: không còn validator nên mỗi lần mở trang đều
  tải bản mới (nonce mới, quy tắc mới), nhưng Back/Forward vẫn có thể dùng bản đã
  lưu cùng CSP khớp với nó (trước đây `no-store` chặn cả việc này). Asset
  ảnh/video/script giữ cache thông thường.
- Trang lấy từ cache/service worker mà không qua mạng sẽ không có phần chèn.

## Kiểm tra

- `nativecore/tests/cosmetic.rs`: phạm vi/ngoại lệ domain, `:has`, `#?#`,
  procedural, `:style()` an toàn, `$generichide`, 6.000 quy tắc không bị cắt.
- `mitm/html.rs`: nonce/CSP cho script (kể cả `'strict-dynamic'`), script không thể
  tự đóng thẻ, tokenizer theo luồng. `tests/https.rs`: endpoint CSS chung qua bốn
  tổ hợp HTTP/1.1↔HTTP/2, không tới upstream.
- Chrome desktop headless, không cần mạng, CSP chặt (`default-src 'none'`):

  ```sh
  BLOCKADS_CHROME='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' \
    cargo test --manifest-path nativecore/Cargo.toml --locked --test https \
    content_script -- --ignored --nocapture --test-threads=1
  ```

  Chrome 156: 20/20 lần đạt — class chung, phần tử thêm sau 300 ms, `:has-text`,
  `:remove()` và id riêng đều bị ẩn/xóa; phần tử thường không bị ảnh hưởng.
  Sau khi chèn sớm: thêm 10/10 lần với `<base href>` trỏ sang host khác và script
  của trang trong head xác nhận script BlockAds đã chạy trước nó. Bỏ URL tuyệt đối
  thì test này fail (đã thử).
  Scriptlet và `$redirect`: 10/10 với tài nguyên giả lập, 5/5 với file AdGuard thật
  (`BLOCKADS_RESOURCES=<thư mục chứa corelibs.json, redirects.json>`): `set-constant`
  chạy trước script của trang, `/ad-lib.js` bị chặn được trả bằng `noopjs`.
  Chưa kiểm tra Chrome Android trên điện thoại.
