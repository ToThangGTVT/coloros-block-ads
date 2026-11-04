// Manual live-network diagnostic; excluded from normal test runs.
include!("https.rs");

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
    let guard = Guard(AtomicUsize::new(0));
    let upstream = proxy::upstream(address, &domain, &guard, &stats)
        .await
        .unwrap();
    let (front, back) = tokio::io::duplex(65536);
    let config = proxy::prepare(&domain).await.unwrap();
    let proxy_task = tokio::spawn(proxy::serve(
        back,
        config,
        vec![],
        upstream,
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
}
