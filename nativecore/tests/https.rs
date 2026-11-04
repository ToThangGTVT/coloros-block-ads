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
async fn gzip(data: &[u8]) -> Vec<u8> {
    use tokio::io::AsyncReadExt;
    let mut output = Vec::new();
    async_compression::tokio::bufread::GzipEncoder::new(data)
        .read_to_end(&mut output)
        .await
        .unwrap();
    output
}
async fn brotli(data: &[u8]) -> Vec<u8> {
    use tokio::io::AsyncReadExt;
    let mut output = Vec::new();
    async_compression::tokio::bufread::BrotliEncoder::new(data)
        .read_to_end(&mut output)
        .await
        .unwrap();
    output
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
fn test_connector(
    address: std::net::SocketAddr,
    domain: String,
    config: Arc<rustls::ClientConfig>,
    guard: Arc<Guard>,
    stats: Arc<Stats>,
) -> proxy::Connector {
    Arc::new(move || {
        let (domain, config, guard, stats) =
            (domain.clone(), config.clone(), guard.clone(), stats.clone());
        Box::pin(async move {
            let raw = sockets::connect_tcp(address, guard.as_ref(), &stats).await?;
            TlsConnector::from(config)
                .connect(ServerName::try_from(domain).map_err(io::Error::other)?, raw)
                .await
        })
    })
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
async fn only_certificate_alerts_bypass_an_abandoned_handshake_does_not() {
    use tokio::io::AsyncWriteExt;
    let (key, der) = root();
    ca::set(Some(key), der);
    enable();
    for (domain, client_alerts, stale_root) in [
        ("abandoned.test", false, false),
        ("pinned.test", true, false),
        ("stale-root.test", true, true),
    ] {
        let (peer_root, server_config) = peer_config(domain, false);
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let origin = tokio::spawn(async move {
            let (raw, _) = listener.accept().await.unwrap();
            let _ = TlsAcceptor::from(server_config).accept(raw).await;
        });
        let connector = test_connector(
            address,
            domain.into(),
            client_config(&peer_root, false),
            Arc::new(Guard(AtomicUsize::new(0))),
            Arc::new(Stats::default()),
        );
        let upstream = connector().await.unwrap();
        let (mut front, back) = tokio::io::duplex(65536);
        let proxy = tokio::spawn(proxy::serve(
            back,
            proxy::prepare(domain).await.unwrap(),
            vec![],
            proxy::VerifiedUpstream::new(upstream, connector),
            domain.into(),
            address,
            owner(),
        ));
        if client_alerts {
            // A client that does not trust the local CA answers with a certificate alert: an
            // unrelated issuer (unknown CA) or a stale root of the same name (bad signature).
            let trusted = if stale_root {
                root().1
            } else {
                peer_root.clone()
            };
            assert!(TlsConnector::from(client_config(&trusted, false))
                .connect(ServerName::try_from(domain).unwrap(), front)
                .await
                .is_err());
        } else {
            // Start a TLS record, then give up the socket like an unused browser preconnect.
            front
                .write_all(&[0x16, 0x03, 0x01, 0x00, 0x40, 0x01])
                .await
                .unwrap();
            drop(front);
        }
        assert!(tokio::time::timeout(Duration::from_secs(3), proxy)
            .await
            .unwrap()
            .unwrap()
            .is_err());
        assert_eq!(mitm::eligible(domain, &owner()), !client_alerts, "{domain}");
        origin.abort();
    }
    mitm::configure("{}");
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
    let guard = Arc::new(Guard(AtomicUsize::new(0)));
    assert!(proxy::upstream(
        address,
        "untrusted.test",
        guard.as_ref(),
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
    send(
        client,
        domain,
        path,
        &[("Accept", "text/html"), ("Sec-Fetch-Dest", "document")],
    )
    .await
}
async fn send(
    client: &mut Client,
    domain: &str,
    path: &str,
    headers: &[(&str, &str)],
) -> Response<hyper::body::Incoming> {
    let h2 = matches!(client, Client::H2(_));
    let uri = if h2 {
        format!("https://{domain}{path}")
    } else {
        path.to_owned()
    };
    let mut request = Request::builder().uri(uri).header("Host", domain);
    for (name, value) in headers {
        request = request.header(*name, *value);
    }
    let request = request.body(Empty::<Bytes>::new()).unwrap();
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
            assert!(
                filter::replace(
                    "",
                    &format!(
                        "||{domain}/ads/*\n{domain}##.banner\n||{domain}^*/sdk.js$script,third-party\n##.generic-ad\n{domain}#@#.allowed-ad\n##.allowed-ad"
                    )
                )
                .applied
            );
            let own_page = format!("https://{domain}/article");
            let (peer_root, server_config) = peer_config(&domain, upstream_h2);
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            let address = listener.local_addr().unwrap();
            let count = Arc::new(AtomicUsize::new(0));
            let requests = count.clone();
            let expected_authority = format!("{domain}:{}", address.port());
            let server = tokio::spawn(async move {
                let (raw, _) = listener.accept().await.unwrap();
                let tls = TlsAcceptor::from(server_config).accept(raw).await.unwrap();
                let service = hyper::service::service_fn(
                    move |request: Request<hyper::body::Incoming>| {
                        requests.fetch_add(1, Ordering::Relaxed);
                        if upstream_h2 {
                            assert_eq!(
                                request.uri().authority().unwrap().as_str(),
                                expected_authority
                            );
                            assert!(
                                !request.headers().contains_key("host"),
                                "H2 must use :authority without a duplicate Host"
                            );
                        } else {
                            assert_eq!(request.headers()["host"], expected_authority);
                        }
                        async move {
                            if request.uri().path() == "/html" {
                                // Compressed on the wire; the proxy decodes before rewriting.
                                assert_eq!(
                                    request.headers()["accept-encoding"],
                                    "gzip, deflate, br"
                                );
                                return Ok::<_, Infallible>(Response::builder()
                                    .header("content-type", "text/html; charset=utf-8")
                                    .header("content-encoding", "gzip")
                                    .header("etag", "\"original\"")
                                    .header("content-security-policy", "default-src 'none'; script-src 'self'; style-src 'self'")
                                    .body(Full::new(Bytes::from(gzip(b"<!doctype html><html><head><script>const fake='</head>';</script></head><body><div class='banner'>ad</div><p>content</p></body></html>").await))).unwrap());
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
            let guard = Arc::new(Guard(AtomicUsize::new(0)));
            let raw = sockets::connect_tcp(address, guard.as_ref(), &stats)
                .await
                .unwrap();
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
                proxy::VerifiedUpstream::new(
                    verified,
                    test_connector(
                        address,
                        domain.clone(),
                        client_config(&peer_root, upstream_h2),
                        guard.clone(),
                        stats.clone(),
                    ),
                ),
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
            let document: &[(&str, &str)] =
                &[("Accept", "text/html"), ("Sec-Fetch-Dest", "document")];
            let cross_site_script: &[(&str, &str)] = &[
                ("Sec-Fetch-Dest", "script"),
                ("Sec-Fetch-Site", "cross-site"),
                ("Referer", "https://news.example/"),
            ];
            let own_script: &[(&str, &str)] = &[
                ("Sec-Fetch-Dest", "script"),
                ("Sec-Fetch-Site", "same-origin"),
                ("Referer", &own_page),
            ];
            let cross_site_image: &[(&str, &str)] = &[
                ("Sec-Fetch-Dest", "image"),
                ("Referer", "https://news.example/"),
            ];
            let mut endpoint = None;
            for (path, headers, blocked) in [
                ("/allowed", document, false),
                ("/ads/banner?private=query-not-logged", document, true),
                ("/sdk.js", cross_site_script, true),
                ("/sdk.js", own_script, false),
                ("/sdk.js", cross_site_image, false),
                ("/next", document, false),
                ("/html", document, false),
            ] {
                let response = send(&mut client, &domain, path, headers).await;
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
                    assert_eq!(response.headers()["cache-control"], "private, no-cache");
                    assert!(!response.headers().contains_key("content-encoding"));
                }
                let body = response.into_body().collect().await.unwrap().to_bytes();
                if blocked {
                    assert!(body.is_empty());
                } else if path == "/html" {
                    let text = std::str::from_utf8(&body).unwrap();
                    // Charset in the header: before the page's own head scripts.
                    assert!(text.contains("<head><style nonce="));
                    assert!(text.contains("</script><script>const fake='</head>';"));
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
                    assert!(csp.contains(&format!("script-src-elem 'self' 'nonce-{nonce}'")));
                    assert!(text.contains(&format!("<script nonce=\"{nonce}\" data-blockads>")));
                    endpoint = text
                        .split("(\"/.blockads-")
                        .nth(1)
                        .map(|rest| format!("/.blockads-{}", rest.split('"').next().unwrap()));
                } else {
                    assert_eq!(body.len(), 256 * 1024);
                    assert!(body.iter().all(|v| *v == 7));
                }
            }
            // Generic class/id CSS is answered by the proxy itself, never by the origin.
            let endpoint = endpoint.expect("content script endpoint");
            let response = send(
                &mut client,
                &domain,
                &format!("{endpoint}?c=generic-ad%20allowed-ad%20plain&i=none"),
                &[("Sec-Fetch-Dest", "style")],
            )
            .await;
            assert_eq!(response.status(), StatusCode::OK);
            assert_eq!(
                response.headers()["content-type"],
                "text/css; charset=utf-8"
            );
            let body = response.into_body().collect().await.unwrap().to_bytes();
            assert_eq!(body.as_ref(), b".generic-ad{display:none!important}\n");
            assert_eq!(
                count.load(Ordering::Relaxed),
                5,
                "blocked or local request reached upstream"
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
    let guard = Arc::new(Guard(AtomicUsize::new(0)));
    let raw = sockets::connect_tcp(address, guard.as_ref(), &stats)
        .await
        .unwrap();
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
        proxy::VerifiedUpstream::new(
            verified,
            test_connector(
                address,
                domain.clone(),
                client_config(&peer_root, true),
                guard.clone(),
                stats.clone(),
            ),
        ),
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

// Manual live-network diagnostic; excluded from normal test runs.

#[tokio::test]
#[ignore = "Requires explicit live-network access to m.genk.vn"]
async fn live_genk_html_through_https_proxy() {
    use std::net::ToSocketAddrs;
    struct Capture;
    impl blockads_core::dns::EventSink for Capture {
        fn batch(&self, json: &str) {
            eprintln!("{json}");
        }
    }
    blockads_core::dns::set_sink(Some(Arc::new(Capture)));
    let domain = "m.genk.vn".to_owned();
    let address = (domain.as_str(), 443)
        .to_socket_addrs()
        .unwrap()
        .find(|a| a.is_ipv4())
        .unwrap();
    let (key, der) = root();
    ca::set(Some(key), der.clone());
    enable();
    filter::replace("~cafef.vn##[id^=\"adm-slot\"]", "");
    let stats = Arc::new(Stats::default());
    let guard = Arc::new(Guard(AtomicUsize::new(0)));
    let upstream = proxy::upstream(address, &domain, guard.as_ref(), &stats)
        .await
        .unwrap();
    let (front, back) = tokio::io::duplex(65536);
    let config = proxy::prepare(&domain).await.unwrap();
    let proxy_task = tokio::spawn(proxy::serve(
        back,
        config,
        vec![],
        proxy::VerifiedUpstream::new(
            upstream,
            proxy::connector(address, domain.clone(), guard.clone(), stats.clone()),
        ),
        domain.clone(),
        address,
        owner(),
    ));
    let tls = TlsConnector::from(client_config(&der, true))
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
    let mut sender = sender;
    sender.ready().await.unwrap();
    let response = sender.send_request(Request::builder().uri(format!("https://{domain}/may-tinh-anh-em-dinh-mua-se-dat-them-bao-nhieu-trong-nam-toi-3-kich-ban-cho-3-tam-gia-165261104151528916.chn"))
        .header("host", &domain).header("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .header("sec-fetch-dest", "document")
        .header("user-agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/158.0.0.0 Mobile Safari/537.36")
        .body(Empty::<Bytes>::new()).unwrap()).await.unwrap();
    let status = response.status();
    eprintln!("status: {status}, headers: {:?}", response.headers());
    let body = response.into_body().collect().await.unwrap().to_bytes();
    eprintln!(
        "body len: {}, CSS present: {}",
        body.len(),
        String::from_utf8_lossy(&body).contains("data-blockads")
    );
    blockads_core::dns::flush();
    proxy_task.abort();
    driver.abort();
    assert_eq!(status, StatusCode::OK);
    assert!(String::from_utf8_lossy(&body).contains("data-blockads"));
    filter::replace("", "");
    mitm::configure("{}");
    ca::set(None, vec![]);
}

// Isolated desktop Chrome probe; upstream certificates are still verified by the proxy.
// The generated test CA is accepted only by this disposable browser process/profile.
#[tokio::test]
#[ignore = "Requires BLOCKADS_CHROME and explicit live-network access to m.genk.vn"]
async fn live_genk_in_desktop_chrome() {
    use std::{net::ToSocketAddrs, process::Command};
    struct Capture;
    impl blockads_core::dns::EventSink for Capture {
        fn batch(&self, json: &str) {
            eprintln!("{json}");
        }
    }
    blockads_core::dns::set_sink(Some(Arc::new(Capture)));
    let domain = "m.genk.vn".to_owned();
    let address = (domain.as_str(), 443)
        .to_socket_addrs()
        .unwrap()
        .find(|a| a.is_ipv4())
        .unwrap();
    let (key, der) = root();
    ca::set(Some(key), der);
    enable();
    filter::replace("~cafef.vn##[id^=\"adm-slot\"]", "");
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let config = proxy::prepare(&domain).await.unwrap();
    let server = tokio::spawn(async move {
        let mut tasks = tokio::task::JoinSet::new();
        loop {
            tokio::select! {
                accepted = listener.accept() => {
                    let (client, _) = accepted.unwrap();
                    let domain = domain.clone();
                    let config = config.clone();
                    tasks.spawn(async move {
                        let upstream = proxy::upstream(address, &domain, &Guard(AtomicUsize::new(0)), &Arc::new(Stats::default())).await.unwrap();
                        let reconnect = proxy::connector(address, domain.clone(), Arc::new(Guard(AtomicUsize::new(0))), Arc::new(Stats::default()));
                        let _ = proxy::serve(client, config, vec![], proxy::VerifiedUpstream::new(upstream, reconnect), domain, address, owner()).await;
                    });
                }
                _ = tasks.join_next(), if !tasks.is_empty() => {}
            }
        }
    });
    let chrome =
        std::env::var("BLOCKADS_CHROME").expect("Set BLOCKADS_CHROME to a Chrome executable");
    let profile =
        std::env::temp_dir().join(format!("blockads-chrome-probe-{}", std::process::id()));
    let profile_arg = format!("--user-data-dir={}", profile.display());
    let result = tokio::task::spawn_blocking(move || Command::new(chrome).args([
        "--headless", "--disable-gpu", "--no-first-run", "--disable-extensions", "--disable-quic",
        "--disable-background-networking", "--ignore-certificate-errors", "--dump-dom", "--timeout=20000",
        "--user-agent=Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/158.0.0.0 Mobile Safari/537.36",
        &profile_arg,
        &format!("--host-resolver-rules=MAP m.genk.vn 127.0.0.1:{port}"),
        "https://m.genk.vn/may-tinh-anh-em-dinh-mua-se-dat-them-bao-nhieu-trong-nam-toi-3-kich-ban-cho-3-tam-gia-165261104151528916.chn"
    ]).output().unwrap()).await.unwrap();
    blockads_core::dns::flush();
    server.abort();
    let _ = server.await;
    let _ = std::fs::remove_dir_all(profile);
    let html = String::from_utf8_lossy(&result.stdout);
    eprintln!(
        "Chrome success: {}, DOM bytes: {}, CSS: {}",
        result.status.success(),
        html.len(),
        html.contains("data-blockads")
    );
    assert!(
        result.status.success(),
        "{}",
        String::from_utf8_lossy(&result.stderr)
    );
    assert!(
        html.contains("data-blockads"),
        "No cosmetic CSS in Chrome DOM"
    );
    assert!(
        html.contains("Chip nhớ còn khan"),
        "Article content was lost"
    );
}

// Local-only desktop Chrome check of the injected content script under a strict CSP:
// generic class rules (including elements added later), procedural `:has-text`/`:remove()`.
#[tokio::test]
#[ignore = "Requires BLOCKADS_CHROME (a Chrome executable); no network access"]
async fn content_script_hides_generic_and_procedural_targets_in_desktop_chrome() {
    use std::process::Command;
    const PAGE: &str = r#"<!doctype html><html><head><meta charset="utf-8"><base href="https://elsewhere.test/">
<script nonce="page">window.early = !!document.querySelector('script[data-blockads]');
window.scriptletFirst = window.blockadsProbe;</script><script nonce="page" src="https://content.test/ad-lib.js"></script><title>t</title></head><body>
<div class="generic-ad" id="g">generic</div><div class="keep" id="k">keep</div>
<div class="box" id="t">Quảng cáo ở đây</div><div class="box" id="t2">Nội dung</div>
<div class="popup" id="p">popup</div><div id="sponsor-x">specific</div><div id="holder"></div>
<script nonce="page">
setTimeout(function () { var d = document.createElement('div'); d.className = 'late-ad'; d.id = 'late';
  d.textContent = 'late'; document.getElementById('holder').appendChild(d); }, 300);
setTimeout(function () { var r = {}; ['g', 'k', 't', 't2', 'sponsor-x', 'late'].forEach(function (id) {
  var e = document.getElementById(id); r[id] = e ? getComputedStyle(e).display : 'missing'; });
  r.p = document.getElementById('p') ? 'present' : 'removed';
  r.early = window.early; r.scriptletFirst = window.scriptletFirst; r.redirected = window.noopLoaded === true; r.adlib = performance.getEntriesByType('resource').filter(function (e) { return e.name.indexOf('ad-lib') >= 0; }).map(function (e) { return e.name + ' ' + e.responseStatus + ' ' + e.decodedBodySize; }).join('|');
  r.links = Array.prototype.map.call(document.querySelectorAll('link[data-blockads]'), function (l) {
    return l.sheet ? l.sheet.cssRules.length : -1; }).join(',');
  var pre = document.createElement('pre'); pre.id = 'result'; pre.textContent = JSON.stringify(r);
  document.body.appendChild(pre); }, 2000);
</script></body></html>"#;
    let domain = "content.test".to_owned();
    let (key, der) = root();
    ca::set(Some(key), der);
    enable();
    assert!(filter::replace(
        "##.generic-ad\n##.late-ad\ncontent.test###sponsor-x\ncontent.test##.box:has-text(Quảng cáo)\ncontent.test##.popup:remove()\ncontent.test##+js(set-constant, blockadsProbe, yes)\n||content.test^*/ad-lib.js$script,redirect=noopjs",
        ""
    )
    .applied);
    // Minimal stand-ins in the AdGuard Scriptlets formats; the real files are downloaded by the app.
    // BLOCKADS_RESOURCES: real AdGuard Scriptlets files (scriptlets.corelibs.json saved as
    // corelibs.json, redirects.json); otherwise minimal stand-ins in the same formats.
    let real = std::env::var("BLOCKADS_RESOURCES").ok();
    match &real {
        Some(dir) => {
            let read = |name: &str| std::fs::read_to_string(format!("{dir}/{name}")).unwrap();
            filter::resources::install(&read("corelibs.json"), &read("redirects.json")).unwrap()
        }
        None => filter::resources::install(
            r#"{"scriptlets":[{"names":["set-constant"],"scriptlet":"function setConstant(source,args){window[args[0]]=args[1]}"}]}"#,
            r#"[{"title":"noopjs","contentType":"application/javascript;base64","content":"d2luZG93Lm5vb3BMb2FkZWQgPSB0cnVlOw=="}]"#,
        )
        .unwrap(),
    };
    let (peer_root, server_config) = peer_config(&domain, true);
    let origin = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = origin.local_addr().unwrap();
    let origin_task = tokio::spawn(async move {
        loop {
            let (raw, _) = origin.accept().await.unwrap();
            let server_config = server_config.clone();
            tokio::spawn(async move {
                let Ok(tls) = TlsAcceptor::from(server_config).accept(raw).await else {
                    return;
                };
                let service = hyper::service::service_fn(
                    |request: Request<hyper::body::Incoming>| async move {
                        assert_eq!(request.headers()["accept-encoding"], "gzip, deflate, br");
                        Ok::<_, Infallible>(
                            Response::builder()
                                .header("content-type", "text/html; charset=utf-8")
                                .header("content-encoding", "br")
                                .header(
                                    "content-security-policy",
                                    "default-src 'none'; script-src 'nonce-page'; style-src 'self'",
                                )
                                .body(Full::new(Bytes::from(brotli(PAGE.as_bytes()).await)))
                                .unwrap(),
                        )
                    },
                );
                let _ = hyper::server::conn::http2::Builder::new(TokioExecutor::new())
                    .serve_connection(TokioIo::new(tls), service)
                    .await;
            });
        }
    });
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let config = proxy::prepare(&domain).await.unwrap();
    let accepted = Arc::new(AtomicUsize::new(0));
    let accepted_count = accepted.clone();
    let proxy_task = tokio::spawn(async move {
        loop {
            let (client, _) = listener.accept().await.unwrap();
            accepted_count.fetch_add(1, Ordering::Relaxed);
            let connector = test_connector(
                address,
                domain.clone(),
                client_config(&peer_root, true),
                Arc::new(Guard(AtomicUsize::new(0))),
                Arc::new(Stats::default()),
            );
            let (domain, config) = (domain.clone(), config.clone());
            tokio::spawn(async move {
                let upstream = connector().await.unwrap();
                let _ = proxy::serve(
                    client,
                    config,
                    vec![],
                    proxy::VerifiedUpstream::new(upstream, connector),
                    domain,
                    address,
                    owner(),
                )
                .await;
            });
        }
    });
    let chrome =
        std::env::var("BLOCKADS_CHROME").expect("Set BLOCKADS_CHROME to a Chrome executable");
    let profile =
        std::env::temp_dir().join(format!("blockads-chrome-content-{}", std::process::id()));
    let profile_arg = format!("--user-data-dir={}", profile.display());
    let result = tokio::task::spawn_blocking(move || {
        Command::new(chrome)
            .args([
                "--headless",
                "--disable-gpu",
                "--no-first-run",
                "--disable-extensions",
                "--disable-quic",
                "--disable-background-networking",
                "--ignore-certificate-errors",
                "--dump-dom",
                "--virtual-time-budget=5000",
                "--timeout=20000",
                &profile_arg,
                &format!("--host-resolver-rules=MAP content.test 127.0.0.1:{port}"),
                "https://content.test/article",
            ])
            .output()
            .unwrap()
    })
    .await
    .unwrap();
    proxy_task.abort();
    origin_task.abort();
    filter::resources::install("", "").unwrap();
    let _ = std::fs::remove_dir_all(profile);
    filter::replace("", "");
    mitm::configure("{}");
    ca::set(None, vec![]);
    let html = String::from_utf8_lossy(&result.stdout);
    let json = html
        .split("<pre id=\"result\">")
        .nth(1)
        .and_then(|rest| rest.split("</pre>").next())
        .unwrap_or_else(|| {
            panic!(
                "no result in DOM ({} proxy connections, {:?}): {html}\n{}",
                accepted.load(Ordering::Relaxed),
                result.status,
                String::from_utf8_lossy(&result.stderr)
            )
        })
        .replace("&quot;", "\"");
    eprintln!(
        "chrome result: {json} ({} proxy connections)",
        accepted.load(Ordering::Relaxed)
    );
    let result: serde_json::Value = serde_json::from_str(&json).unwrap();
    for (id, display) in [
        ("g", "none"),
        ("late", "none"),
        ("sponsor-x", "none"),
        ("t", "none"),
        ("k", "block"),
        ("t2", "block"),
        ("p", "removed"),
    ] {
        assert_eq!(result[id], display, "{id}: {json}");
    }
    assert_eq!(
        result["early"], true,
        "BlockAds must run before head scripts"
    );
    assert_eq!(
        result["scriptletFirst"], "yes",
        "scriptlet must run before page scripts"
    );
    if real.is_some() {
        // AdGuard's noopjs is an empty function; check the response came from the proxy.
        assert!(result["adlib"].as_str().unwrap().contains("ad-lib.js 200 "));
    } else {
        assert_eq!(
            result["redirected"], true,
            "$redirect must answer with the noopjs stand-in"
        );
    }
    assert_eq!(
        result["links"], "1,1",
        "generic CSS must load despite <base href>"
    );
}

#[tokio::test]
async fn closed_upstream_is_reopened_while_browser_keeps_its_tls_connection() {
    for upstream_h2 in [false, true] {
        let domain = "reconnect.test".to_owned();
        let (key, root) = root();
        ca::set(Some(key), root.clone());
        enable();
        filter::replace("", "");
        let (peer_root, server_config) = peer_config(&domain, upstream_h2);
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let closed = Arc::new(tokio::sync::Notify::new());
        let closed_server = closed.clone();
        let posts = Arc::new(AtomicUsize::new(0));
        let server_posts = posts.clone();
        let server = tokio::spawn(async move {
            for index in 0..3 {
                let (raw, _) = listener.accept().await.unwrap();
                let tls = TlsAcceptor::from(server_config.clone())
                    .accept(raw)
                    .await
                    .unwrap();
                let answered = Arc::new(tokio::sync::Notify::new());
                let signal = answered.clone();
                let server_posts = server_posts.clone();
                let service =
                    hyper::service::service_fn(move |request: Request<hyper::body::Incoming>| {
                        signal.notify_one();
                        if request.method() == hyper::Method::POST {
                            server_posts.fetch_add(1, Ordering::Relaxed);
                        }
                        async move {
                            if request.method() == hyper::Method::POST {
                                return Err(io::Error::other("origin failed after accepting POST"));
                            }
                            let mut response =
                                Response::new(Full::new(Bytes::from_static(b"page content")));
                            if !upstream_h2 {
                                response.headers_mut().insert(
                                    "connection",
                                    hyper::header::HeaderValue::from_static("close"),
                                );
                            }
                            Ok::<_, io::Error>(response)
                        }
                    });
                let result = if upstream_h2 {
                    let builder = hyper::server::conn::http2::Builder::new(TokioExecutor::new());
                    let connection = builder.serve_connection(TokioIo::new(tls), service);
                    tokio::pin!(connection);
                    tokio::select! {
                        result = &mut connection => result,
                        _ = answered.notified() => {
                            connection.as_mut().graceful_shutdown();
                            connection.await
                        }
                    }
                } else {
                    hyper::server::conn::http1::Builder::new()
                        .serve_connection(TokioIo::new(tls), service)
                        .await
                };
                if index != 2 {
                    result.unwrap();
                }
                closed_server.notify_one();
            }
        });
        let stats = Arc::new(Stats::default());
        let guard = Arc::new(Guard(AtomicUsize::new(0)));
        let raw = sockets::connect_tcp(address, guard.as_ref(), &stats)
            .await
            .unwrap();
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
            proxy::VerifiedUpstream::new(
                verified,
                test_connector(
                    address,
                    domain.clone(),
                    client_config(&peer_root, upstream_h2),
                    guard.clone(),
                    stats.clone(),
                ),
            ),
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
        let mut client = Client::H2(sender);
        for path in ["/first", "/second"] {
            let response = request(&mut client, &domain, path).await;
            assert_eq!(
                response.status(),
                StatusCode::OK,
                "stale upstream (HTTP/2={upstream_h2})"
            );
            assert_eq!(
                response.into_body().collect().await.unwrap().to_bytes(),
                "page content"
            );
            tokio::time::timeout(Duration::from_secs(3), closed.notified())
                .await
                .unwrap();
        }
        // The origin accepts this POST and disconnects without sending response headers.
        // Retrying it could repeat a side effect; Hyper must not return it as unsent.
        let Client::H2(sender) = &mut client else {
            unreachable!()
        };
        let response = sender
            .send_request(
                Request::builder()
                    .method("POST")
                    .uri(format!("https://{domain}/side-effect"))
                    .header("host", &domain)
                    .body(Empty::<Bytes>::new())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::BAD_GATEWAY);
        response.into_body().collect().await.unwrap();
        tokio::time::timeout(Duration::from_secs(3), server)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(posts.load(Ordering::Relaxed), 1, "POST was replayed");
        assert_eq!(
            guard.0.load(Ordering::Relaxed),
            3,
            "protect every reopened socket and never retry a POST accepted by the origin"
        );
        drop(client);
        driver.abort();
        proxy.abort();
        filter::replace("", "");
        mitm::configure("{}");
        ca::set(None, vec![]);
    }
}

// Manual live-network diagnostic; excluded from normal test runs.

#[tokio::test]
#[ignore = "Requires explicit live-network access to ajax.googleapis.com"]
async fn live_public_script_through_https_proxy() {
    use std::net::ToSocketAddrs;
    struct Capture;
    impl blockads_core::dns::EventSink for Capture {
        fn batch(&self, json: &str) {
            eprintln!("{json}");
        }
    }
    blockads_core::dns::set_sink(Some(Arc::new(Capture)));
    let domain = "ajax.googleapis.com".to_owned();
    let address = (domain.as_str(), 443)
        .to_socket_addrs()
        .unwrap()
        .find(|a| a.is_ipv4())
        .unwrap();
    let (key, der) = root();
    ca::set(Some(key), der.clone());
    enable();
    filter::replace("", "");
    let stats = Arc::new(Stats::default());
    let guard = Arc::new(Guard(AtomicUsize::new(0)));
    let upstream = proxy::upstream(address, &domain, guard.as_ref(), &stats)
        .await
        .unwrap();
    let (front, back) = tokio::io::duplex(65536);
    let config = proxy::prepare(&domain).await.unwrap();
    let proxy_task = tokio::spawn(proxy::serve(
        back,
        config,
        vec![],
        proxy::VerifiedUpstream::new(
            upstream,
            proxy::connector(address, domain.clone(), guard.clone(), stats.clone()),
        ),
        domain.clone(),
        address,
        owner(),
    ));
    let tls = TlsConnector::from(client_config(&der, true))
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
    let mut sender = sender;
    sender.ready().await.unwrap();
    let response = sender.send_request(Request::builder().uri(format!("https://{domain}/ajax/libs/jquery/1.11.0/jquery.min.js"))
        .header("host", &domain).header("accept", "*/*")
        .header("sec-fetch-dest", "script")
        .header("user-agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/158.0.0.0 Mobile Safari/537.36")
        .body(Empty::<Bytes>::new()).unwrap()).await.unwrap();
    let status = response.status();
    eprintln!("status: {status}, headers: {:?}", response.headers());
    let body = response.into_body().collect().await.unwrap().to_bytes();
    eprintln!(
        "body len: {}, CSS present: {}",
        body.len(),
        String::from_utf8_lossy(&body).contains("data-blockads")
    );
    blockads_core::dns::flush();
    proxy_task.abort();
    driver.abort();
    assert_eq!(status, StatusCode::OK);
    assert!(String::from_utf8_lossy(&body).contains("jQuery"));
    filter::replace("", "");
    mitm::configure("{}");
    ca::set(None, vec![]);
}
