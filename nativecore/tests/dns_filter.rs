use blockads_core::{
    dns::{self, Session},
    filter::{self, Rules},
    sockets::{Protector, Stats},
};
use hickory_resolver::proto::{
    op::{Message, Query, ResponseCode},
    rr::{
        rdata::{A, SOA},
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
struct Deny;
impl Protector for Deny {
    fn protect(&self, _: i32) -> io::Result<()> {
        Err(io::ErrorKind::PermissionDenied.into())
    }
}
fn query(name: &str, id: u16, kind: RecordType) -> Vec<u8> {
    let mut message = Message::query();
    message.metadata.id = id;
    message.metadata.recursion_desired = true;
    message
        .queries
        .push(Query::query(Name::from_ascii(name).unwrap(), kind));
    message.to_vec().unwrap()
}
#[test]
fn hosts_suffix_and_priority_are_deterministic() {
    let rules = Rules::compile("! comment\n0.0.0.0 exact.test another.test\n||ads.test^\n@@||trusted.ads.test^\n||important.test^$important\n@@||important.test^\nexample.test##.banner", "||trusted.ads.test^\n@@||important.test^");
    assert!(rules.lookup("exact.test").unwrap().blocked);
    assert!(rules.lookup("sub.exact.test").is_none());
    assert!(rules.lookup("sub.ads.test").unwrap().blocked);
    assert!(
        rules.lookup("trusted.ads.test").unwrap().blocked,
        "custom block must outrank list allow"
    );
    assert!(
        !rules.lookup("important.test").unwrap().blocked,
        "custom allow must outrank important"
    );
    assert_eq!(rules.report.ignored, 0);
    assert!(rules
        .cosmetic_css("example.test")
        .contains(".banner{display:none!important}"));
    assert!(Rules::compile("", "! ok\n||bad..domain^\n||good.test^")
        .report
        .errors
        .iter()
        .any(|e| e.line == 2));
}
#[tokio::test]
async fn blocked_a_aaaa_and_nxdomain_never_open_an_upstream_socket() {
    filter::replace("0.0.0.0 blocked.test", "");
    dns::configure(r#"{"protocol":"udp","ip":"127.0.0.1","blockMode":"zero","logging":false}"#);
    let stats = Arc::new(Stats::default());
    let session = Session::new(Arc::new(Deny), stats.clone());
    for (kind, expected) in [(RecordType::A, "0.0.0.0"), (RecordType::AAAA, "::")] {
        let response = session
            .resolve(&query("blocked.test.", 1234, kind), None, false)
            .await
            .unwrap();
        let response = Message::from_vec(&response).unwrap();
        assert_eq!(response.metadata.id, 1234);
        assert_eq!(response.answers[0].data.to_string(), expected);
        assert!(!response.metadata.authentic_data);
    }
    dns::configure(r#"{"protocol":"udp","ip":"127.0.0.1","blockMode":"nxdomain","logging":false}"#);
    let response = Message::from_vec(
        &session
            .resolve(&query("blocked.test.", 4321, RecordType::A), None, false)
            .await
            .unwrap(),
    )
    .unwrap();
    assert_eq!(response.metadata.response_code, ResponseCode::NXDomain);
    assert!(response.answers.is_empty());
    assert_eq!(stats.snapshot()[2], 0);
    assert_eq!(stats.snapshot()[3], 0);
    assert_eq!(stats.snapshot()[4], 0);
    filter::replace("", "");
    dns::configure("{}");
}
#[tokio::test]
async fn positive_and_negative_cache_preserve_ids_and_soa() {
    filter::replace("", "");
    let server = UdpSocket::bind("127.0.0.1:0").unwrap();
    server
        .set_read_timeout(Some(Duration::from_secs(3)))
        .unwrap();
    let port = server.local_addr().unwrap().port();
    let worker = std::thread::spawn(move || {
        for _ in 0..2 {
            let mut data = [0; 4096];
            let (len, peer) = server.recv_from(&mut data).unwrap();
            let request = Message::from_vec(&data[..len]).unwrap();
            let mut response = dns::error_response(&request, ResponseCode::NoError);
            if request.queries[0].name().to_ascii() == "missing.test." {
                response.metadata.response_code = ResponseCode::NXDomain;
                response.metadata.authoritative = true;
                response.authorities.push(Record::from_rdata(
                    Name::from_ascii("test.").unwrap(),
                    60,
                    RData::SOA(SOA::new(
                        Name::from_ascii("ns.test.").unwrap(),
                        Name::from_ascii("hostmaster.test.").unwrap(),
                        1,
                        60,
                        60,
                        300,
                        30,
                    )),
                ));
            } else {
                response.answers.push(Record::from_rdata(
                    request.queries[0].name().clone(),
                    30,
                    RData::A(A::new(203, 0, 113, 7)),
                ));
            }
            server.send_to(&response.to_vec().unwrap(), peer).unwrap();
        }
    });
    assert!(dns::configure(&format!(
        r#"{{"protocol":"udp","ip":"127.0.0.1","port":{port},"logging":false}}"#
    )));
    let stats = Arc::new(Stats::default());
    let session = Session::new(Arc::new(Allow), stats.clone());
    for (name, expected) in [
        ("allowed.test.", ResponseCode::NoError),
        ("missing.test.", ResponseCode::NXDomain),
    ] {
        for id in [100, 200] {
            let response = Message::from_vec(
                &session
                    .resolve(&query(name, id, RecordType::A), None, false)
                    .await
                    .unwrap(),
            )
            .unwrap();
            assert_eq!(response.metadata.id, id);
            assert_eq!(response.metadata.response_code, expected);
            if expected == ResponseCode::NXDomain {
                assert_eq!(response.authorities.len(), 1);
            }
        }
    }
    worker.join().unwrap();
    assert_eq!(
        stats.snapshot()[3],
        2,
        "second query must use cache instead of another socket"
    );
    dns::configure("{}");
}
#[test]
fn invalid_custom_reload_does_not_replace_previous_rules() {
    assert!(filter::replace("||kept.test^", "").applied);
    assert!(!filter::replace("", "||bad..domain^").applied);
    assert!(filter::current().lookup("kept.test").unwrap().blocked);
    filter::replace("", "");
}

#[tokio::test]
async fn disabling_logs_keeps_only_aggregate_counts_in_batches() {
    struct Capture(std::sync::Mutex<Vec<String>>);
    impl dns::EventSink for Capture {
        fn batch(&self, json: &str) {
            self.0.lock().unwrap().push(json.to_owned());
        }
    }
    let sink = Arc::new(Capture(std::sync::Mutex::new(vec![])));
    dns::set_sink(Some(sink.clone()));
    dns::configure(r#"{"logging":false}"#);
    filter::replace("||stats.test^", "");
    let session = Session::new(Arc::new(Deny), Arc::new(Stats::default()));
    session
        .resolve(&query("stats.test", 1, RecordType::A), None, false)
        .await
        .unwrap();
    dns::flush();
    let json: serde_json::Value = serde_json::from_str(&sink.0.lock().unwrap()[0]).unwrap();
    assert!(json["events"].as_array().unwrap().is_empty());
    assert!(json["totals"]
        .as_array()
        .unwrap()
        .iter()
        .any(|v| v["blocked"].as_u64().unwrap() > 0));
    assert!(!json.to_string().contains("stats.test"));
    dns::set_sink(None);
    dns::configure("{}");
    filter::replace("", "");
}
