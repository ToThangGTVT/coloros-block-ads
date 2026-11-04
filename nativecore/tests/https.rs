use blockads_core::{
    filter,
    mitm::{self, ca, proxy},
    policy::Owner,
    sockets::{self, Protector, Stats},
};
use bytes::Bytes;
use http_body_util::{BodyExt, Empty, Full};
use hyper::{Request, Response, StatusCode};
use hyper_util::rt::{TokioExecutor, TokioIo};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName};
use std::{
    convert::Infallible,
    io,
    sync::{
        atomic::{AtomicUsize, Ordering},
        Arc,
    },
    time::Duration,
};
use tokio_rustls::{TlsAcceptor, TlsConnector};
struct Guard(AtomicUsize);
impl Protector for Guard {
    fn protect(&self, _: i32) -> io::Result<()> {
        self.0.fetch_add(1, Ordering::Relaxed);
        Ok(())
    }
}
fn root() -> (Arc<rcgen::KeyPair>, Vec<u8>) {
    let key = Arc::new(rcgen::KeyPair::generate().unwrap());
    let der = ca::generate(key.clone()).unwrap();
    (key, der)
}
fn owner() -> Owner {
    Owner {
        uid: Some(123),
        packages: vec!["test.browser".into()],
        bypass: false,
        blocked: false,
    }
}
fn enable() {
    assert!(mitm::configure(
        r#"{"enabled":true,"apps":["test.browser"]}"#
    ));
}
fn client_config(root: &[u8], h2: bool) -> Arc<rustls::ClientConfig> {
    let mut roots = rustls::RootCertStore::empty();
    roots.add(CertificateDer::from(root.to_vec())).unwrap();
    let mut config = rustls::ClientConfig::builder_with_provider(Arc::new(
        rustls::crypto::ring::default_provider(),
    ))
    .with_safe_default_protocol_versions()
    .unwrap()
    .with_root_certificates(roots)
    .with_no_client_auth();
    config.alpn_protocols = vec![if h2 {
        b"h2".to_vec()
    } else {
        b"http/1.1".to_vec()
    }];
    Arc::new(config)
}
fn peer_config(domain: &str, h2: bool) -> (Vec<u8>, Arc<rustls::ServerConfig>) {
    let rcgen::CertifiedKey { cert, signing_key } =
        rcgen::generate_simple_self_signed(vec![domain.into()]).unwrap();
    let mut config = rustls::ServerConfig::builder_with_provider(Arc::new(
        rustls::crypto::ring::default_provider(),
    ))
    .with_safe_default_protocol_versions()
    .unwrap()
    .with_no_client_auth()
    .with_single_cert(
        vec![cert.der().clone()],
        PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(signing_key.serialize_der())),
    )
    .unwrap();
    config.alpn_protocols = vec![if h2 {
        b"h2".to_vec()
    } else {
        b"http/1.1".to_vec()
    }];
    (cert.der().to_vec(), Arc::new(config))
}
#[test]
fn default_off_scope_sensitive_domains_cache_and_rejected_client_bypass() {
    let (key, der) = root();
    ca::set(Some(key), der);
    mitm::configure("{}");
    assert!(!mitm::eligible("scope.test", &owner()));
    enable();
    assert!(mitm::eligible("sub.scope.test", &owner()));
    assert!(mitm::eligible("another-website.test", &owner()));
    let mut other = owner();
    other.packages = vec!["unselected.app".into()];
    assert!(!mitm::eligible("scope.test", &other));
    assert!(mitm::configure(
        r#"{"enabled":true,"apps":["test.browser"],"domains":["legacy.test"],"excluded":["scope.test"]}"#
    ));
    assert!(
        mitm::eligible("scope.test", &owner()),
        "legacy domain settings must not narrow app scope"
    );
    assert!(!mitm::eligible("sub.scope.test", &Owner::default()));
    for domain in [
        "bank.scope.test",
        "payments.scope.test",
        "health.scope.test",
        "paypal.com",
        "vietcombank.com.vn",
    ] {
        assert!(mitm::sensitive(domain));
        assert!(!mitm::eligible(domain, &owner()));
    }
    let authority = ca::authority().unwrap();
    assert!(Arc::ptr_eq(
        &authority.server("scope.test").unwrap(),
        &authority.server("scope.test").unwrap()
    ));
    mitm::reject_client("scope.test", &owner());
    assert!(!mitm::eligible("scope.test", &owner()));
    mitm::configure("{}");
    assert!(!mitm::eligible("another-website.test", &owner()));
    ca::set(None, vec![]);
}
#[tokio::test]
async fn upstream_verification_rejects_untrusted_peer_after_socket_protection() {
    let (_, server_config) = peer_config("untrusted.test", false);
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let server = tokio::spawn(async move {
        let (raw, _) = listener.accept().await.unwrap();
        assert!(TlsAcceptor::from(server_config).accept(raw).await.is_err());
    });
    let guard = Guard(AtomicUsize::new(0));
    assert!(proxy::upstream(
        address,
        "untrusted.test",
        &guard,
        &Arc::new(Stats::default())
    )
    .await
    .is_err());
    assert_eq!(guard.0.load(Ordering::Relaxed), 1);
    server.await.unwrap();
}
enum Client {
    H1(hyper::client::conn::http1::SendRequest<Empty<Bytes>>),
    H2(hyper::client::conn::http2::SendRequest<Empty<Bytes>>),
}
async fn request(client: &mut Client, domain: &str, path: &str) -> Response<hyper::body::Incoming> {
    let h2 = matches!(client, Client::H2(_));
    let uri = if h2 {
        format!("https://{domain}{path}")
    } else {
        path.to_owned()
    };
    let request = Request::builder()
        .uri(uri)
        .header("Host", domain)
        .header("Accept", "text/html")
        .header("Sec-Fetch-Dest", "document")
        .body(Empty::<Bytes>::new())
        .unwrap();
    match client {
        Client::H1(sender) => {
            sender.ready().await.unwrap();
            sender.send_request(request).await.unwrap()
        }
        Client::H2(sender) => {
            sender.ready().await.unwrap();
            sender.send_request(request).await.unwrap()
        }
    }
}
#[tokio::test]
async fn http1_http2_translation_filters_paths_preserves_keepalive_and_streams_bodies() {
    for client_h2 in [false, true] {
        for upstream_h2 in [false, true] {
            let domain = format!(
                "bridge{}{}.test",
                u8::from(client_h2),
                u8::from(upstream_h2)
            );
            let (key, root) = root();
            ca::set(Some(key), root.clone());
            enable();
            assert!(filter::replace("", &format!("||{domain}/ads/*\n{domain}##.banner")).applied);
            let (peer_root, server_config) = peer_config(&domain, upstream_h2);
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            let address = listener.local_addr().unwrap();
            let count = Arc::new(AtomicUsize::new(0));
            let requests = count.clone();
            let server = tokio::spawn(async move {
                let (raw, _) = listener.accept().await.unwrap();
                let tls = TlsAcceptor::from(server_config).accept(raw).await.unwrap();
                let service = hyper::service::service_fn(
                    move |request: Request<hyper::body::Incoming>| {
                        requests.fetch_add(1, Ordering::Relaxed);
                        async move {
                            if request.uri().path() == "/html" {
                                assert_eq!(request.headers()["accept-encoding"], "identity");
                                return Ok::<_, Infallible>(Response::builder()
                                    .header("content-type", "text/html; charset=utf-8")
                                    .header("etag", "\"original\"")
                                    .header("content-security-policy", "default-src 'none'; script-src 'self'; style-src 'self'")
                                    .body(Full::new(Bytes::from_static(b"<!doctype html><html><head><script>const fake='</head>';</script></head><body><div class='banner'>ad</div><p>content</p></body></html>"))).unwrap());
                            }
                            Ok::<_, Infallible>(Response::new(Full::new(Bytes::from(
                                vec![7; 256 * 1024],
                            ))))
                        }
                    },
                );
                if upstream_h2 {
                    let _ = hyper::server::conn::http2::Builder::new(TokioExecutor::new())
                        .serve_connection(TokioIo::new(tls), service)
                        .await;
                } else {
                    let _ = hyper::server::conn::http1::Builder::new()
                        .serve_connection(TokioIo::new(tls), service)
                        .await;
                }
            });
            let stats = Arc::new(Stats::default());
            let guard = Guard(AtomicUsize::new(0));
            let raw = sockets::connect_tcp(address, &guard, &stats).await.unwrap();
            let verified = TlsConnector::from(client_config(&peer_root, upstream_h2))
                .connect(ServerName::try_from(domain.clone()).unwrap(), raw)
                .await
                .unwrap();
            let (front, back) = tokio::io::duplex(65536);
            let config = proxy::prepare(&domain).await.unwrap();
            let proxy = tokio::spawn(proxy::serve(
                back,
                config,
                vec![],
                verified,
                domain.clone(),
                address,
                owner(),
            ));
            let tls = TlsConnector::from(client_config(&root, client_h2))
                .connect(ServerName::try_from(domain.clone()).unwrap(), front)
                .await
                .unwrap();
            let (mut client, driver) = if client_h2 {
                let (sender, connection) =
                    hyper::client::conn::http2::handshake(TokioExecutor::new(), TokioIo::new(tls))
                        .await
                        .unwrap();
                (
                    Client::H2(sender),
                    tokio::spawn(async move {
                        let _ = connection.await;
                    }),
                )
            } else {
                let (sender, connection) = hyper::client::conn::http1::handshake(TokioIo::new(tls))
                    .await
                    .unwrap();
                (
                    Client::H1(sender),
                    tokio::spawn(async move {
                        let _ = connection.await;
                    }),
                )
            };
            for path in [
                "/allowed",
                "/ads/banner?private=query-not-logged",
                "/next",
                "/html",
            ] {
                let response = request(&mut client, &domain, path).await;
                let blocked = path.starts_with("/ads/");
                assert_eq!(
                    response.status(),
                    if blocked {
                        StatusCode::NO_CONTENT
                    } else {
                        StatusCode::OK
                    }
                );
                let csp = response.headers().get("content-security-policy").cloned();
                if path == "/html" {
                    assert!(!response.headers().contains_key("content-length"));
                    assert!(!response.headers().contains_key("etag"));
                    assert_eq!(response.headers()["cache-control"], "no-store");
                }
                let body = response.into_body().collect().await.unwrap().to_bytes();
                if blocked {
                    assert!(body.is_empty());
                } else if path == "/html" {
                    let text = std::str::from_utf8(&body).unwrap();
                    assert!(text.contains("</script><style nonce="));
                    assert!(text.contains(".banner{display:none!important}"));
                    assert!(text.contains("<p>content</p>"));
                    let nonce = text
                        .split("<style nonce=\"")
                        .nth(1)
                        .unwrap()
                        .split('"')
                        .next()
                        .unwrap();
                    let csp = csp.unwrap();
                    let csp = csp.to_str().unwrap();
                    assert!(csp.contains(&format!("style-src-elem 'self' 'nonce-{nonce}'")));
                    assert!(csp.contains("script-src 'self'"));
                } else {
                    assert_eq!(body.len(), 256 * 1024);
                    assert!(body.iter().all(|v| *v == 7));
                }
            }
            assert_eq!(
                count.load(Ordering::Relaxed),
                3,
                "blocked path reached upstream"
            );
            assert_eq!(guard.0.load(Ordering::Relaxed), 1);
            drop(client);
            driver.abort();
            let _ = driver.await;
            tokio::time::timeout(Duration::from_secs(3), proxy)
                .await
                .unwrap()
                .unwrap()
                .unwrap();
            server.abort();
            let _ = server.await;
            filter::replace("", "");
            mitm::configure("{}");
            ca::set(None, vec![]);
        }
    }
}
#[tokio::test]
async fn http2_concurrent_streams_multiplex_over_one_upstream_connection() {
    let domain = "multiplex.test".to_owned();
    let (key, root) = root();
    ca::set(Some(key), root.clone());
    enable();
    let (peer_root, server_config) = peer_config(&domain, true);
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let inflight = Arc::new(AtomicUsize::new(0));
    let peak = Arc::new(AtomicUsize::new(0));
    let (now, high) = (inflight.clone(), peak.clone());
    let server = tokio::spawn(async move {
        let (raw, _) = listener.accept().await.unwrap();
        let tls = TlsAcceptor::from(server_config).accept(raw).await.unwrap();
        let service = hyper::service::service_fn(move |_: Request<hyper::body::Incoming>| {
            let (now, high) = (now.clone(), high.clone());
            async move {
                high.fetch_max(now.fetch_add(1, Ordering::SeqCst) + 1, Ordering::SeqCst);
                tokio::time::sleep(Duration::from_millis(300)).await;
                now.fetch_sub(1, Ordering::SeqCst);
                Ok::<_, Infallible>(Response::new(Full::new(Bytes::from_static(b"ok"))))
            }
        });
        let _ = hyper::server::conn::http2::Builder::new(TokioExecutor::new())
            .serve_connection(TokioIo::new(tls), service)
            .await;
    });
    let stats = Arc::new(Stats::default());
    let guard = Guard(AtomicUsize::new(0));
    let raw = sockets::connect_tcp(address, &guard, &stats).await.unwrap();
    let verified = TlsConnector::from(client_config(&peer_root, true))
        .connect(ServerName::try_from(domain.clone()).unwrap(), raw)
        .await
        .unwrap();
    let (front, back) = tokio::io::duplex(65536);
    let config = proxy::prepare(&domain).await.unwrap();
    let proxy = tokio::spawn(proxy::serve(
        back,
        config,
        vec![],
        verified,
        domain.clone(),
        address,
        owner(),
    ));
    let tls = TlsConnector::from(client_config(&root, true))
        .connect(ServerName::try_from(domain.clone()).unwrap(), front)
        .await
        .unwrap();
    let (sender, connection) =
        hyper::client::conn::http2::handshake(TokioExecutor::new(), TokioIo::new(tls))
            .await
            .unwrap();
    let driver = tokio::spawn(async move {
        let _ = connection.await;
    });
    let started = std::time::Instant::now();
    let streams = (0..8)
        .map(|i| {
            let mut client = Client::H2(sender.clone());
            let domain = domain.clone();
            tokio::spawn(async move {
                let response = request(&mut client, &domain, &format!("/item/{i}")).await;
                assert_eq!(response.status(), StatusCode::OK);
                let body = response.into_body().collect().await.unwrap().to_bytes();
                assert_eq!(&body[..], b"ok");
            })
        })
        .collect::<Vec<_>>();
    for stream in streams {
        stream.await.unwrap();
    }
    assert!(
        started.elapsed() < Duration::from_millis(8 * 300),
        "streams were serialized"
    );
    assert!(peak.load(Ordering::SeqCst) >= 2, "upstream saw no overlap");
    assert_eq!(guard.0.load(Ordering::Relaxed), 1, "extra upstream socket");
    drop(sender);
    driver.abort();
    let _ = driver.await;
    tokio::time::timeout(Duration::from_secs(3), proxy)
        .await
        .unwrap()
        .unwrap()
        .unwrap();
    server.abort();
    let _ = server.await;
    mitm::configure("{}");
    ca::set(None, vec![]);
}
