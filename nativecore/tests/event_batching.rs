use blockads_core::{
    dns::{self, EventSink},
    sockets::Protector,
    tun::{Engine, NetworkConfig},
};
use std::{
    io,
    os::{fd::OwnedFd, unix::net::UnixDatagram},
    sync::{mpsc, Arc},
    time::Duration,
};

struct Allow;
impl Protector for Allow {
    fn protect(&self, _: i32) -> io::Result<()> {
        Ok(())
    }
}
struct Capture(mpsc::Sender<String>);
impl EventSink for Capture {
    fn batch(&self, json: &str) {
        self.0.send(json.to_owned()).unwrap();
    }
}
fn event() {
    dns::event("batch.test".into(), false, None, false, None, "dns");
}
fn assert_batch(json: &str, count: usize) {
    let value: serde_json::Value = serde_json::from_str(json).unwrap();
    assert_eq!(value["events"].as_array().unwrap().len(), count);
    let total: u64 = value["totals"]
        .as_array()
        .unwrap()
        .iter()
        .map(|row| row["total"].as_u64().unwrap())
        .sum();
    assert_eq!(total, count as u64);
}

#[test]
fn metadata_waits_for_longer_interval_full_batch_or_shutdown() {
    dns::configure("{}");
    dns::flush();
    let (tx, rx) = mpsc::channel();
    dns::set_sink(Some(Arc::new(Capture(tx))));
    let (tun, _peer) = UnixDatagram::pair().unwrap();
    let mut engine = Engine::start(
        OwnedFd::from(tun),
        Arc::new(Allow),
        NetworkConfig::default(),
    )
    .unwrap();

    for _ in 0..3 {
        event();
    }
    assert!(matches!(
        rx.recv_timeout(Duration::from_millis(750)),
        Err(mpsc::RecvTimeoutError::Timeout)
    ));
    assert_batch(&rx.recv_timeout(Duration::from_secs(6)).unwrap(), 3);

    // Busy traffic remains bounded by the existing 100-event batch threshold.
    for _ in 0..100 {
        event();
    }
    assert_batch(&rx.recv_timeout(Duration::from_secs(1)).unwrap(), 100);

    event();
    engine.shutdown();
    assert_batch(&rx.recv_timeout(Duration::from_secs(1)).unwrap(), 1);
    assert!(rx.try_recv().is_err());
    dns::set_sink(None);
}
