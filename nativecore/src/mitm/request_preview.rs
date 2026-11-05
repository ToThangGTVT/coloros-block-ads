use crate::dns::{self, RequestPreview};
use bytes::Bytes;
use hyper::{
    body::{Body, Frame, Incoming, SizeHint},
    header::{CONTENT_ENCODING, CONTENT_TYPE},
    Request,
};
use std::{
    pin::Pin,
    task::{Context, Poll},
};

const URL_LIMIT: usize = 2048;
const BODY_LIMIT: usize = 4096;

pub(super) struct Capture {
    domain: String,
    uid: Option<u32>,
    blocked: bool,
    rule: Option<String>,
    preview: Option<RequestPreview>,
    bytes: Vec<u8>,
    complete: bool,
}

impl Capture {
    pub(super) fn start(
        request: &Request<Incoming>,
        domain: &str,
        uid: Option<u32>,
        blocked: bool,
        rule: Option<String>,
    ) -> Option<Self> {
        if let Some(session) = dns::capture_session(uid).filter(|_| dns::options().logging) {
            let path = request.uri().path();
            let query = request.uri().query();
            let shortened = |value: &str| value[..value.floor_char_boundary(URL_LIMIT)].to_owned();
            let preview = RequestPreview {
                session,
                method: request.method().as_str().to_owned(),
                path: shortened(path),
                query: query.map(shortened),
                body: None,
                url_truncated: path.len() > URL_LIMIT || query.is_some_and(|q| q.len() > URL_LIMIT),
                body_truncated: false,
            };
            let capture = Self {
                domain: domain.into(),
                uid,
                blocked,
                rule,
                preview: Some(preview),
                bytes: Vec::new(),
                complete: false,
            };
            if !blocked && !request.body().is_end_stream() && readable(request.headers()) {
                return Some(capture);
            }
            // A blocked request may never send its body; do not drain it just to log it.
            drop(capture);
        } else {
            dns::event(domain.into(), blocked, rule, false, uid, "https");
        }
        None
    }

    /// Returns false once the preview is full or capture has stopped.
    fn record(&mut self, chunk: &[u8]) -> bool {
        let Some(preview) = self.preview.as_mut() else {
            return false;
        };
        if dns::capture_session(self.uid) != Some(preview.session) {
            return false;
        }
        let len = (BODY_LIMIT - self.bytes.len()).min(chunk.len());
        self.bytes.extend_from_slice(&chunk[..len]);
        preview.body_truncated |= len < chunk.len();
        !preview.body_truncated
    }
}

impl Drop for Capture {
    fn drop(&mut self) {
        let preview = self
            .preview
            .take()
            .filter(|preview| dns::capture_session(self.uid) == Some(preview.session))
            .map(|mut preview| {
                preview.body_truncated |= !self.complete && !self.bytes.is_empty();
                preview.body = utf8_preview(&self.bytes, preview.body_truncated).map(str::to_owned);
                preview
            });
        dns::event_with_preview(
            std::mem::take(&mut self.domain),
            self.blocked,
            self.rule.take(),
            false,
            self.uid,
            "https",
            preview,
        );
    }
}

fn readable(headers: &hyper::HeaderMap) -> bool {
    if headers
        .get_all(CONTENT_ENCODING)
        .iter()
        .any(|v| !v.as_bytes().eq_ignore_ascii_case(b"identity"))
    {
        return false;
    }
    let Some(mime) = headers.get(CONTENT_TYPE).and_then(|v| v.to_str().ok()) else {
        return false;
    };
    let mime = mime
        .split(';')
        .next()
        .unwrap_or_default()
        .trim()
        .to_ascii_lowercase();
    mime.starts_with("text/")
        || matches!(
            mime.as_str(),
            "application/json"
                | "application/xml"
                | "application/x-www-form-urlencoded"
                | "application/graphql"
        )
        || mime.ends_with("+json")
        || mime.ends_with("+xml")
}

fn utf8_preview(bytes: &[u8], truncated: bool) -> Option<&str> {
    let text = match std::str::from_utf8(bytes) {
        Ok(text) => text,
        Err(err) if truncated && err.error_len().is_none() => {
            std::str::from_utf8(&bytes[..err.valid_up_to()]).ok()?
        }
        Err(_) => return None,
    };
    (!text.is_empty()).then_some(text)
}

/// Observes frames as Hyper consumes them. It keeps the original length and sends every
/// frame (including trailers and errors) unchanged.
pub(super) struct PreviewBody {
    pub inner: Incoming,
    pub capture: Option<Capture>,
}

impl Body for PreviewBody {
    type Data = Bytes;
    type Error = hyper::Error;

    fn poll_frame(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
    ) -> Poll<Option<Result<Frame<Bytes>, Self::Error>>> {
        let result = Pin::new(&mut self.inner).poll_frame(cx);
        let complete = matches!(result, Poll::Ready(None)) || self.inner.is_end_stream();
        let mut capturing = true;
        if let Some(capture) = &mut self.capture {
            if let Poll::Ready(Some(Ok(frame))) = &result {
                if let Some(data) = frame.data_ref() {
                    capturing = capture.record(data);
                }
            }
            capture.complete = complete && !matches!(result, Poll::Ready(Some(Err(_))));
        }
        if !capturing || complete || matches!(result, Poll::Ready(Some(Err(_)))) {
            self.capture.take();
        }
        result
    }

    fn size_hint(&self) -> SizeHint {
        self.inner.size_hint()
    }

    fn is_end_stream(&self) -> bool {
        self.inner.is_end_stream()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use http_body_util::{BodyExt, Full};
    use hyper_util::rt::TokioIo;
    use std::{
        convert::Infallible,
        sync::{Arc, Mutex},
    };

    #[test]
    fn accepts_text_and_rejects_binary_or_compressed_bodies() {
        let mut headers = hyper::HeaderMap::new();
        headers.insert(
            CONTENT_TYPE,
            "application/problem+json; charset=utf-8".parse().unwrap(),
        );
        assert!(readable(&headers));
        headers.insert(CONTENT_ENCODING, "gzip".parse().unwrap());
        assert!(!readable(&headers));
        headers.remove(CONTENT_ENCODING);
        headers.insert(
            CONTENT_TYPE,
            "multipart/form-data; boundary=abc".parse().unwrap(),
        );
        assert!(!readable(&headers));
    }

    #[test]
    fn a_preview_cut_in_the_middle_of_utf8_keeps_the_complete_characters() {
        assert_eq!(utf8_preview(b"ok\xe1\xba", true), Some("ok"));
        assert_eq!(utf8_preview(b"ok\xe1\xba", false), None);
        assert_eq!(utf8_preview(b"ok\xff", true), None);
        assert_eq!(utf8_preview(b"", false), None);
    }

    #[tokio::test]
    async fn logs_only_the_selected_uid_and_preserves_the_streamed_body() {
        #[derive(Default)]
        struct Sink(Mutex<Vec<String>>);
        impl dns::EventSink for Sink {
            fn batch(&self, json: &str) {
                self.0.lock().unwrap().push(json.to_owned());
            }
        }
        let sink = Arc::new(Sink::default());
        dns::set_sink(Some(sink.clone()));
        dns::set_request_capture(123, 8, 120);
        let (client, server) = tokio::io::duplex(16_384);
        let service = hyper::service::service_fn(|request: Request<Incoming>| async {
            let uid = if request.uri().path() == "/selected" {
                123
            } else {
                456
            };
            let capture = Capture::start(&request, "capture.test", Some(uid), false, None);
            let body = PreviewBody {
                inner: request.into_body(),
                capture,
            };
            let echoed = body.collect().await.unwrap().to_bytes();
            Ok::<_, Infallible>(hyper::Response::new(Full::new(echoed)))
        });
        let server = tokio::spawn(async move {
            hyper::server::conn::http1::Builder::new()
                .serve_connection(TokioIo::new(server), service)
                .await
        });
        let (mut sender, connection) = hyper::client::conn::http1::handshake(TokioIo::new(client))
            .await
            .unwrap();
        let client = tokio::spawn(connection);
        let payload = Bytes::from(vec![b'x'; BODY_LIMIT + 100]);
        for path in ["selected", "other"] {
            let request = Request::post(format!("/{path}?tag=first&tag=second"))
                .header(CONTENT_TYPE, "text/plain")
                .body(Full::new(payload.clone()))
                .unwrap();
            let echoed = sender
                .send_request(request)
                .await
                .unwrap()
                .into_body()
                .collect()
                .await
                .unwrap()
                .to_bytes();
            assert_eq!(echoed, payload);
        }
        dns::flush();
        dns::set_request_capture(-1, 9, 0);
        dns::set_sink(None);
        client.abort();
        server.abort();
        let batches = sink.0.lock().unwrap();
        let batch: serde_json::Value = serde_json::from_str(&batches[0]).unwrap();
        let events = batch["events"].as_array().unwrap();
        let preview = &events.iter().find(|e| e["uid"] == 123).unwrap()["requestPreview"];
        assert_eq!(preview["session"], 8);
        assert_eq!(preview["method"], "POST");
        assert_eq!(preview["path"], "/selected");
        assert_eq!(preview["query"], "tag=first&tag=second");
        assert_eq!(preview["body"].as_str().unwrap().len(), BODY_LIMIT);
        assert_eq!(preview["bodyTruncated"], true);
        assert!(events
            .iter()
            .find(|e| e["uid"] == 456)
            .unwrap()
            .get("requestPreview")
            .is_none());
    }
}
