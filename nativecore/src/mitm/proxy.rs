//! TLS-verified upstream and streaming HTTP/1.1 + HTTP/2 proxy with bounded HTML-head rewriting.
use super::{ca, eligible, reject_client};
use crate::{
    policy::Owner,
    sockets::{self, Protector, Stats},
};
use bytes::Bytes;
use futures_util::future::BoxFuture;
use http_body_util::{combinators::UnsyncBoxBody, BodyExt, Empty, Full};
use hyper::{body::Incoming, Request, Response, StatusCode, Uri};
use hyper_util::rt::{TokioExecutor, TokioIo};
use std::{
    convert::Infallible,
    error::Error,
    io,
    net::SocketAddr,
    pin::Pin,
    sync::{Arc, OnceLock},
    task::{Context, Poll},
    time::Duration,
};
use tokio::{
    io::{AsyncRead, AsyncWrite, ReadBuf},
    sync::Mutex,
    task::JoinHandle,
};
use tokio_rustls::{client::TlsStream, TlsAcceptor, TlsConnector};
pub(super) type BoxError = Box<dyn Error + Send + Sync>;
pub(super) type Body = UnsyncBoxBody<Bytes, BoxError>;
enum Sender {
    H1(hyper::client::conn::http1::SendRequest<Body>),
    H2(hyper::client::conn::http2::SendRequest<Body>),
}
pub type Connector =
    Arc<dyn Fn() -> BoxFuture<'static, io::Result<TlsStream<tokio::net::TcpStream>>> + Send + Sync>;
pub struct VerifiedUpstream {
    tls: TlsStream<tokio::net::TcpStream>,
    reconnect: Connector,
}
impl VerifiedUpstream {
    pub fn new(tls: TlsStream<tokio::net::TcpStream>, reconnect: Connector) -> Self {
        Self { tls, reconnect }
    }
}
struct UpstreamState {
    sender: Sender,
    // Keep draining HTTP/2 GOAWAY connections alive for their existing streams.
    drivers: Vec<Driver>,
}
struct Upstream {
    state: Mutex<UpstreamState>,
    reconnect: Connector,
}
struct Driver(JoinHandle<()>);
impl Drop for Driver {
    fn drop(&mut self) {
        self.0.abort();
    }
}
struct Prefix<S> {
    initial: Vec<u8>,
    offset: usize,
    inner: S,
}
impl<S: AsyncRead + Unpin> AsyncRead for Prefix<S> {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        if this.offset < this.initial.len() {
            let n = (this.initial.len() - this.offset).min(buf.remaining());
            buf.put_slice(&this.initial[this.offset..this.offset + n]);
            this.offset += n;
            Poll::Ready(Ok(()))
        } else {
            Pin::new(&mut this.inner).poll_read(cx, buf)
        }
    }
}
impl<S: AsyncWrite + Unpin> AsyncWrite for Prefix<S> {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &[u8],
    ) -> Poll<io::Result<usize>> {
        Pin::new(&mut self.get_mut().inner).poll_write(cx, buf)
    }
    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().inner).poll_flush(cx)
    }
    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().inner).poll_shutdown(cx)
    }
}
pub async fn upstream(
    destination: SocketAddr,
    domain: &str,
    protector: &dyn Protector,
    stats: &Arc<Stats>,
) -> io::Result<TlsStream<tokio::net::TcpStream>> {
    let raw = sockets::connect_tcp(destination, protector, stats).await?;
    let mut roots = rustls::RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    let mut config = rustls::ClientConfig::builder_with_provider(Arc::new(
        rustls::crypto::ring::default_provider(),
    ))
    .with_safe_default_protocol_versions()
    .map_err(io::Error::other)?
    .with_root_certificates(roots)
    .with_no_client_auth();
    config.alpn_protocols = vec![b"h2".to_vec(), b"http/1.1".to_vec()];
    let name =
        rustls::pki_types::ServerName::try_from(domain.to_owned()).map_err(io::Error::other)?;
    tokio::time::timeout(
        Duration::from_secs(10),
        TlsConnector::from(Arc::new(config)).connect(name, raw),
    )
    .await?
}
pub fn connector(
    destination: SocketAddr,
    domain: String,
    protector: Arc<dyn Protector>,
    stats: Arc<Stats>,
) -> Connector {
    Arc::new(move || {
        let (domain, protector, stats) = (domain.clone(), protector.clone(), stats.clone());
        Box::pin(async move { upstream(destination, &domain, protector.as_ref(), &stats).await })
    })
}
async fn connection(
    upstream: TlsStream<tokio::net::TcpStream>,
    domain: String,
    uid: Option<u32>,
) -> io::Result<(Sender, Driver)> {
    if upstream.get_ref().1.alpn_protocol() == Some(b"h2".as_slice()) {
        let (sender, connection) =
            hyper::client::conn::http2::handshake(TokioExecutor::new(), TokioIo::new(upstream))
                .await
                .map_err(io::Error::other)?;
        Ok((
            Sender::H2(sender),
            Driver(tokio::spawn(async move {
                if let Err(error) = connection.await {
                    report_error(&domain, uid, "upstream HTTP/2", &error);
                }
            })),
        ))
    } else {
        let (sender, connection) = hyper::client::conn::http1::handshake(TokioIo::new(upstream))
            .await
            .map_err(io::Error::other)?;
        Ok((
            Sender::H1(sender),
            Driver(tokio::spawn(async move {
                if let Err(error) = connection.with_upgrades().await {
                    report_error(&domain, uid, "upstream HTTP/1.1", &error);
                }
            })),
        ))
    }
}
async fn send_upstream(
    mut request: Request<Body>,
    upstream: &Upstream,
    domain: &str,
    uid: Option<u32>,
    url: &str,
    path: &str,
) -> Result<Response<Incoming>, BoxError> {
    let absolute = url.parse::<Uri>()?;
    let host = hyper::header::HeaderValue::from_str(
        absolute
            .authority()
            .ok_or_else(|| io::Error::from(io::ErrorKind::InvalidInput))?
            .as_str(),
    )?;
    let mut reconnect = false;
    for attempt in 0..2 {
        let mut state = upstream.state.lock().await;
        let ready = match &mut state.sender {
            Sender::H1(sender) => sender.ready().await,
            Sender::H2(sender) => sender.ready().await,
        };
        if reconnect || ready.is_err() {
            let tls = (upstream.reconnect)().await?;
            let (sender, driver) = connection(tls, domain.to_owned(), uid).await?;
            state.drivers.retain(|driver| !driver.0.is_finished());
            state.drivers.push(driver);
            state.sender = sender;
        }
        let h2 = matches!(state.sender, Sender::H2(_));
        *request.uri_mut() = if h2 {
            absolute.clone()
        } else {
            path.parse::<Uri>()?
        };
        if h2 {
            // HTTP/2 carries the origin in :authority, derived from the absolute URI.
            request.headers_mut().remove(hyper::header::HOST);
        } else {
            // Rebuild this on every attempt: reconnecting can negotiate a different ALPN.
            request
                .headers_mut()
                .insert(hyper::header::HOST, host.clone());
        }
        *request.version_mut() = if h2 {
            hyper::Version::HTTP_2
        } else {
            hyper::Version::HTTP_11
        };
        let result = match &mut state.sender {
            Sender::H1(sender) => sender.try_send_request(request).await,
            Sender::H2(sender) => {
                let mut sender = sender.clone();
                drop(state);
                sender.try_send_request(request).await
            }
        };
        match result {
            Ok(response) => return Ok(response),
            Err(mut error) => {
                // Hyper returns the request only if it was never serialized. Never replay a
                // request whose headers/body may have reached the origin (including POST).
                if attempt == 0 {
                    if let Some(unsent) = error.take_message() {
                        request = unsent;
                        reconnect = true;
                        continue;
                    }
                }
                return Err(Box::new(error.into_error()));
            }
        }
    }
    unreachable!()
}
fn empty(status: StatusCode) -> Response<Body> {
    let mut response = Response::new(
        Empty::<Bytes>::new()
            .map_err(|never: Infallible| match never {})
            .boxed_unsync(),
    );
    *response.status_mut() = status;
    response
}
fn report_error(domain: &str, uid: Option<u32>, stage: &str, error: &dyn Error) {
    let mut reason = error.to_string();
    let mut source = error.source();
    while let Some(cause) = source {
        reason.push_str(": ");
        reason.push_str(&cause.to_string());
        source = cause.source();
    }
    crate::dns::event(
        domain.into(),
        false,
        Some(format!("Lỗi proxy HTTPS · {stage} · {reason}")),
        false,
        uid,
        "https-error",
    );
}
fn clean_headers(headers: &mut hyper::HeaderMap) {
    let named = headers
        .get(hyper::header::CONNECTION)
        .and_then(|v| v.to_str().ok())
        .map(|v| {
            v.split(',')
                .map(|name| name.trim().to_owned())
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();
    for name in named {
        headers.remove(name);
    }
    for name in [
        "connection",
        "proxy-connection",
        "keep-alive",
        "transfer-encoding",
        "upgrade",
        "te",
        "trailer",
    ] {
        headers.remove(name);
    }
}
/// Only a certificate alert from the client means it does not trust our CA (pinning or no user
/// CA trust). Browsers routinely abandon preconnected sockets mid-handshake; treating that EOF,
/// reset or timeout as a rejection would switch filtering off for the host for hours.
fn rejects_certificate(error: &io::Error) -> bool {
    use rustls::AlertDescription as Alert;
    error
        .get_ref()
        .and_then(|inner| inner.downcast_ref::<rustls::Error>())
        .is_some_and(|error| {
            matches!(
                error,
                rustls::Error::AlertReceived(
                    Alert::BadCertificate
                        | Alert::UnsupportedCertificate
                        | Alert::CertificateRevoked
                        | Alert::CertificateExpired
                        | Alert::CertificateUnknown
                        | Alert::UnknownCA
                        | Alert::AccessDenied
                        // A stale root with the same name but another key (CA re-created).
                        | Alert::DecryptError
                )
            )
        })
}
/// Client hang-ups (EOF, reset, broken pipe during shutdown) end a session normally; only real
/// protocol/IO failures are surfaced.
fn finish(result: Result<(), hyper::Error>) -> io::Result<()> {
    let Err(error) = result else {
        return Ok(());
    };
    let mut source: Option<&(dyn Error + 'static)> = error.source();
    let mut hung_up = error.is_incomplete_message()
        || error.is_closed()
        || error.is_canceled()
        || error.is_body_write_aborted()
        || error.is_shutdown();
    while let Some(cause) = source {
        if let Some(io) = cause.downcast_ref::<io::Error>() {
            hung_up |= matches!(
                io.kind(),
                io::ErrorKind::BrokenPipe
                    | io::ErrorKind::ConnectionReset
                    | io::ErrorKind::ConnectionAborted
                    | io::ErrorKind::UnexpectedEof
                    | io::ErrorKind::NotConnected
            );
        }
        source = cause.source();
    }
    if hung_up {
        Ok(())
    } else {
        Err(io::Error::other(error))
    }
}
pub async fn serve<S: AsyncRead + AsyncWrite + Unpin + Send + 'static>(
    client: S,
    config: Arc<rustls::ServerConfig>,
    initial: Vec<u8>,
    upstream: VerifiedUpstream,
    domain: String,
    destination: SocketAddr,
    owner: Owner,
) -> io::Result<()> {
    let mut revision = super::subscribe();
    if !eligible(&domain, &owner) {
        return Err(io::ErrorKind::PermissionDenied.into());
    }
    let handshake = tokio::time::timeout(
        Duration::from_secs(10),
        TlsAcceptor::from(config).accept(Prefix {
            initial,
            offset: 0,
            inner: client,
        }),
    );
    let client = match tokio::select! { result = handshake => result, _ = revision.changed() => return Err(io::ErrorKind::Interrupted.into()) }
    {
        Ok(Ok(client)) => client,
        Ok(Err(error)) => {
            if rejects_certificate(&error) {
                reject_client(&domain, &owner);
            }
            return Err(io::ErrorKind::ConnectionAborted.into());
        }
        Err(_) => return Err(io::ErrorKind::TimedOut.into()),
    };
    let client_h2 = client.get_ref().1.alpn_protocol() == Some(b"h2".as_slice());
    let (sender, driver) = connection(upstream.tls, domain.clone(), owner.uid).await?;
    let upstream = Arc::new(Upstream {
        state: Mutex::new(UpstreamState {
            sender,
            drivers: vec![driver],
        }),
        reconnect: upstream.reconnect,
    });
    let service = hyper::service::service_fn(move |request: Request<Incoming>| {
        let upstream = upstream.clone();
        let domain = domain.clone();
        let owner = owner.clone();
        async move {
            Ok::<_, Infallible>(forward(request, upstream, &domain, destination, &owner).await)
        }
    });
    if client_h2 {
        let mut builder = hyper::server::conn::http2::Builder::new(TokioExecutor::new());
        builder
            .max_concurrent_streams(32)
            .max_header_list_size(16384)
            .max_send_buf_size(65536);
        tokio::select! {result=builder.serve_connection(TokioIo::new(client),service)=>finish(result)?,_=revision.changed()=>{}}
    } else {
        let mut builder = hyper::server::conn::http1::Builder::new();
        builder.max_buf_size(16384).max_headers(100);
        tokio::select! {result=builder.serve_connection(TokioIo::new(client),service)=>finish(result)?,_=revision.changed()=>{}}
    }
    Ok(())
}
async fn forward(
    mut request: Request<Incoming>,
    upstream: Arc<Upstream>,
    domain: &str,
    destination: SocketAddr,
    owner: &Owner,
) -> Response<Body> {
    let client_version = request.version();
    let authority = request
        .uri()
        .authority()
        .map(|a| a.host())
        .or_else(|| {
            request
                .headers()
                .get(hyper::header::HOST)
                .and_then(|v| v.to_str().ok())
                .map(|v| v.split(':').next().unwrap_or(""))
        })
        .unwrap_or("");
    if !authority.eq_ignore_ascii_case(domain) || !eligible(domain, owner) {
        return empty(StatusCode::MISDIRECTED_REQUEST);
    }
    if request.method() == hyper::Method::CONNECT
        || request.headers().contains_key(hyper::header::UPGRADE)
    {
        super::bypass(domain, owner, "HTTP Upgrade/CONNECT chưa hỗ trợ");
        return empty(StatusCode::NOT_IMPLEMENTED);
    }
    let path = request
        .uri()
        .path_and_query()
        .map_or("/", |p| p.as_str())
        .to_owned();
    let authority = if destination.port() == 443 {
        domain.to_owned()
    } else {
        format!("{domain}:{}", destination.port())
    };
    if let Some(query) = path.strip_prefix(endpoint()).and_then(|rest| {
        rest.strip_prefix('?')
            .or_else(|| rest.is_empty().then_some(""))
    }) {
        return generic_css(domain, query);
    }
    let url = format!("https://{authority}{path}");
    let rules = crate::filter::current();
    let (kind, source) = request_context(request.headers(), &url);
    let context = crate::filter::Context {
        url: &url,
        source: &source,
        kind,
        method: request.method().as_str(),
    };
    let decision = rules.check(domain, &context, &owner.packages);
    let blocked = decision.as_ref().is_some_and(|d| d.blocked);
    let (redirect, rewritten) = decision
        .as_ref()
        .map_or((None, None), |d| (d.redirect.clone(), d.rewritten.clone()));
    crate::dns::event(
        domain.into(),
        blocked,
        decision.map(|d| d.rule),
        false,
        owner.uid,
        "https",
    );
    if blocked {
        // `$redirect`: a harmless stand-in (empty script, 1x1 image, ad library stub) keeps
        // pages that depend on the blocked resource working.
        return redirect
            .as_deref()
            .and_then(|data| resource_response(data, request.headers()))
            .unwrap_or_else(|| empty(StatusCode::NO_CONTENT));
    }
    // `$removeparam`: forward without the tracking parameters. Same origin only.
    let origin = format!("https://{authority}");
    let (url, path) = match rewritten.as_deref().and_then(|u| {
        u.strip_prefix(&origin)
            .filter(|p| p.starts_with('/'))
            .map(|p| (u.to_owned(), p.to_owned()))
    }) {
        Some(rewritten) => rewritten,
        None => (url, path),
    };
    let page = super::html::document_request(&request)
        .then(|| rules.cosmetic_page(domain))
        .filter(|page| !page.is_empty());
    if page.is_some() {
        // Only codings the rewriter can decode; anything else would pass through unmodified.
        request.headers_mut().insert(
            hyper::header::ACCEPT_ENCODING,
            hyper::header::HeaderValue::from_static(super::html::ACCEPT_ENCODING),
        );
        for name in ["if-none-match", "if-modified-since"] {
            request.headers_mut().remove(name);
        }
    }
    clean_headers(request.headers_mut());
    let Ok(host) = hyper::header::HeaderValue::from_str(&authority) else {
        return empty(StatusCode::BAD_REQUEST);
    };
    request.headers_mut().insert(hyper::header::HOST, host);
    let request = request.map(|body| body.map_err(|e| -> BoxError { Box::new(e) }).boxed_unsync());
    let response = tokio::time::timeout(
        Duration::from_secs(30),
        send_upstream(request, &upstream, domain, owner.uid, &url, &path),
    )
    .await;
    match response {
        Ok(Ok(mut response)) => {
            *response.version_mut() = client_version;
            clean_headers(response.headers_mut());
            let response =
                response.map(|body| body.map_err(|e| -> BoxError { Box::new(e) }).boxed_unsync());
            match page {
                Some(page) => super::html::inject(response, &page, endpoint()).await,
                None => response,
            }
        }
        Ok(Err(error)) => {
            report_error(domain, owner.uid, "request", error.as_ref());
            empty(StatusCode::BAD_GATEWAY)
        }
        Err(_) => {
            crate::dns::event(
                domain.into(),
                false,
                Some("Lỗi proxy HTTPS · upstream quá thời gian chờ".into()),
                false,
                owner.uid,
                "https-error",
            );
            empty(StatusCode::GATEWAY_TIMEOUT)
        }
    }
}
/// Answers a blocked request with a `data:` URL resource from adblock-rust.
fn resource_response(data: &str, request: &hyper::HeaderMap) -> Option<Response<Body>> {
    use base64::Engine as _;
    let (mime, content) = data.strip_prefix("data:")?.split_once(";base64,")?;
    let content = base64::engine::general_purpose::STANDARD
        .decode(content)
        .ok()?;
    let mut response = Response::new(
        Full::new(Bytes::from(content))
            .map_err(|never: Infallible| match never {})
            .boxed_unsync(),
    );
    let headers = response.headers_mut();
    headers.insert(
        hyper::header::CONTENT_TYPE,
        hyper::header::HeaderValue::from_str(mime).ok()?,
    );
    headers.insert(
        "cache-control",
        hyper::header::HeaderValue::from_static("no-store"),
    );
    headers.insert(
        "x-content-type-options",
        hyper::header::HeaderValue::from_static("nosniff"),
    );
    // Stand-ins carry no data, so CORS requests (crossorigin scripts, fetch) may read them.
    if let Some(origin) = request.get(hyper::header::ORIGIN) {
        headers.insert("access-control-allow-origin", origin.clone());
        headers.insert(
            "access-control-allow-credentials",
            hyper::header::HeaderValue::from_static("true"),
        );
        headers.insert("vary", hyper::header::HeaderValue::from_static("Origin"));
    }
    Some(response)
}
/// Same-origin path the content script loads generic CSS from. Random per process so it cannot
/// collide with a site's own paths; answered here and never forwarded to the origin.
fn endpoint() -> &'static str {
    static PATH: OnceLock<String> = OnceLock::new();
    PATH.get_or_init(|| {
        let mut random = [0u8; 16];
        let _ = ring::rand::SecureRandom::fill(&ring::rand::SystemRandom::new(), &mut random);
        let token: String = random.iter().map(|b| format!("{b:02x}")).collect();
        format!("/.blockads-{token}/generic.css")
    })
}
fn decode(value: &str) -> String {
    let bytes = value.as_bytes();
    let mut output = Vec::with_capacity(bytes.len());
    let mut at = 0;
    while at < bytes.len() {
        let hex = |c: u8| (c as char).to_digit(16);
        match bytes[at] {
            b'%' if at + 2 < bytes.len() => match (hex(bytes[at + 1]), hex(bytes[at + 2])) {
                (Some(high), Some(low)) => {
                    output.push((high * 16 + low) as u8);
                    at += 3;
                    continue;
                }
                _ => output.push(b'%'),
            },
            b'+' => output.push(b' '),
            byte => output.push(byte),
        }
        at += 1;
    }
    String::from_utf8_lossy(&output).into_owned()
}
/// `?c=<classes>&i=<ids>`, space separated. Bounded so a page cannot make the proxy do much work.
fn generic_css(domain: &str, query: &str) -> Response<Body> {
    let (mut classes, mut ids) = (String::new(), String::new());
    for pair in query.split('&') {
        match pair.split_once('=') {
            Some(("c", value)) => classes = decode(value),
            Some(("i", value)) => ids = decode(value),
            _ => {}
        }
    }
    let tokens = |text: &str| -> Vec<String> {
        text.split(' ')
            .filter(|t| !t.is_empty() && t.len() <= 256)
            .take(4096)
            .map(str::to_owned)
            .collect()
    };
    let (classes, ids) = (tokens(&classes), tokens(&ids));
    let rules = crate::filter::current();
    let page = rules.cosmetic_page(domain);
    let css = rules.generic_css(
        &page,
        &classes.iter().map(String::as_str).collect::<Vec<_>>(),
        &ids.iter().map(String::as_str).collect::<Vec<_>>(),
    );
    let mut response = Response::new(
        Full::new(Bytes::from(css))
            .map_err(|never: Infallible| match never {})
            .boxed_unsync(),
    );
    let headers = response.headers_mut();
    for (name, value) in [
        ("content-type", "text/css; charset=utf-8"),
        ("cache-control", "no-store"),
        ("x-content-type-options", "nosniff"),
    ] {
        headers.insert(name, hyper::header::HeaderValue::from_static(value));
    }
    response
}
/// Request type and initiator for network rules, from Fetch Metadata (Chrome sends it on every
/// HTTPS request) with an Accept fallback. Chrome's default referrer policy still exposes the
/// initiating origin cross-site, which is enough for `$third-party` and `$domain=`.
fn request_context(headers: &hyper::HeaderMap, url: &str) -> (&'static str, String) {
    let header = |name: &str| headers.get(name).and_then(|v| v.to_str().ok());
    let kind = match header("sec-fetch-dest") {
        Some("document") => "document",
        Some("iframe" | "frame" | "fencedframe") => "subdocument",
        Some(
            "script" | "worker" | "sharedworker" | "serviceworker" | "audioworklet"
            | "paintworklet",
        ) => "script",
        Some("style") => "stylesheet",
        Some("image") => "image",
        Some("font") => "font",
        Some("audio" | "video" | "track") => "media",
        Some("object" | "embed") => "object",
        Some("report") => "csp_report",
        Some("empty") if header("sec-fetch-mode") == Some("websocket") => "websocket",
        Some("empty") => "xmlhttprequest",
        Some(_) => "other",
        None => {
            let accept = header("accept").unwrap_or("");
            if accept.starts_with("text/html") {
                "document"
            } else if accept.starts_with("image/") {
                "image"
            } else if accept.starts_with("text/css") {
                "stylesheet"
            } else {
                "other"
            }
        }
    };
    let source = if kind == "document" {
        // A top-level navigation is its own first party.
        url.to_owned()
    } else if let Some(referer) = header("referer") {
        referer.to_owned()
    } else if let Some(origin) = header("origin").filter(|o| *o != "null") {
        format!("{origin}/")
    } else if matches!(
        header("sec-fetch-site"),
        Some("same-origin" | "same-site" | "none")
    ) {
        url.to_owned()
    } else {
        // Unknown initiator: treated as third-party; `$domain=` rules cannot match.
        String::new()
    };
    (kind, source)
}

pub async fn prepare(domain: &str) -> io::Result<Arc<rustls::ServerConfig>> {
    let authority = ca::authority().ok_or(io::ErrorKind::NotFound)?;
    let domain = domain.to_owned();
    tokio::task::spawn_blocking(move || authority.server(&domain))
        .await
        .map_err(io::Error::other)?
}
