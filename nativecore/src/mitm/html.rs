//! Streaming HTML-head rewrite with bounded token memory. Inserts the page's CSS and, when generic
//! or procedural rules apply, the small content script in `html/content.js`.
mod scanner;
use super::proxy::Body;
use crate::filter::Page;
use bytes::Bytes;
use futures_util::{stream, StreamExt};
use http_body_util::{BodyExt, BodyStream, StreamBody};
use hyper::{body::Frame, header::HeaderValue, HeaderMap, Request, Response};
use ring::rand::{SecureRandom, SystemRandom};

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

const CONTENT: &str = include_str!("html/content.js");
/// `endpoint` is the same-origin path the proxy answers locally with generic class/id CSS.
pub(super) async fn inject(
    response: Response<Body>,
    page: &Page,
    endpoint: &str,
) -> Response<Body> {
    let script = (page.generic || !page.procedural.is_empty()).then(|| {
        let procedural = if page.procedural.is_empty() {
            "[]"
        } else {
            &page.procedural
        };
        format!(
            "{}({endpoint:?},{},{procedural})",
            CONTENT.trim_end(),
            page.generic
        )
    });
    rewrite(response, &page.css, script).await
}
async fn rewrite(response: Response<Body>, css: &str, script: Option<String>) -> Response<Body> {
    if (css.is_empty() && script.is_none()) || !eligible(&response) {
        return response;
    }
    let mut random = [0u8; 16];
    if SystemRandom::new().fill(&mut random).is_err() {
        return response;
    }
    let nonce: String = random.iter().map(|b| format!("{b:02x}")).collect();
    // eligible() already rejected unsupported charsets; a declared one lets the browser decode
    // without prescanning the head, so the snippet can go first.
    let early = response
        .headers()
        .get("content-type")
        .and_then(|v| v.to_str().ok())
        .is_some_and(|v| {
            v.split(';')
                .skip(1)
                .any(|p| p.trim().to_ascii_lowercase().starts_with("charset="))
        });
    let Some(csp) = csp_headers(response.headers(), &nonce, script.is_some()) else {
        return response;
    };
    let (mut parts, body) = response.into_parts();
    // Headers are sent before the head finishes streaming. All rewrite candidates must
    // therefore discard byte-dependent metadata, even if malformed markup later passes through.
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
    parts
        .headers
        .insert("cache-control", HeaderValue::from_static("no-store"));
    for (name, values) in csp {
        parts.headers.remove(name);
        for value in values {
            parts.headers.append(name, value);
        }
    }
    let mut snippet = String::new();
    if !css.is_empty() {
        snippet.push_str(&format!(
            "<style nonce=\"{nonce}\" data-blockads>\n{css}</style>"
        ));
    }
    if let Some(script) = script {
        snippet.push_str(&format!(
            "<script nonce=\"{nonce}\" data-blockads>{script}</script>"
        ));
    }
    let mut scanner = scanner::Scanner::new(Bytes::from(snippet), early);
    let frames = BodyStream::new(body)
        .map(Some)
        .chain(stream::once(async { None }))
        .flat_map(move |frame| {
            let mut output = [None, None];
            let mut push = |frame| {
                if output[0].is_none() {
                    output[0] = Some(frame);
                } else {
                    output[1] = Some(frame);
                }
            };
            match frame {
                Some(Ok(frame)) => match frame.into_data() {
                    Ok(data) => {
                        let data = scanner.write(data);
                        if !data.is_empty() {
                            push(Ok(Frame::data(data)));
                        }
                    }
                    Err(mut frame) => {
                        let pending = scanner.finish();
                        if !pending.is_empty() {
                            push(Ok(Frame::data(pending)));
                        }
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
                        push(Ok(frame));
                    }
                },
                terminal => {
                    let pending = scanner.finish();
                    if !pending.is_empty() {
                        push(Ok(Frame::data(pending)));
                    }
                    if let Some(Err(error)) = terminal {
                        push(Err(error));
                    }
                }
            }
            stream::iter(output.into_iter().flatten())
        });
    Response::from_parts(parts, StreamBody::new(frames).boxed_unsync())
}

type CspHeaders = Vec<(&'static str, Vec<HeaderValue>)>;
/// Lets the injected element through one policy: copies the sources governing `elem` (falling
/// back like the browser does) into `elem` plus our nonce. A policy which already permits inline
/// elements is left alone, since adding a nonce would disable the page's other inline elements.
fn allow_nonce(directives: &mut Vec<String>, chain: [&str; 3], nonce: &str) {
    let Some(sources) = chain.iter().find_map(|name| {
        directives.iter().find_map(|d| {
            let mut words = d.split_whitespace();
            words
                .next()
                .filter(|key| key.eq_ignore_ascii_case(name))
                .map(|_| words.map(str::to_owned).collect::<Vec<_>>())
        })
    }) else {
        return;
    };
    if sources
        .iter()
        .any(|s| s.eq_ignore_ascii_case("'unsafe-inline'"))
        && !sources.iter().any(|s| {
            s.get(..7)
                .is_some_and(|s| s.eq_ignore_ascii_case("'nonce-"))
                || s.get(..4).is_some_and(|s| s.eq_ignore_ascii_case("'sha"))
                || s.eq_ignore_ascii_case("'strict-dynamic'")
        })
    {
        return;
    }
    directives.retain(|d| {
        !d.split_whitespace()
            .next()
            .is_some_and(|k| k.eq_ignore_ascii_case(chain[0]))
    });
    let mut directive = chain[0].to_owned();
    for source in sources {
        if !source.eq_ignore_ascii_case("'none'") {
            directive.push(' ');
            directive.push_str(&source);
        }
    }
    directive.push_str(&format!(" 'nonce-{nonce}'"));
    directives.push(directive);
}
fn csp_headers(headers: &HeaderMap, nonce: &str, script: bool) -> Option<CspHeaders> {
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
                    let mut directives: Vec<String> = policy
                        .split(';')
                        .map(str::trim)
                        .filter(|d| !d.is_empty())
                        .map(str::to_owned)
                        .collect();
                    let original = directives.clone();
                    allow_nonce(
                        &mut directives,
                        ["style-src-elem", "style-src", "default-src"],
                        nonce,
                    );
                    if script {
                        allow_nonce(
                            &mut directives,
                            ["script-src-elem", "script-src", "default-src"],
                            nonce,
                        );
                    }
                    if directives == original {
                        policy.to_owned()
                    } else {
                        directives.join("; ")
                    }
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

#[cfg(test)]
#[derive(Debug, PartialEq)]
enum Head {
    More,
    End(usize),
    Skip,
}
#[cfg(test)]
fn head_end(input: &[u8]) -> Head {
    let mut scanner = scanner::Scanner::new(Bytes::from_static(b"INJECT"), false);
    let output = scanner.write(Bytes::copy_from_slice(input));
    if scanner.inserted {
        Head::End(output.windows(6).position(|v| v == b"INJECT").unwrap())
    } else if scanner.stopped {
        Head::Skip
    } else {
        Head::More
    }
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
        // Without a header charset the snippet follows the charset declaration.
        let after_charset = genk.windows(3).position(|w| w == b"\"><").unwrap() + 2;
        assert_eq!(head_end(genk), Head::End(after_charset));
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
        let result = csp_headers(&headers, "newnonce", false).unwrap();
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
    #[test]
    fn content_script_cannot_close_its_element() {
        assert!(!CONTENT.contains("</"));
        assert!(!CONTENT.contains("<!--"));
    }
    #[tokio::test]
    async fn script_gets_its_own_nonce_and_csp_source() {
        let mut document = response(b"<html><head></head><body></body></html>");
        document.headers_mut().insert(
            "content-security-policy",
            HeaderValue::from_static(
                "default-src 'none'; script-src 'strict-dynamic' 'unsafe-inline' 'nonce-site'",
            ),
        );
        let page = Page {
            css: ".ad{display:none!important}\n".into(),
            procedural: "[{\"selector\":[{\"type\":\"css-selector\",\"arg\":\".x\"}],\"action\":{\"type\":\"remove\"}}]".into(),
            generic: true,
            exceptions: Default::default(),
        };
        let result = inject(document, &page, "/.blockads-token/generic.css").await;
        let csp = result.headers()["content-security-policy"]
            .to_str()
            .unwrap()
            .to_owned();
        let bytes = result.into_body().collect().await.unwrap().to_bytes();
        let text = std::str::from_utf8(&bytes).unwrap();
        let nonce = text
            .split("<script nonce=\"")
            .nth(1)
            .unwrap()
            .split('"')
            .next()
            .unwrap();
        assert!(text.contains(&format!("<style nonce=\"{nonce}\" data-blockads>")));
        assert!(text.contains("(\"/.blockads-token/generic.css\",true,[{"));
        assert!(text.ends_with("</script></head><body></body></html>"));
        // 'strict-dynamic' ignores 'unsafe-inline', so the injected script needs the nonce.
        assert!(csp.contains(&format!(
            "script-src-elem 'strict-dynamic' 'unsafe-inline' 'nonce-site' 'nonce-{nonce}'"
        )));
        assert!(csp.contains(&format!("style-src-elem 'nonce-{nonce}'")));
        let plain = Page {
            css: ".ad{}".into(),
            ..Default::default()
        };
        let result = inject(response(b"<head></head>"), &plain, "/x").await;
        let bytes = result.into_body().collect().await.unwrap().to_bytes();
        assert!(!std::str::from_utf8(&bytes).unwrap().contains("<script"));
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
        let response = rewrite(response, ".ad{display:none!important}\n", None).await;
        assert!(!response.headers().contains_key("content-length"));
        let collected = response.into_body().collect().await.unwrap();
        assert_eq!(collected.trailers().unwrap()["x-end"], "yes");
        assert!(!collected.trailers().unwrap().contains_key("content-digest"));
        let text = String::from_utf8(collected.to_bytes().to_vec()).unwrap();
        assert!(text.starts_with("<html><head><meta charset=utf-8><style nonce="));
        assert!(text.ends_with("</style></head><body>content</body></html>"));
    }
    #[tokio::test]
    async fn head_is_delivered_before_the_origin_finishes_sending_it() {
        let frames = stream::iter([Ok(Frame::data(Bytes::from_static(b"<head><style>")))]).chain(
            stream::pending::<Result<Frame<Bytes>, super::super::proxy::BoxError>>(),
        );
        let response = Response::builder()
            .header("content-type", "text/html")
            .body(StreamBody::new(frames).boxed_unsync())
            .unwrap();
        let mut result = tokio::time::timeout(
            std::time::Duration::from_millis(100),
            rewrite(response, ".ad{display:none!important}", None),
        )
        .await
        .unwrap();
        let frame = tokio::time::timeout(
            std::time::Duration::from_millis(100),
            result.body_mut().frame(),
        )
        .await
        .unwrap()
        .unwrap()
        .unwrap();
        assert_eq!(frame.into_data().unwrap(), "<head><style>");
    }
    #[tokio::test]
    async fn multi_megabyte_head_is_rewritten_without_changing_content() {
        let original = format!(
            "<!doctype html><html><head><script>{}</script></head><body><p>article</p></body></html>",
            " ".repeat(2 * 1024 * 1024)
        );
        // Charset in the header: right after <head>. Otherwise, with no meta charset, the
        // whole 2 MiB head streams through before the snippet goes in front of </head>.
        for (content_type, before) in [
            ("text/html; charset=utf-8", "<head><style nonce="),
            ("text/html", "</script><style nonce="),
        ] {
            let response = Response::builder()
                .header("content-type", content_type)
                .header("content-length", original.len())
                .body(
                    Full::new(Bytes::from(original.clone()))
                        .map_err(|never: Infallible| match never {})
                        .boxed_unsync(),
                )
                .unwrap();
            let result = rewrite(response, ".ad{display:none!important}", None).await;
            assert!(!result.headers().contains_key("content-length"));
            let bytes = result.into_body().collect().await.unwrap().to_bytes();
            let text = std::str::from_utf8(&bytes).unwrap();
            let start = text.find("<style nonce=").unwrap();
            let end = text[start..].find("</style>").unwrap() + start + "</style>".len();
            assert_eq!(format!("{}{}", &text[..start], &text[end..]), original);
            assert!(text.contains(before), "{content_type}");
            assert!(text.contains(".ad{display:none!important}"));
        }
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
            let result = rewrite(response, ".ad{display:none!important}", None).await;
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
        // Without a header charset, documents the head tokenizer cannot vouch for pass through.
        for data in [
            b"<body>no head</body>".as_slice(),
            b"<head><meta http-equiv='Content-Security-Policy'></head>",
            b"<head><script>unfinished",
        ] {
            let mut response = response(data);
            response
                .headers_mut()
                .insert("content-type", HeaderValue::from_static("text/html"));
            let result = rewrite(response, ".ad{display:none!important}", None).await;
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
