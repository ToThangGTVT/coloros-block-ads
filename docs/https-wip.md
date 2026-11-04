# HTTPS cosmetic filtering — tiếp tục ngày 04/11/2026

Đã sửa hai lỗi còn dở và cài bản debug lên CPH2825 (Android 16/API 36) để kiểm
tra với Chrome 158.0.8127.2. Chưa tạo tag hoặc phát hành phiên bản mới.
Bản release 1.0.4, dữ liệu và CA của bản release được giữ nguyên. Người dùng
đã cài CA riêng của bản debug; không xóa hoặc tạo lại CA sau khi cài.

## IPv6 khi mạng thật không có IPv6 (kiểm tra Chrome trên CPH2825)

Nhật ký trên máy (bản debug mới, chỉ đọc qua adb, không thao tác UI): 256 request
Chrome được giải mã (vnexpress), nhưng 182 lần `tls-bypass` "upstream TLS không xác
minh được" — **toàn bộ** là host có AAAA (`www.googletagmanager.com`,
`imasdk.googleapis.com`, `accounts.google.com`, `www.google.com`,
`challenges.cloudflare.com`…), còn mọi host chạy tốt đều không có AAAA. Từ Mac, cùng
hàm upstream tới các host đó đều thành công. Nguyên nhân: VPN luôn quảng bá `::/0`
(địa chỉ ULA `fdfe:dcba:9876::1`) trong khi Wi‑Fi và 4G lúc đó chỉ có IPv4. Chrome
mở TCP IPv6 vào tunnel, netstack nhận handshake, rồi proxy không ra được mạng: kết nối
bị đóng giữa TLS, Chrome không tự lùi về IPv4. Quảng cáo Google (GTM, IMA) vì thế không
qua proxy, và trang Google/Cloudflare có thể lỗi.

Sửa giống AdGuard ("lọc AAAA khi không có IPv6"):
- `sockets::probe_ipv6` dò route IPv6 công cộng bằng một socket UDP đã protect
  (`connect` không gửi gói) khi engine khởi động và mỗi lần `nativeUpdateNetwork`.
  Protect thất bại thì giữ kết quả cũ.
- Không có IPv6: DNS trả AAAA rỗng (NOERROR, không hỏi upstream, không ghi cache) và bỏ
  `ipv6hint` (cả trong `mandatory`) khỏi HTTPS/SVCB cho mọi app.
- Kết nối TCP IPv6 còn lọt vào (IP cache/hard-code) nhận RST ngay, không mở socket ra
  ngoài, để app lùi về IPv4.
Phép dò chỉ chạy khi `NetworkConfig::probe_ipv6` bật (JNI luôn bật; test dùng
mặc định tắt để giữ bất biến "không protect socket nào"). Test: `tests/ipv6.rs`,
`tests/ipv6_tunnel.rs`.

Cũng trên máy: sau khi cài mới chỉ còn "AdGuard DNS filter" được bật, nên gần như không
có quy tắc ẩn phần tử/URL. Cần bật ABPVN, EasyList hoặc AdGuard Base trong **Bộ lọc**.
Tên preset bỏ chữ "Network subset" (đã sai từ khi lọc HTTPS dùng đủ cú pháp); máy đã cài
cũng được cập nhật tên khi đọc lại cài đặt.

## Bỏ ECH khỏi DNS cho ứng dụng được lọc HTTPS

Chrome bật Encrypted Client Hello khi bản ghi DNS HTTPS/SVCB có tham số `ech`. Khi
đó SNI nhìn thấy chỉ là tên ngoài (ví dụ `cloudflare-ech.com`), proxy không biết
host thật để chọn quy tắc và chứng chỉ, và phải chờ Chrome thử lại không ECH. Giống
AdGuard, `dns/mod.rs` giờ bỏ `ech` (và `ech` trong `mandatory`) khỏi câu trả lời
HTTPS/SVCB, **chỉ khi** `mitm::eligible` đúng cho app và host đó. Cache giữ bản gốc,
nên app khác, host nhạy cảm (ngân hàng/thanh toán/y tế) và host đang bypass do
pinning vẫn nhận ECH. Các tham số khác (ALPN, ipv4hint...) giữ nguyên. Test:
`tests/ech.rs`. Giới hạn: chỉ áp dụng cho truy vấn DNS gán được UID của trình duyệt;
Chrome dùng resolver riêng nên thường gán được, còn truy vấn đi qua resolver hệ
thống hoặc Secure DNS (DoH) do người dùng tự bật trong Chrome thì không qua bước này.

## Scriptlet, `$redirect`, `$removeparam`

AdGuard Scriptlets được tải lúc chạy (không đóng gói, GPL-3.0) và chuyển sang
`Resource` của adblock-rust trong `filter/resources.rs`. Hàm CoreLibs nhận
`(source, args)` nên mỗi scriptlet được bọc trong `function blockads_<tên>(...args)`
cho khớp cách adblock-rust gọi; loại `application/javascript` vì adblock-rust chỉ
tiêm loại này. Redirect nạp trước; tên trùng (`noeval.js`…) thì bản redirect dùng
cho cả hai, như uBO. Một kho chung cho mọi engine của mọi snapshot quy tắc, nên cập
nhật tài nguyên không phải biên dịch lại bộ lọc; cache trang tự làm mới theo
`resources::generation()`. Lỗi định dạng giữ bộ cũ; phía app chỉ thay file trên đĩa
sau khi native nhận bộ mới. JNI mới: `nativeSetResources`. Chi tiết người dùng:
[cosmetic-filtering.md](cosmetic-filtering.md#phạm-vi-và-giới-hạn).

## Giải nén HTML trong proxy

Trước đây trang cần chèn bị ép `Accept-Encoding: identity`, nên HTML tải về qua
mạng không nén (thường lớn gấp 5–10 lần), và `no-store` làm Back/Forward phải tải
lại. Giờ proxy xin `gzip, deflate, br`, giải nén theo luồng bằng `async-compression`
(Rust thuần, không cần zstd/C) rồi mới chèn; trình duyệt nhận bản không nén qua kết
nối nội bộ. Cache đổi thành `private, no-cache`, vẫn bỏ validator. Test: bốn mã hóa
+ gzip nhiều member + dữ liệu hỏng (unit), origin gzip qua bốn tổ hợp H1/H2, và
Chrome headless với origin gửi brotli (10/10). Chi tiết:
[cosmetic-filtering.md](cosmetic-filtering.md#phạm-vi-và-giới-hạn).

## Chèn sớm ngay sau `<head>`

Trước đây phải quét hết head tới `</head>` mới chèn, và bỏ qua cả trang khi gặp
meta CSP, `<template>`, script legacy hay thiếu thẻ head. Giờ khi header có
charset, `<style>`/`<script>` của BlockAds đứng ngay sau `<head>` (hoặc trước phần
tử đầu tiên), nên CSS áp dụng trước khi trang vẽ và script chạy trước script của
trang — điều kiện cần cho scriptlet ở bước sau. Không có charset trong header thì
chèn sau `<meta charset>`, không có nữa thì giữ cách cũ. Chi tiết:
[cosmetic-filtering.md](cosmetic-filtering.md#phạm-vi-và-giới-hạn).
Script nạp CSS chung bằng URL tuyệt đối để `<base href>` không làm hỏng.

## Ẩn phần tử qua adblock-rust

Parser CSS riêng (chỉ nhận CSS ASCII đơn giản, cắt ở 64 KiB theo thứ tự chữ cái
nên selector riêng của trang có thể bị mất) được thay bằng engine cosmetic của
adblock-rust, bật `css-validation`. Chi tiết và giới hạn:
[cosmetic-filtering.md](cosmetic-filtering.md#cách-hoạt-động).

- `<style nonce>` chứa quy tắc riêng + quy tắc chung không dựa class/id; script
  `mitm/html/content.js` có nonce xử lý quy tắc chung class/id (qua đường dẫn
  ngẫu nhiên do proxy tự trả lời) và procedural/action. CSP thêm
  `script-src-elem` với nonce, giống cách đã làm cho style.
- `$generichide`/`$elemhide` được đưa cả vào engine cosmetic (ABPVN dùng cho
  tinhte.vn: trang chỉ nhận 8 quy tắc riêng, gồm `.micro` và `.pro-container`).
- `scripts/native-stamp.py` giờ tính cả file `.js` trong `nativecore/src`, vì
  script được nhúng vào thư viện native bằng `include_str!`.

**Sửa lỗi bypass 6 giờ:** trước đây mọi handshake TLS thất bại với Chrome (kể cả
socket preconnect bị Chrome bỏ dở, EOF, reset hoặc hết 10 giây) đều bị coi là
"client từ chối chứng chỉ" và tắt lọc HTTPS cho UID+host đó trong 6 giờ. Lỗi được
phát hiện khi test Chrome headless thỉnh thoảng nhận `ERR_CONNECTION_RESET`.
Giờ chỉ bypass khi client gửi alert chứng chỉ (bad/unsupported/revoked/expired/
unknown certificate, unknown CA, access denied, hoặc decrypt error khi máy còn tin
một CA cũ cùng tên). Test mới kiểm tra cả ba trường hợp; test Chrome đạt 20/20
sau sửa (trước đó khoảng 1/5 lần lỗi).

## Bộ lọc URL đầy đủ qua adblock-rust

Trước đây parser riêng bỏ mọi quy tắc có tùy chọn ngoài `$important`/`$app`
(ABPVN mất 298/424 quy tắc mạng, EasyList mất khoảng 7.000). Proxy HTTPS giờ
dùng thêm engine `adblock` 0.13.5 của Brave (`nativecore/src/filter/network.rs`):

- Engine chỉ nhận các quy tắc cần ngữ cảnh request: đường dẫn, `$script`,
  `$image`, `$xmlhttprequest`, `$third-party`, `$domain=`, regex có tùy chọn...
  Quy tắc tên miền thuần (`||domain^`, hosts, tên miền trần) và `$app` vẫn ở
  matcher DNS cũ, nên danh sách DNS lớn không bị nạp hai lần. Tên miền trần
  không bị engine hiểu thành chuỗi con của URL.
- Loại request lấy từ `Sec-Fetch-Dest` (thiếu thì đoán theo `Accept`), trang khởi
  tạo lấy từ `Referer`, rồi `Origin`, rồi `Sec-Fetch-Site`. Điều hướng chính coi
  chính URL là bên thứ nhất. Không rõ trang khởi tạo thì coi là bên thứ ba và
  `$domain=` không khớp.
- Thứ tự ưu tiên giữ như cũ: quy tắc riêng cho phép > quy tắc riêng chặn >
  `$important` của danh sách > ngoại lệ `@@` của danh sách > chặn của danh sách.
  Ngoại lệ luôn được kiểm tra, nên `@@||host/path$script` có thể mở một request
  cụ thể dù host bị danh sách hosts chặn ở DNS (DNS vẫn chặn như trước).
- Quy tắc riêng có tùy chọn ngữ cảnh giờ được nhận thay vì báo lỗi, nhưng chỉ có
  tác dụng với ứng dụng bật lọc HTTPS. Regex vẫn chịu giới hạn biên dịch của
  Phase 3; `||` phải có tên miền hợp lệ; tùy chọn lạ hoặc `$app` kèm tùy chọn
  khác vẫn báo lỗi.
- Chưa dùng: `$redirect` (chưa nạp tài nguyên thay thế, request vẫn bị chặn bằng
  204), `$removeparam`, `$csp`. Engine không hỗ trợ `$popup` (khoảng 3.000 dòng
  EasyList vẫn bị bỏ qua).

Đo trên máy tính (release, EasyList + ABPVN + AdGuard DNS filter, 269.097 dòng):
biên dịch 163 ms, nạp 261.536 quy tắc thay vì 255.148, RSS đỉnh 51 MB so với
47,6 MB của bản cũ, mỗi lần xét request trung bình 7,5 µs. Chưa đo trên điện thoại.
Test: `tests/network_rules.rs` và tổ hợp H1/H2 trong `tests/https.rs`
(script bên thứ ba bị chặn, cùng site hoặc ảnh thì vẫn tới upstream).

## Sửa tổng quát sau kiểm tra Tinhte

Phần này thay thế giới hạn head 64 KiB và các lỗi còn mở trong biên bản Tinhte
phía dưới. Không thêm ngoại lệ theo website và không thay quy tắc/config CA.

- **HTML theo luồng:** `mitm/html/scanner.rs` giữ trạng thái tokenizer qua từng
  chunk, truyền ngay nội dung style/script/comment và chỉ giữ một thẻ dang dở
  tối đa 8 KiB. Khi đọc `</head>` hợp lệ thì chèn style; sau đó body đi trực tiếp
  bằng Bytes. Không gom head, không tăng trần 64/128/256 KiB theo website, không
  đợi đủ head hoặc timeout mới gửi response. Thẻ đơn quá dài/markup không hỗ trợ
  vẫn truyền nguyên nội dung. HTML candidate bỏ metadata độ dài/digest/validator
  và đặt no-store trước khi stream; asset không bị đổi cache vì cơ chế này.
- **HTTP/2 upstream:** đã tái hiện cùng lỗi Google bằng proxy host, không cần
  điện thoại. Bỏ `Host` dư khi gửi HTTP/2, lấy `:authority` từ URI tuyệt đối,
  request đổi từ 502/PROTOCOL_ERROR thành 200. Với HTTP/1.1, dựng lại `Host` từ
  authority chuẩn hóa ở từng lần gửi, kể cả reconnect đổi ALPN. Không fallback
  hàng loạt về HTTP/1.1 và không retry request đã được serialize.
  [RFC 9113 §8.3.1](https://www.rfc-editor.org/rfc/rfc9113.html#section-8.3.1)
  là tài liệu đối chiếu authority/Host; việc Google từ chối request được xác minh
  bằng probe trước/sau, không suy luận rằng RFC cấm mọi Host trong HTTP/2.
- **Điện thoại, đúng bài Tinhte:** HTTP 200/h2 qua BlockAds Local CA, không lấy
  từ cache/service worker. Head ~129 KiB có `style[data-blockads]`; `.micro` và
  `.pro-container` đều display:none/height:0 thay cho ô cao 180 px. Nội dung bài
  vẫn đọc được, khoảng 6.351 ký tự text toàn trang.
- ajax.googleapis.com (jQuery), www.googletagmanager.com, www.googletagservices.com,
  cse.google.com và www.gstatic.com (Firebase) đều trả 200 từ mạng trong lượt
  thử sau sửa. Không có HTTP >=400 hoặc `https-error` mới trong lượt này.
- 42 test Rust workspace qua; ba probe live-network ignored mặc định. Test mới
  kiểm tra head 2 MiB, style/script/comment 4 MiB với buffer thẻ không tăng,
  các vị trí chia byte, markup quá dài, EOF/trailer, và gửi phần đầu head ngay
  cả khi origin chưa kết thúc head. Bốn tổ hợp H1/H2 kiểm tra Host/authority ở
  server; multiplex, upstream reconnect và không replay POST vẫn qua.
- fmt, clippy workspace/all-targets -D warnings, native bốn ABI/JNI stamp,
  debug + androidTest APK + release R8 + lint đều qua. Đã cài đè debug, giữ CA và
  dữ liệu; bản release không bị thay thế. Chưa benchmark CPU/pin dài hạn.

## Sửa lần đầu, trước kiểm tra Tinhte

- **HTTP 502 khi chuyển trang/tải lại:** Chrome giữ kết nối HTTP/2 tới proxy,
  còn máy chủ đã đóng kết nối upstream. Proxy cũ gọi `ready()` trên sender đã
  đóng và trả 502 với `channel closed`, không mở lại upstream. Kết nối mới từ
  Chrome vẫn tải được nên dễ nhầm lỗi này với lỗi chứng chỉ hoặc CSS.
  Proxy giờ mở lại upstream, gọi protect trước connect và xác minh TLS/hostname
  như ban đầu. URI và HTTP version theo ALPN mới. Request chỉ được thử lại nếu
  Hyper trả lại nguyên request chưa từng serialize; không phát lại request đã
  tới máy chủ, kể cả khi máy chủ chưa gửi response. Driver HTTP/2 cũ được giữ
  cho những stream đang hoàn tất sau GOAWAY. Lỗi driver upstream có chuỗi nguyên
  nhân trong `https-error`, không ghi URL/path/body.
- **Bài viết không có CSS:** head bài GenK dài khoảng 35 KiB, vượt giới hạn
  32 KiB. Tăng giới hạn lên 64 KiB, vẫn giữ trần 256 frame/2 giây và truyền
  phần body còn lại theo luồng. Test kiểm tra nội dung gốc không thay đổi ngoài
  thẻ style được chèn; head quá giới hạn vẫn truyền nguyên trạng.

## Kết quả Chrome trên điện thoại

Bật AdGuard DNS filter + ABPVN (729 rule được hỗ trợ), chặn QUIC, chọn Chrome
trong phạm vi HTTPS. Dùng DevTools đọc HTTP status/chứng chỉ/DOM/computed style;
không vô hiệu hóa cache hoặc service worker và không xóa dữ liệu Chrome.

| Trường hợp | HTTP | Chứng chỉ | CSS data-blockads | Ô adm-slot-7269 |
| --- | --- | --- | --- | --- |
| HTTPS bật, trang chủ | 200 | BlockAds Local CA | Có | display:none, height:0 |
| Chuyển sang bài viết | 200 | BlockAds Local CA | Có | display:none, height:0 |
| Tắt HTTPS khi Chrome đang mở | 200 | GlobalSign | Không | display:block, height:210 px |
| Bật lại và tải lại trang chủ/bài viết | 200 | BlockAds Local CA | Có | display:none, height:0 |

Bài viết thử:
https://m.genk.vn/may-tinh-anh-em-dinh-mua-se-dat-them-bao-nhieu-trong-nam-toi-3-kich-ban-cho-3-tam-gia-165261104151528916.chn

Bài viết có nội dung đọc được và hơn 440 KiB DOM body; không lấy việc mất quảng
cáo trên trang lỗi làm kết quả thành công. Trước sửa đã tái hiện 502, sau sửa
chuỗi chuyển trang không có 502 mới. Các request tới domain bị danh sách DNS
chặn vẫn bị từ chối khi tắt HTTPS.

## Kiểm tra mã và build

- 38 test Rust workspace qua, hai probe dùng mạng thật được ignored mặc định;
  fmt và clippy workspace/all-targets `-D warnings` sạch.
- Regression mới tái hiện 502 ở bản cũ, qua sau sửa: upstream HTTP/1.1 đóng
  connection và HTTP/2 GOAWAY, Chrome giữ cùng kết nối TLS tới proxy; mỗi socket
  mở lại đều protect. POST đã được origin nhận nhưng chưa trả response không bị
  gửi lại (một lần gọi, không mở thêm socket để retry).
- Test head 35 KiB, CSS/ngoại lệ, CSP, streaming, bốn tổ hợp HTTP/1.1↔HTTP/2 và
  HTTP/2 concurrent streams qua. Gộp probe GenK vào `tests/https.rs`, bỏ
  `tests/genk_live.rs` vốn include cả file test gây chạy trùng.
- Build native đủ bốn ABI và xác minh JNI/source stamp; debug, androidTest APK,
  release R8 và lint qua. Unit test core:common/core:data qua.
- Emulator API 37: hai test CA qua, không skip, gồm xóa khóa/file/tắt HTTPS,
  tạo lại CA mới và kiểm tra khóa Keystore không export/chưa cài CA không bật được.

Probe host tự chọn, không chạy trong CI mặc định:

```sh
cargo test --manifest-path nativecore/Cargo.toml --locked --test https \
  live_genk_html_through_https_proxy -- --ignored --nocapture --test-threads=1
BLOCKADS_CHROME='/path/to/chrome' cargo test --manifest-path nativecore/Cargo.toml \
  --locked --test https live_genk_in_desktop_chrome -- --ignored --nocapture --test-threads=1
```

Probe Chrome dùng profile tạm và chấp nhận CA test chỉ trong process đó; proxy
vẫn xác minh TLS upstream. Không thay đổi trust store trên máy tính.

## Còn giới hạn

Chưa đo CPU/pin dài hạn hoặc nghiệm thu mọi website/trình duyệt/OEM. Không hỗ
trợ scriptlet, procedural selectors, HTTP Upgrade/CONNECT, QUIC MITM và HTML
ngoài các điều kiện trong [cosmetic-filtering.md](cosmetic-filtering.md).
Không phát hành hoặc tự động xóa CA debug đã cài sau kiểm thử.

## Kiểm tra Tinhte trước bản sửa theo luồng (04/11/2026)

URL: https://tinhte.vn/thread/the-khong-cham-con-chip-khong-pin-muon-dien-tu-may-pos-de-tao-ma-rieng-cho-moi-giao-dich.4189356/

Biên bản này ghi nhận lỗi trước sửa; kết quả đã sửa ở đầu tài liệu.
Hai lần reload trong Chrome, giữ cache/service worker bình thường:

- Document chính trả HTTP 200/h2 qua BlockAds Local CA, không phải cache hoặc
  service worker. Nội dung bài đọc được (khoảng 6.365 ký tự text toàn trang).
- **Cosmetic chưa đạt:** không có `style[data-blockads]`. Response HTML khoảng
  1.015.610 byte, `</head>` ở byte 127.564 (~125 KiB), vượt trần 64 KiB. ABPVN
  đã có rule `.micro` và `.pro-container` áp dụng cho tinhte.vn, nhưng không được
  chèn vào trang. Container quảng cáo phía trên vẫn display:block, cao 180 CSS px;
  wrapper chiếm 252 px. Đây không phải kết quả chặn quảng cáo thành công.
- **Tài nguyên phụ có lỗi:** ajax.googleapis.com, www.googletagmanager.com,
  www.googletagservices.com và cse.google.com trả HTTP 502. Log native xác nhận
  `http2 error: stream error received: unspecific protocol error detected`.
  Khác lỗi `channel closed` do stale sender đã sửa; chưa sửa nguyên nhân này.
  www.gstatic.com còn trả 421 ở một số request (proxy từ chối authority khác SNI).
- Không sửa source, nâng giới hạn hoặc thay rule/config trong lượt kiểm tra này.
  Cần xử lý việc chèn CSS cho head lớn và lỗi HTTP/2 upstream trước khi coi
  Tinhte hoặc mọi tài nguyên HTTPS đã được hỗ trợ đầy đủ.

## Danh sách DNS và bộ lọc trình duyệt tách riêng

Mỗi danh sách là DNS hoặc trình duyệt (`FilterList.browser`); native nhận ba
phần riêng (`Rules::compile_lists(dns, browser, custom)`, JNI
`nativeReplaceRules(dns, browser, custom, apply)`):

- Danh sách DNS (AdGuard DNS filter, StevenBlack, danh mục AdGuard Home) chỉ vào
  matcher tên miền: chặn câu trả lời DNS cho mọi ứng dụng. Quy tắc ẩn phần tử và
  quy tắc cần ngữ cảnh request trong danh sách DNS bị bỏ qua, như AdGuard Home.
- Bộ lọc trình duyệt (AdGuard Base, EasyList, EasyPrivacy, ABPVN, danh mục tiện
  ích AdGuard) chỉ vào engine `adblock` và CSS/scriptlet của proxy HTTPS, kể cả
  `||domain^`. Chúng không bao giờ chặn DNS, nên ứng dụng không bật lọc HTTPS
  không bị ảnh hưởng.
- Quy tắc riêng vẫn áp dụng ở cả hai nơi. `Rules::compile(system, custom)` cũ
  vẫn coi `system` là áp dụng mọi nơi (dùng trong test).
- Danh sách đã lưu trước khi tách được coi là DNS, trừ các preset trình duyệt.
  Một URL chỉ thuộc một loại; thêm trùng sang loại kia sẽ báo lỗi.

Danh mục trình duyệt lấy từ `https://filters.adtidy.org/extension/ublock/filters.json`
(bản cú pháp uBlock Origin của danh mục tiện ích AdGuard, 7 nhóm), lưu cache 4
ngày. EasyList (101), EasyPrivacy (118) và ABPVN (214) trỏ về URL của preset để
không tải hai bản. Danh sách của ngôn ngữ máy được xếp lên đầu nhóm Theo ngôn ngữ.

Engine nhận thêm `/path.js$script` (không phải regex nếu không kết thúc bằng
`/`), `||ads-*.example.com^` và `||192.0.2.1^`. Kiểm tra với 16 danh sách thật
(`BLOCKADS_LISTS=... cargo test --release --test real_lists -- --ignored`):
AdGuard Base 128.709 quy tắc nạp, 3.783 bỏ qua (chủ yếu `$popup`), Tracking
Protection 406.632/41, EasyPrivacy 56.459/3, ABPVN 1.193/3; URL Tracking bỏ
208/2.736 vì `$removeparam` không giá trị hoặc dạng regex mà adblock-rust chưa
hỗ trợ. Cả 16 danh sách cùng lúc biên dịch trong khoảng 1,1 giây trên Mac.

`tests/dns_filter.rs` chạy song song có thể lỗi ngẫu nhiên vì dùng chung cấu
hình toàn cục (lỗi có sẵn ở HEAD); chạy `cargo test -- --test-threads=1`.

Tải danh sách chạy trong scope của `FilterRepository`, không theo màn hình gọi nó.
Trước đây bật bộ lọc trong danh mục rồi bấm Back sẽ hủy coroutine đang tải: AdGuard
Base (6,4 MB) và EasyList không bao giờ tải xong, cũng không ghi lỗi. Trên máy thật
hai danh sách này đã bật nhưng `updatedAt` vẫn là 0 và không có file.

## QUIC chỉ bị chặn cho app lọc HTTPS

Trước đây **Chặn QUIC** drop UDP/443 của mọi app không bỏ qua lọc. Giờ chỉ drop khi
app đang lọc HTTPS (`mitm::filters_app`: đã bật, app được chọn, có CA) và host không
thuộc loại proxy luôn bỏ qua (nhạy cảm, pinning; tra theo reverse DNS, không tra
được thì vẫn drop). App khác như YouTube, Instagram giữ HTTP/3: chặn quảng cáo của
chúng dựa vào DNS, hoạt động như nhau với QUIC hay TCP. Đổi lại, app khác dùng
IP cứng hoặc DoH riêng qua QUIC không còn bị chặn theo SNI như khi đi TCP.
