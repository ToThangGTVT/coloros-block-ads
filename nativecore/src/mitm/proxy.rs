//! TLS-verified upstream and streaming HTTP/1.1 + HTTP/2 proxy with bounded HTML-head rewriting.
use super::{ca, eligible, reject_client};
use crate::{
    policy::Owner,
    sockets::{self, Protector, Stats},
};
use bytes::Bytes;
use http_body_util::{combinators::UnsyncBoxBody, BodyExt, Empty};
use hyper::{body::Incoming, Request, Response, StatusCode, Uri};
use hyper_util::rt::{TokioExecutor, TokioIo};
use std::{
    convert::Infallible,
    error::Error,
    io,
    net::SocketAddr,
    pin::Pin,
    sync::Arc,
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
fn empty(status: StatusCode) -> Response<Body> {
    let mut response = Response::new(
        Empty::<Bytes>::new()
            .map_err(|never: Infallible| match never {})
            .boxed_unsync(),
    );
    *response.status_mut() = status;
    response
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
    upstream: TlsStream<tokio::net::TcpStream>,
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
        _ => {
            reject_client(&domain, &owner);
            return Err(io::ErrorKind::ConnectionAborted.into());
        }
    };
    let client_h2 = client.get_ref().1.alpn_protocol() == Some(b"h2".as_slice());
    let upstream_h2 = upstream.get_ref().1.alpn_protocol() == Some(b"h2".as_slice());
    let (sender, driver) = if upstream_h2 {
        let (sender, connection) =
            hyper::client::conn::http2::handshake(TokioExecutor::new(), TokioIo::new(upstream))
                .await
                .map_err(io::Error::other)?;
        (
            Sender::H2(sender),
            Driver(tokio::spawn(async move {
                let _ = connection.await;
            })),
        )
    } else {
        let (sender, connection) = hyper::client::conn::http1::handshake(TokioIo::new(upstream))
            .await
            .map_err(io::Error::other)?;
        (
            Sender::H1(sender),
            Driver(tokio::spawn(async move {
                let _ = connection.with_upgrades().await;
            })),
        )
    };
    let sender = Arc::new(Mutex::new(sender));
    let service = hyper::service::service_fn(move |request: Request<Incoming>| {
        let sender = sender.clone();
        let domain = domain.clone();
        let owner = owner.clone();
        async move {
            Ok::<_, Infallible>(
                forward(request, sender, &domain, destination, &owner, upstream_h2).await,
            )
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
    drop(driver);
    Ok(())
}
async fn forward(
    mut request: Request<Incoming>,
    sender: Arc<Mutex<Sender>>,
    domain: &str,
    destination: SocketAddr,
    owner: &Owner,
    upstream_h2: bool,
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
    let url = format!("https://{authority}{path}");
    let rules = crate::filter::current();
    let decision = rules.matches(domain, Some(&url), &owner.packages);
    let blocked = decision.as_ref().is_some_and(|d| d.blocked);
    crate::dns::event(
        domain.into(),
        blocked,
        decision.map(|d| d.rule),
        false,
        owner.uid,
        "https",
    );
    if blocked {
        return empty(StatusCode::NO_CONTENT);
    }
    let css = if super::html::document_request(&request) {
        rules.cosmetic_css(domain)
    } else {
        Arc::from("")
    };
    if !css.is_empty() {
        // Avoid decompression/recompression work. Noncompliant compressed responses pass through.
        request.headers_mut().insert(
            hyper::header::ACCEPT_ENCODING,
            hyper::header::HeaderValue::from_static("identity"),
        );
        for name in ["if-none-match", "if-modified-since"] {
            request.headers_mut().remove(name);
        }
    }
    let uri = if upstream_h2 {
        url.parse::<Uri>()
    } else {
        path.parse::<Uri>()
    };
    let Ok(uri) = uri else {
        return empty(StatusCode::BAD_REQUEST);
    };
    *request.uri_mut() = uri;
    *request.version_mut() = if upstream_h2 {
        hyper::Version::HTTP_2
    } else {
        hyper::Version::HTTP_11
    };
    clean_headers(request.headers_mut());
    let Ok(host) = hyper::header::HeaderValue::from_str(&authority) else {
        return empty(StatusCode::BAD_REQUEST);
    };
    request.headers_mut().insert(hyper::header::HOST, host);
    let request = request.map(|body| body.map_err(|e| -> BoxError { Box::new(e) }).boxed_unsync());
    let mut guard = sender.lock().await;
    let response = match &mut *guard {
        Sender::H1(sender) => {
            tokio::time::timeout(Duration::from_secs(30), async {
                sender.ready().await?;
                sender.send_request(request).await
            })
            .await
        }
        Sender::H2(sender) => {
            let mut sender = sender.clone();
            drop(guard);
            tokio::time::timeout(Duration::from_secs(30), async {
                sender.ready().await?;
                sender.send_request(request).await
            })
            .await
        }
    };
    match response {
        Ok(Ok(mut response)) => {
            *response.version_mut() = client_version;
            clean_headers(response.headers_mut());
            let response =
                response.map(|body| body.map_err(|e| -> BoxError { Box::new(e) }).boxed_unsync());
            super::html::inject(response, css).await
        }
        Ok(Err(error)) => {
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
                Some(format!("Lỗi proxy HTTPS · {reason}")),
                false,
                owner.uid,
                "https-error",
            );
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

pub async fn prepare(domain: &str) -> io::Result<Arc<rustls::ServerConfig>> {
    let authority = ca::authority().ok_or(io::ErrorKind::NotFound)?;
    let domain = domain.to_owned();
    tokio::task::spawn_blocking(move || authority.server(&domain))
        .await
        .map_err(io::Error::other)?
}
