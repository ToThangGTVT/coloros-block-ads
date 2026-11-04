use blockads_core::{
    dns::{self, Session},
    sockets::{self, Protector, Stats},
};
use hickory_resolver::proto::{
    op::{Message, Query, ResponseCode},
    rr::{
        rdata::{
            svcb::{Alpn, IpHint, Mandatory, SvcParamKey, SvcParamValue, SVCB},
            AAAA, HTTPS,
        },
        Name, RData, Record, RecordType,
    },
};
use std::{
    io,
    net::{Ipv6Addr, UdpSocket},
    sync::Arc,
    time::Duration,
};

struct Allow;
impl Protector for Allow {
    fn protect(&self, _: i32) -> io::Result<()> {
        Ok(())
    }
}
struct Deny;
impl Protector for Deny {
    fn protect(&self, _: i32) -> io::Result<()> {
        Err(io::ErrorKind::PermissionDenied.into())
    }
}
fn query(name: &str, kind: RecordType) -> Vec<u8> {
    let mut message = Message::query();
    message.metadata.id = 9;
    message.metadata.recursion_desired = true;
    message
        .queries
        .push(Query::query(Name::from_ascii(name).unwrap(), kind));
    message.to_vec().unwrap()
}
fn https(name: &Name) -> Record {
    Record::from_rdata(
        name.clone(),
        300,
        RData::HTTPS(HTTPS(SVCB::new(
            1,
            Name::root(),
            vec![
                (
                    SvcParamKey::Mandatory,
                    SvcParamValue::Mandatory(Mandatory(vec![
                        SvcParamKey::Alpn,
                        SvcParamKey::Ipv6Hint,
                    ])),
                ),
                (
                    SvcParamKey::Alpn,
                    SvcParamValue::Alpn(Alpn(vec!["h2".into()])),
                ),
                (
                    SvcParamKey::Ipv6Hint,
                    SvcParamValue::Ipv6Hint(IpHint(vec![AAAA(Ipv6Addr::LOCALHOST)])),
                ),
            ],
        ))),
    )
}

// One test: the IPv6 flag is process-global.
#[tokio::test]
async fn without_physical_ipv6_aaaa_is_empty_and_ipv6hint_is_dropped() {
    let server = UdpSocket::bind("127.0.0.1:0").unwrap();
    server
        .set_read_timeout(Some(Duration::from_secs(3)))
        .unwrap();
    let port = server.local_addr().unwrap().port();
    let worker = std::thread::spawn(move || {
        // Exactly two upstream queries: HTTPS without IPv6, AAAA once IPv6 is back.
        for _ in 0..2 {
            let mut data = [0; 4096];
            let (len, peer) = server.recv_from(&mut data).unwrap();
            let request = Message::from_vec(&data[..len]).unwrap();
            let mut response = dns::error_response(&request, ResponseCode::NoError);
            let name = request.queries[0].name().clone();
            response
                .answers
                .push(match request.queries[0].query_type() {
                    RecordType::AAAA => Record::from_rdata(
                        name,
                        60,
                        RData::AAAA(AAAA::new(0x2001, 0xdb8, 0, 0, 0, 0, 0, 1)),
                    ),
                    _ => https(&name),
                });
            server.send_to(&response.to_vec().unwrap(), peer).unwrap();
        }
    });
    assert!(dns::configure(&format!(
        r#"{{"protocol":"udp","ip":"127.0.0.1","port":{port},"logging":false}}"#
    )));
    let stats = Arc::new(Stats::default());
    let session = Session::new(Arc::new(Allow), stats.clone());
    let resolve = |data: Vec<u8>| {
        let session = &session;
        async move { Message::from_vec(&session.resolve(&data, None, false).await.unwrap()).unwrap() }
    };

    sockets::set_ipv6_available(false);
    let aaaa = resolve(query("dual.test.", RecordType::AAAA)).await;
    assert_eq!(aaaa.metadata.response_code, ResponseCode::NoError);
    assert!(aaaa.answers.is_empty(), "no IPv6 upstream: empty AAAA");
    assert_eq!(stats.snapshot()[3], 0, "answered locally");
    let record = resolve(query("dual.test.", RecordType::HTTPS)).await;
    let RData::HTTPS(service) = &record.answers[0].data else {
        panic!("HTTPS record expected");
    };
    let keys: Vec<_> = service.0.svc_params.iter().map(|p| p.0).collect();
    assert_eq!(keys, vec![SvcParamKey::Mandatory, SvcParamKey::Alpn]);
    assert!(matches!(
        &service.0.svc_params[0].1,
        SvcParamValue::Mandatory(m) if m.0 == vec![SvcParamKey::Alpn]
    ));

    sockets::set_ipv6_available(true);
    let aaaa = resolve(query("dual.test.", RecordType::AAAA)).await;
    assert_eq!(aaaa.answers.len(), 1, "IPv6 available: forwarded");
    worker.join().unwrap();

    // A protector that refuses says nothing about the network: keep the previous answer.
    sockets::set_ipv6_available(false);
    sockets::probe_ipv6(&Deny);
    assert!(!sockets::ipv6_available());
    sockets::set_ipv6_available(true);
    dns::configure("{}");
}
