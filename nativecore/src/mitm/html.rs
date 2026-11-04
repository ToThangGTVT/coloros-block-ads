//! Bounded HTML-head rewrite; the remainder streams unchanged. No DOM watcher or script execution.
use super::proxy::Body;
use bytes::Bytes;
use futures_util::{stream, StreamExt};
use http_body_util::{BodyExt, BodyStream, StreamBody};
use hyper::{body::Frame, header::HeaderValue, HeaderMap, Request, Response};
use ring::rand::{SecureRandom, SystemRandom};
use std::{sync::Arc, time::Duration};

const MAX_HEAD: usize = 32 * 1024;
fn no_transform(headers: &HeaderMap) -> bool {
    headers.get_all("cache-control").iter().any(|v| {
        v.to_str().map_or(true, |v| {
            v.split(',')
                .any(|d| d.trim().eq_ignore_ascii_case("no-transform"))
        })
    })
}
pub(super) fn document_request<B>(request: &Request<B>) -> bool {
    request.method() == hyper::Method::GET
        && !request.headers().contains_key("range")
        && !no_transform(request.headers())
        && request
            .headers()
            .get("accept")
            .and_then(|v| v.to_str().ok())
            .is_some_and(|v| {
                v.split(',').any(|v| {
                    v.split(';')
                        .next()
                        .unwrap_or("")
                        .trim()
                        .eq_ignore_ascii_case("text/html")
                })
            })
        && request
            .headers()
            .get("sec-fetch-dest")
            .is_none_or(|v| v == "document" || v == "iframe" || v == "frame")
}
fn eligible(response: &Response<Body>) -> bool {
    let h = response.headers();
    response.status() == hyper::StatusCode::OK
        && !no_transform(h)
        && !h.contains_key("content-range")
        && !h.contains_key("content-disposition")
        && h.get_all("content-encoding")
            .iter()
            .all(|v| v.as_bytes().eq_ignore_ascii_case(b"identity"))
        && h.get("content-type")
            .and_then(|v| v.to_str().ok())
            .is_some_and(|v| {
                let mut fields = v.split(';');
                fields
                    .next()
                    .is_some_and(|v| v.trim().eq_ignore_ascii_case("text/html"))
                    && fields.all(|field| {
                        let Some((key, value)) = field.trim().split_once('=') else {
                            return true;
                        };
                        !key.trim().eq_ignore_ascii_case("charset")
                            || matches!(
                                value
                                    .trim()
                                    .trim_matches(['\'', '"'])
                                    .to_ascii_lowercase()
                                    .as_str(),
                                "utf-8" | "utf8" | "us-ascii" | "iso-8859-1" | "windows-1252"
                            )
                    })
            })
}

pub(super) async fn inject(mut response: Response<Body>, css: Arc<str>) -> Response<Body> {
    if css.is_empty() || !eligible(&response) {
        return response;
    }
    let mut random = [0u8; 16];
    if SystemRandom::new().fill(&mut random).is_err() {
        return response;
    }
    // Hex uses only base64-alphabet characters and is a valid CSP nonce value.
    let nonce: String = random.iter().map(|b| format!("{b:02x}")).collect();
    let Some(csp) = csp_headers(response.headers(), &nonce) else {
        return response;
    };
    let mut frames = Vec::new();
    let mut prefix = Vec::with_capacity(MAX_HEAD);
    let read = async {
        loop {
            let frame = response.body_mut().frame().await?;
            let frame = match frame {
                Ok(frame) => frame,
                Err(error) => {
                    frames.push(Err(error));
                    return None;
                }
            };
            let data = frame.data_ref();
            if let Some(data) = data {
                let n = data.len().min(MAX_HEAD - prefix.len());
                prefix.extend_from_slice(&data[..n]);
            }
            frames.push(Ok(frame));
            if frames.len() >= 256 {
                return None;
            }
            match head_end(&prefix) {
                Head::End(at) => return Some(at),
                Head::Skip => return None,
                Head::More if prefix.len() == MAX_HEAD => return None,
                Head::More => {}
            }
        }
    };
    // Slow/streaming documents must not be held indefinitely waiting for a head terminator.
    let position = tokio::time::timeout(Duration::from_secs(2), read)
        .await
        .ok()
        .flatten();
    let (mut parts, body) = response.into_parts();
    if let Some(at) = position {
        let style = format!("<style nonce=\"{nonce}\" data-blockads>\n{css}</style>");
        // Replay original frames without copying their remaining data (including frames > MAX_HEAD).
        let mut offset = 0;
        let mut inserted = false;
        let mut rewritten = Vec::with_capacity(frames.len() + 2);
        for frame in frames {
            if let Ok(ref frame) = frame {
                if let Some(data) = frame.data_ref() {
                    if !inserted && at >= offset && at <= offset + data.len() {
                        let split = at - offset;
                        rewritten.push(Ok(Frame::data(data.slice(..split))));
                        rewritten.push(Ok(Frame::data(Bytes::from(style.clone()))));
                        rewritten.push(Ok(Frame::data(data.slice(split..))));
                        inserted = true;
                        offset += data.len();
                        continue;
                    }
                    offset += data.len();
                }
            }
            rewritten.push(frame);
        }
        frames = rewritten;
        for name in [
            "content-length",
            "etag",
            "last-modified",
            "content-md5",
            "digest",
            "content-digest",
            "repr-digest",
            "accept-ranges",
        ] {
            parts.headers.remove(name);
        }
        // Do not persist rule-dependent HTML across reloads of the filter lists.
        parts
            .headers
            .insert("cache-control", HeaderValue::from_static("no-store"));
        for (name, values) in csp {
            parts.headers.remove(name);
            for value in values {
                parts.headers.append(name, value);
            }
        }
    }
    let stream = stream::iter(frames)
        .chain(BodyStream::new(body))
        .map(move |frame| {
            frame.map(|mut frame| {
                if position.is_some() {
                    if let Some(trailers) = frame.trailers_mut() {
                        for name in [
                            "content-md5",
                            "digest",
                            "content-digest",
                            "repr-digest",
                            "etag",
                        ] {
                            trailers.remove(name);
                        }
                    }
                }
                frame
            })
        });
    Response::from_parts(parts, StreamBody::new(stream).boxed_unsync())
}

type CspHeaders = Vec<(&'static str, Vec<HeaderValue>)>;
fn csp_headers(headers: &HeaderMap, nonce: &str) -> Option<CspHeaders> {
    let mut result = Vec::new();
    for name in [
        "content-security-policy",
        "content-security-policy-report-only",
    ] {
        let mut values = Vec::new();
        for value in headers.get_all(name) {
            let policies = value
                .to_str()
                .ok()?
                .split(',')
                .map(|policy| {
                    let directives: Vec<_> = policy
                        .split(';')
                        .map(str::trim)
                        .filter(|d| !d.is_empty())
                        .collect();
                    let sources = ["style-src-elem", "style-src", "default-src"]
                        .iter()
                        .find_map(|name| {
                            directives.iter().find_map(|d| {
                                let mut words = d.split_whitespace();
                                words
                                    .next()
                                    .filter(|key| key.eq_ignore_ascii_case(name))
                                    .map(|_| words.collect::<Vec<_>>())
                            })
                        });
                    let Some(sources) = sources else {
                        return policy.to_owned();
                    };
                    // Adding a nonce to an unsafe-inline policy would disable the page's other
                    // inline styles. Leave policies which already permit inline styles alone.
                    if sources
                        .iter()
                        .any(|s| s.eq_ignore_ascii_case("'unsafe-inline'"))
                        && !sources.iter().any(|s| {
                            s.get(..7)
                                .is_some_and(|s| s.eq_ignore_ascii_case("'nonce-"))
                                || s.get(..4).is_some_and(|s| s.eq_ignore_ascii_case("'sha"))
                        })
                    {
                        return policy.to_owned();
                    }
                    let mut output = directives
                        .into_iter()
                        .filter(|d| {
                            !d.split_whitespace()
                                .next()
                                .is_some_and(|k| k.eq_ignore_ascii_case("style-src-elem"))
                        })
                        .collect::<Vec<_>>()
                        .join("; ");
                    output.push_str("; style-src-elem");
                    for source in sources {
                        if !source.eq_ignore_ascii_case("'none'") {
                            output.push(' ');
                            output.push_str(source);
                        }
                    }
                    output.push_str(&format!(" 'nonce-{nonce}'"));
                    output
                })
                .collect::<Vec<_>>()
                .join(", ");
            values.push(HeaderValue::from_str(&policies).ok()?);
        }
        if !values.is_empty() {
            result.push((name, values));
        }
    }
    Some(result)
}

#[derive(Debug, PartialEq)]
enum Head {
    More,
    End(usize),
    Skip,
}
fn head_end(input: &[u8]) -> Head {
    // Small bounded tokenizer: ignore tag-like strings inside comments and raw-text elements.
    // Require an explicit head; unusual or incomplete documents pass through unchanged.
    if input.starts_with(&[0xff, 0xfe]) || input.starts_with(&[0xfe, 0xff]) || input.contains(&0) {
        return Head::Skip;
    }
    let mut at = 0;
    let mut head = false;
    let mut raw: Option<Vec<u8>> = None;
    while at < input.len() {
        if input[at] != b'<' {
            at += 1;
            continue;
        }
        if let Some(ref tag) = raw {
            // Legacy escaped/double-escaped script parsing has different closing-tag rules.
            // Leave those documents untouched rather than risk placing CSS inside script text.
            if tag == b"script" && input[at..].starts_with(b"<!--") {
                return Head::Skip;
            }
            let closing = [b"</".as_slice(), tag].concat();
            if input.len() - at < closing.len() {
                return Head::More;
            }
            if !input[at..at + closing.len()].eq_ignore_ascii_case(&closing)
                || !input
                    .get(at + closing.len())
                    .is_some_and(|c| c.is_ascii_whitespace() || *c == b'>')
            {
                at += 1;
                continue;
            }
            raw = None;
        }
        if input[at..].starts_with(b"<!--") {
            let Some(end) = input[at + 4..].windows(3).position(|v| v == b"-->") else {
                return Head::More;
            };
            at += 4 + end + 3;
            continue;
        }
        let start = at;
        let mut quote = 0;
        at += 1;
        while at < input.len() {
            let c = input[at];
            if quote != 0 {
                if c == quote {
                    quote = 0;
                }
            } else if matches!(c, b'\'' | b'"') {
                quote = c;
            } else if c == b'>' {
                break;
            }
            at += 1;
        }
        if at == input.len() {
            return Head::More;
        }
        let content = &input[start + 1..at];
        let name = content
            .split(|c| c.is_ascii_whitespace() || *c == b'/')
            .next()
            .unwrap_or_default();
        if content
            .strip_prefix(b"/")
            .is_some_and(|n| n.trim_ascii_end().eq_ignore_ascii_case(b"head"))
        {
            return if head { Head::End(start) } else { Head::Skip };
        }
        if name.eq_ignore_ascii_case(b"head") {
            head = true;
        }
        if name.eq_ignore_ascii_case(b"body") {
            return Head::Skip;
        }
        if name.eq_ignore_ascii_case(b"template") {
            return Head::Skip;
        }
        if head
            && !name.is_empty()
            && ![
                b"head".as_slice(),
                b"html",
                b"base",
                b"basefont",
                b"bgsound",
                b"link",
                b"meta",
                b"title",
                b"script",
                b"style",
                b"noscript",
                b"noframes",
            ]
            .iter()
            .any(|n| name.eq_ignore_ascii_case(n))
        {
            return Head::Skip;
        }
        // Ordinary content-type and DNS-prefetch meta tags do not prevent CSS injection.
        if name.eq_ignore_ascii_case(b"meta") && !safe_meta(content) {
            return Head::Skip;
        }
        if [
            b"script".as_slice(),
            b"style",
            b"title",
            b"textarea",
            b"noscript",
            b"noframes",
        ]
        .iter()
        .any(|n| name.eq_ignore_ascii_case(n))
        {
            raw = Some(name.to_ascii_lowercase());
        }
        at += 1;
    }
    Head::More
}

fn safe_meta(content: &[u8]) -> bool {
    let mut at = 4;
    let mut attrs = std::collections::HashMap::new();
    while at < content.len() {
        while content.get(at).is_some_and(u8::is_ascii_whitespace) {
            at += 1;
        }
        if at == content.len() || content[at] == b'/' {
            break;
        }
        let start = at;
        while content
            .get(at)
            .is_some_and(|c| !c.is_ascii_whitespace() && *c != b'=' && *c != b'/')
        {
            at += 1;
        }
        if at == start {
            return false;
        }
        let name = String::from_utf8_lossy(&content[start..at]).to_ascii_lowercase();
        while content.get(at).is_some_and(u8::is_ascii_whitespace) {
            at += 1;
        }
        let mut value = String::new();
        if content.get(at) == Some(&b'=') {
            at += 1;
            while content.get(at).is_some_and(u8::is_ascii_whitespace) {
                at += 1;
            }
            let quote = content
                .get(at)
                .copied()
                .filter(|c| matches!(c, b'\'' | b'"'));
            if quote.is_some() {
                at += 1;
            }
            let start = at;
            while content
                .get(at)
                .is_some_and(|c| quote.map_or(!c.is_ascii_whitespace(), |q| *c != q))
            {
                at += 1;
            }
            value = String::from_utf8_lossy(&content[start..at]).to_ascii_lowercase();
            if quote.is_some() {
                at += 1;
            }
        }
        attrs.entry(name).or_insert(value);
    }
    if ["http-equiv", "charset"]
        .iter()
        .any(|k| attrs.get(*k).is_some_and(|v| v.contains('&')))
    {
        return false;
    }
    if attrs
        .get("http-equiv")
        .is_some_and(|v| v.starts_with("content-security-policy"))
    {
        return false;
    }
    let supported = |value: &str| {
        matches!(
            value.trim(),
            "utf-8" | "utf8" | "us-ascii" | "iso-8859-1" | "windows-1252"
        )
    };
    if attrs.get("charset").is_some_and(|v| !supported(v)) {
        return false;
    }
    if attrs.get("http-equiv").is_some_and(|v| v == "content-type") {
        let Some(value) = attrs.get("content") else {
            return false;
        };
        if value.contains('&') {
            return false;
        }
        if value.split(';').skip(1).any(|p| {
            p.trim().split_once('=').is_some_and(|(k, v)| {
                k.trim() == "charset" && !supported(v.trim_matches(['\'', '"']))
            })
        }) {
            return false;
        }
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;
    use http_body_util::Full;
    use std::convert::Infallible;

    fn body(data: &'static [u8]) -> Body {
        Full::new(Bytes::from_static(data))
            .map_err(|never: Infallible| match never {})
            .boxed_unsync()
    }
    fn response(data: &'static [u8]) -> Response<Body> {
        Response::builder()
            .header("content-type", "text/html; charset=utf-8")
            .body(body(data))
            .unwrap()
    }
    #[test]
    fn tokenizer_ignores_comments_quoted_tags_and_raw_text() {
        let html = b"<!doctype html><HEAD data-x='>'><!-- </head> --><title></head></title><script>let x='</head>';</script><style>/* </head> */</style></HEAD  >body";
        let end = html.windows(7).position(|s| s == b"</HEAD ").unwrap();
        assert_eq!(head_end(html), Head::End(end));
        assert_eq!(head_end(&html[..end + 4]), Head::More);
        assert_eq!(head_end(b"<html><body>no head</body></html>"), Head::Skip);
        assert_eq!(head_end(b"<head><meta http-equiv='Content-Security-Policy' content=\"style-src 'none'\"></head>"), Head::Skip);
        assert_eq!(head_end(b"<head><template></head></template>"), Head::Skip);
        assert_eq!(
            head_end(b"<head><script><!--<script></script></head>"),
            Head::Skip
        );
        assert_eq!(
            head_end(b"<head><svg><title></head></title></svg>"),
            Head::Skip
        );
        assert_eq!(head_end(b"\xff\xfe<head></head>"), Head::Skip);
        let genk = b"<head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\"><meta http-equiv=\"x-dns-prefetch-control\" content=\"on\"></head>";
        assert_eq!(head_end(genk), Head::End(genk.len() - 7));
        assert_eq!(head_end(b"<head><meta charset=utf-16></head>"), Head::Skip);
    }
    #[test]
    fn csp_preserves_existing_inline_styles_and_other_directives() {
        let mut headers = HeaderMap::new();
        headers.append(
            "content-security-policy",
            HeaderValue::from_static(
                "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'",
            ),
        );
        headers.append(
            "content-security-policy",
            HeaderValue::from_static(
                "default-src 'none', style-src 'nonce-original' 'unsafe-inline'; img-src https:",
            ),
        );
        headers.append(
            "content-security-policy-report-only",
            HeaderValue::from_static("style-src-elem 'none'; style-src-attr 'none'"),
        );
        let result = csp_headers(&headers, "newnonce").unwrap();
        assert_eq!(result[0].1.len(), 2);
        assert_eq!(
            result[0].1[0],
            headers
                .get_all("content-security-policy")
                .iter()
                .next()
                .unwrap()
        );
        let second = result[0].1[1].to_str().unwrap();
        assert!(second.contains("default-src 'none'; style-src-elem 'nonce-newnonce'"));
        assert!(second.contains("style-src-elem 'nonce-original' 'unsafe-inline' 'nonce-newnonce'"));
        assert!(second.contains("img-src https:"));
        assert!(result[1].1[0]
            .to_str()
            .unwrap()
            .contains("style-src-attr 'none'; style-src-elem 'nonce-newnonce'"));
    }
    #[tokio::test]
    async fn chunk_boundaries_and_trailers_are_preserved() {
        let mut trailers = HeaderMap::new();
        trailers.insert("content-digest", HeaderValue::from_static("old-digest"));
        trailers.insert("x-end", HeaderValue::from_static("yes"));
        let frames: Vec<Result<_, super::super::proxy::BoxError>> = vec![
            Ok(Frame::data(Bytes::from_static(b"<html><he"))),
            Ok(Frame::data(Bytes::from_static(
                b"ad><meta charset=utf-8></he",
            ))),
            Ok(Frame::data(Bytes::from_static(
                b"ad><body>content</body></html>",
            ))),
            Ok(Frame::trailers(trailers)),
        ];
        let response = Response::builder()
            .header("content-type", "text/html")
            .header("content-length", "75")
            .body(StreamBody::new(stream::iter(frames)).boxed_unsync())
            .unwrap();
        let response = inject(response, Arc::from(".ad{display:none!important}\n")).await;
        assert!(!response.headers().contains_key("content-length"));
        let collected = response.into_body().collect().await.unwrap();
        assert_eq!(collected.trailers().unwrap()["x-end"], "yes");
        assert!(!collected.trailers().unwrap().contains_key("content-digest"));
        let text = String::from_utf8(collected.to_bytes().to_vec()).unwrap();
        assert!(text.starts_with("<html><head><meta charset=utf-8><style nonce="));
        assert!(text.ends_with("</style></head><body>content</body></html>"));
    }
    #[tokio::test]
    async fn incompatible_documents_are_passed_through_byte_for_byte() {
        const HTML: &[u8] = b"<html><head></head><body>content</body></html>";
        for (header, value) in [
            ("content-encoding", "gzip"),
            ("cache-control", "public, no-transform"),
            ("content-type", "application/json"),
            ("content-type", "text/html; charset=utf-16"),
            ("content-range", "bytes 0-100/200"),
            ("content-disposition", "attachment; filename=page.html"),
        ] {
            let mut response = response(HTML);
            response
                .headers_mut()
                .insert(header, HeaderValue::from_static(value));
            let result = inject(response, Arc::from(".ad{display:none!important}")).await;
            assert!(
                !result.headers().contains_key("cache-control")
                    || result.headers()["cache-control"] != "no-store"
            );
            assert_eq!(
                result
                    .into_body()
                    .collect()
                    .await
                    .unwrap()
                    .to_bytes()
                    .as_ref(),
                HTML
            );
        }
        for data in [
            b"<body>no head</body>".as_slice(),
            b"<head><meta http-equiv='Content-Security-Policy'></head>",
            b"<head><script>unfinished",
        ] {
            let result = inject(response(data), Arc::from(".ad{display:none!important}")).await;
            assert_eq!(
                result
                    .into_body()
                    .collect()
                    .await
                    .unwrap()
                    .to_bytes()
                    .as_ref(),
                data
            );
        }
        let data = format!("<head>{}</head><body>large</body>", " ".repeat(MAX_HEAD));
        let response = Response::builder()
            .header("content-type", "text/html")
            .body(
                Full::new(Bytes::from(data.clone()))
                    .map_err(|never: Infallible| match never {})
                    .boxed_unsync(),
            )
            .unwrap();
        let result = inject(response, Arc::from(".ad{display:none!important}")).await;
        assert_eq!(
            result
                .into_body()
                .collect()
                .await
                .unwrap()
                .to_bytes()
                .as_ref(),
            data.as_bytes()
        );
    }
    #[test]
    fn only_html_get_documents_are_candidates() {
        for (method, accept, dest, expected) in [
            ("GET", "text/html", "document", true),
            ("GET", "text/html,application/xhtml+xml", "iframe", true),
            ("HEAD", "text/html", "document", false),
            ("GET", "image/avif,*/*", "image", false),
            ("GET", "text/html", "empty", false),
        ] {
            let request = Request::builder()
                .method(method)
                .header("accept", accept)
                .header("sec-fetch-dest", dest)
                .body(())
                .unwrap();
            assert_eq!(document_request(&request), expected);
        }
    }
}
