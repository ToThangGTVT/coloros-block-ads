use blockads_core::{
    filter::Rules,
    tcp::sniff::{self, Parsed},
};
fn hello(domain: &str) -> Vec<u8> {
    let name = domain.as_bytes();
    let mut body = vec![3, 3];
    body.extend_from_slice(&[0; 32]);
    body.extend_from_slice(&[0, 0, 2, 0x13, 1, 1, 0]);
    let mut extension = vec![];
    extension.extend_from_slice(&((name.len() + 3) as u16).to_be_bytes());
    extension.push(0);
    extension.extend_from_slice(&(name.len() as u16).to_be_bytes());
    extension.extend_from_slice(name);
    body.extend_from_slice(&((extension.len() + 4) as u16).to_be_bytes());
    body.extend_from_slice(&[0, 0]);
    body.extend_from_slice(&(extension.len() as u16).to_be_bytes());
    body.extend_from_slice(&extension);
    let mut handshake = vec![
        1,
        0,
        ((body.len() >> 8) & 255) as u8,
        (body.len() & 255) as u8,
    ];
    handshake.extend(body);
    let mut record = vec![22, 3, 1];
    record.extend_from_slice(&(handshake.len() as u16).to_be_bytes());
    record.extend(handshake);
    record
}
#[test]
fn tls_fragmentation_and_truncation_are_bounded_and_http_host_is_case_insensitive() {
    let packet = hello("Ads.Example.test");
    assert_eq!(
        sniff::parse(&packet),
        Parsed::Tls("ads.example.test".into())
    );
    for end in 0..packet.len() {
        assert!(!matches!(sniff::parse(&packet[..end]), Parsed::Tls(_)));
    }
    let content = &packet[5..];
    let split = 39;
    let mut fragmented = vec![];
    for part in [&content[..split], &content[split..]] {
        fragmented.extend_from_slice(&[22, 3, 3]);
        fragmented.extend_from_slice(&(part.len() as u16).to_be_bytes());
        fragmented.extend_from_slice(part);
    }
    assert_eq!(
        sniff::parse(&fragmented),
        Parsed::Tls("ads.example.test".into())
    );
    assert_eq!(
        sniff::parse(b"GET /ads/banner?x=1 HTTP/1.1\r\nhOsT: Example.test:8080\r\n\r\n"),
        Parsed::Http {
            host: "example.test".into(),
            url: "http://example.test:8080/ads/banner?x=1".into()
        }
    );
    assert_eq!(
        sniff::parse(b"GET / HTTP/1.1\r\nHost: a.test\r\nHost: b.test\r\n\r\n"),
        Parsed::Unknown
    );
    for size in 0..4096 {
        let bytes = vec![size as u8; size];
        let _ = sniff::parse(&bytes);
    }
}
#[test]
fn app_scope_url_regex_and_precedence_do_not_overblock_other_apps_or_sni_paths() {
    let rules = Rules::compile(
        "||ads.test^\n@@||ads.test^$app=com.browser\n||site.test/ads/*\n/track[0-9]+/",
        "||ads.test^$app=com.other\n@@||site.test/ads/ok|",
    );
    assert!(rules.report.errors.is_empty());
    assert!(
        !rules
            .matches("ads.test", None, &["com.browser".into()])
            .unwrap()
            .blocked
    );
    assert!(
        rules
            .matches("ads.test", None, &["com.other".into()])
            .unwrap()
            .blocked
    );
    assert!(rules.matches("sub.ads.test", None, &[]).unwrap().blocked);
    assert!(
        rules.matches("site.test", None, &[]).is_none(),
        "path rule must not block opaque TLS"
    );
    assert!(
        rules
            .matches("site.test", Some("http://site.test/ads/banner"), &[])
            .unwrap()
            .blocked
    );
    assert!(rules
        .matches("site.test", Some("http://evilsite.test/ads/banner"), &[])
        .is_none());
    assert!(
        !rules
            .matches("site.test", Some("http://site.test/ads/ok"), &[])
            .unwrap()
            .blocked
    );
    assert!(
        rules
            .matches("site.test", Some("http://site.test/track123"), &[])
            .unwrap()
            .blocked
    );
    assert!(!Rules::compile("", "/(a{1000000})/")
        .report
        .errors
        .is_empty());
    assert!(!Rules::compile("", "||a.test^$script")
        .report
        .errors
        .is_empty());
}
#[test]
fn blocked_sni_on_hardcoded_ip_sends_rst_without_opening_an_outbound_socket() {
    use blockads_core::{
        filter, policy,
        sockets::Protector,
        tun::{Engine, NetworkConfig},
    };
    use std::{
        io,
        net::SocketAddr,
        os::{fd::OwnedFd, unix::net::UnixDatagram},
        sync::Arc,
        time::Duration,
    };
    struct Guard;
    impl Protector for Guard {
        fn protect(&self, _: i32) -> io::Result<()> {
            panic!("blocked TLS opened upstream");
        }
        fn owner(&self, _: u8, _: SocketAddr, _: SocketAddr) -> Option<u32> {
            Some(12345)
        }
    }
    policy::configure(r#"{"packages":{"12345":["com.test"]}}"#);
    filter::replace("", "||hardcoded.test^$app=com.test");
    let packet = |seq, ack: Option<u32>, syn, payload: &[u8]| {
        let mut b = etherparse::PacketBuilder::ipv4([172, 19, 0, 1], [203, 0, 113, 9], 64)
            .tcp(32001, 443, seq, 65535);
        if syn {
            b = b.syn();
        }
        if let Some(ack) = ack {
            b = b.ack(ack);
        }
        let mut bytes = vec![];
        b.write(&mut bytes, payload).unwrap();
        bytes
    };
    let (fd, peer) = UnixDatagram::pair().unwrap();
    peer.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
    let mut engine =
        Engine::start(OwnedFd::from(fd), Arc::new(Guard), NetworkConfig::default()).unwrap();
    peer.send(&packet(1000, None, true, &[])).unwrap();
    let mut bytes = [0; 8192];
    let len = peer.recv(&mut bytes).unwrap();
    let parsed = etherparse::SlicedPacket::from_ip(&bytes[..len]).unwrap();
    let Some(etherparse::TransportSlice::Tcp(tcp)) = parsed.transport else {
        panic!("not TCP")
    };
    let ack = tcp.sequence_number().wrapping_add(1);
    peer.send(&packet(1001, Some(ack), false, &[])).unwrap();
    let initial = hello("hardcoded.test");
    peer.send(&packet(1001, Some(ack), false, &initial[..20]))
        .unwrap();
    peer.send(&packet(1021, Some(ack), false, &initial[20..]))
        .unwrap();
    let mut reset = false;
    for _ in 0..12 {
        let len = peer.recv(&mut bytes).unwrap();
        if let Some(etherparse::TransportSlice::Tcp(tcp)) =
            etherparse::SlicedPacket::from_ip(&bytes[..len])
                .unwrap()
                .transport
        {
            if tcp.rst() {
                assert_eq!(tcp.sequence_number(), ack);
                reset = true;
                break;
            }
        }
    }
    assert!(reset);
    assert_eq!(engine.stats.snapshot()[2], 0);
    engine.shutdown();
    filter::replace("", "");
    policy::configure("{}");
}

#[test]
fn quic_drop_setting_and_app_bypass_are_applied_before_socket_creation() {
    use blockads_core::{
        policy,
        sockets::Protector,
        tun::{Engine, NetworkConfig},
    };
    use std::{
        io,
        net::SocketAddr,
        os::{fd::OwnedFd, unix::net::UnixDatagram},
        sync::{
            atomic::{AtomicU64, Ordering},
            Arc,
        },
        time::{Duration, Instant},
    };
    struct Deny(AtomicU64);
    impl Protector for Deny {
        fn protect(&self, _: i32) -> io::Result<()> {
            self.0.fetch_add(1, Ordering::Relaxed);
            Err(io::ErrorKind::PermissionDenied.into())
        }
        fn owner(&self, _: u8, _: SocketAddr, _: SocketAddr) -> Option<u32> {
            Some(12345)
        }
    }
    policy::configure("{}");
    let protector = Arc::new(Deny(AtomicU64::new(0)));
    let (fd, peer) = UnixDatagram::pair().unwrap();
    let mut engine = Engine::start(
        OwnedFd::from(fd),
        protector.clone(),
        NetworkConfig::default(),
    )
    .unwrap();
    let mut bytes = vec![];
    etherparse::PacketBuilder::ipv4([172, 19, 0, 1], [203, 0, 113, 7], 64)
        .udp(32002, 443)
        .write(&mut bytes, b"quic-fixture")
        .unwrap();
    peer.send(&bytes).unwrap();
    std::thread::sleep(Duration::from_millis(50));
    assert_eq!(protector.0.load(Ordering::Relaxed), 0);
    for (config, expected) in [
        (r#"{"blockQuic":false}"#, 1),
        (r#"{"blockQuic":true,"bypassUids":[12345]}"#, 2),
    ] {
        policy::configure(config);
        peer.send(&bytes).unwrap();
        let deadline = Instant::now() + Duration::from_secs(2);
        while protector.0.load(Ordering::Relaxed) < expected && Instant::now() < deadline {
            std::thread::sleep(Duration::from_millis(5));
        }
        assert_eq!(protector.0.load(Ordering::Relaxed), expected);
    }
    engine.shutdown();
    policy::configure("{}");
}
