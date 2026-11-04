use blockads_core::{
    dns::{self, Session},
    mitm::{self, ca},
    policy::Owner,
    sockets::{Protector, Stats},
};
use hickory_resolver::proto::{
    op::{Message, Query, ResponseCode},
    rr::{
        rdata::{
            svcb::{Alpn, EchConfigList, Mandatory, SvcParamKey, SvcParamValue, SVCB},
            HTTPS,
        },
        Name, RData, Record, RecordType,
    },
};
use std::{io, net::UdpSocket, sync::Arc, time::Duration};

struct Allow;
impl Protector for Allow {
    fn protect(&self, _: i32) -> io::Result<()> {
        Ok(())
    }
}
fn owner(package: &str) -> Owner {
    Owner {
        uid: Some(123),
        packages: vec![package.into()],
        bypass: false,
        blocked: false,
    }
}
fn record(name: &Name) -> Record {
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
                        SvcParamKey::EchConfigList,
                    ])),
                ),
                (
                    SvcParamKey::Alpn,
                    SvcParamValue::Alpn(Alpn(vec!["h2".into(), "h3".into()])),
                ),
                (
                    SvcParamKey::EchConfigList,
                    SvcParamValue::EchConfigList(EchConfigList(vec![0, 1, 2, 3])),
                ),
            ],
        ))),
    )
}
fn keys(message: &Message) -> Vec<SvcParamKey> {
    let RData::HTTPS(https) = &message.answers[0].data else {
        panic!("not an HTTPS record");
    };
    https
        .0
        .svc_params
        .iter()
        .flat_map(|(key, value)| match value {
            SvcParamValue::Mandatory(m) => m.0.clone(),
            _ => vec![*key],
        })
        .collect()
}

#[tokio::test]
async fn ech_is_removed_only_for_https_filtered_apps() {
    let server = UdpSocket::bind("127.0.0.1:0").unwrap();
    server
        .set_read_timeout(Some(Duration::from_secs(3)))
        .unwrap();
    let port = server.local_addr().unwrap().port();
    let worker = std::thread::spawn(move || {
        let mut data = [0; 4096];
        let (len, peer) = server.recv_from(&mut data).unwrap();
        let request = Message::from_vec(&data[..len]).unwrap();
        let mut response = dns::error_response(&request, ResponseCode::NoError);
        response.answers.push(record(request.queries[0].name()));
        server.send_to(&response.to_vec().unwrap(), peer).unwrap();
    });
    assert!(dns::configure(&format!(
        r#"{{"protocol":"udp","ip":"127.0.0.1","port":{port},"logging":false}}"#
    )));
    let key = Arc::new(rcgen::KeyPair::generate().unwrap());
    let der = ca::generate(key.clone()).unwrap();
    ca::set(Some(key), der);
    assert!(mitm::configure(
        r#"{"enabled":true,"apps":["test.browser"]}"#
    ));
    let stats = Arc::new(Stats::default());
    let session = Session::new(Arc::new(Allow), stats.clone());
    let mut query = Message::query();
    query.metadata.id = 7;
    query.metadata.recursion_desired = true;
    query.queries.push(Query::query(
        Name::from_ascii("shop.test.").unwrap(),
        RecordType::HTTPS,
    ));
    let query = query.to_vec().unwrap();
    // Filtered browser (fresh answer), another app and the browser again (both from cache).
    for (package, ech) in [
        ("test.browser", false),
        ("other.app", true),
        ("test.browser", false),
    ] {
        let response = Message::from_vec(
            &session
                .resolve_for(&query, None, false, &owner(package))
                .await
                .unwrap(),
        )
        .unwrap();
        let keys = keys(&response);
        assert_eq!(
            keys.contains(&SvcParamKey::EchConfigList),
            ech,
            "{package}: {keys:?}"
        );
        assert!(keys.contains(&SvcParamKey::Alpn), "other parameters stay");
    }
    worker.join().unwrap();
    assert_eq!(stats.snapshot()[3], 1, "one upstream query, then cache");

    // A record listing only `ech` as mandatory loses the whole mandatory parameter.
    let mut message = Message::query();
    let mut record = record(&Name::from_ascii("a.test.").unwrap());
    if let RData::HTTPS(https) = &mut record.data {
        https.0.svc_params[0].1 =
            SvcParamValue::Mandatory(Mandatory(vec![SvcParamKey::EchConfigList]));
    }
    message.answers.push(record);
    dns::strip_ech(&mut message);
    let RData::HTTPS(https) = &message.answers[0].data else {
        unreachable!()
    };
    assert_eq!(
        https.0.svc_params.iter().map(|p| p.0).collect::<Vec<_>>(),
        vec![SvcParamKey::Alpn]
    );
    mitm::configure("{}");
    ca::set(None, vec![]);
    dns::configure("{}");
}
