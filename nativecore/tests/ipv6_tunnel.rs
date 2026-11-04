use blockads_core::{
    sockets::{self, Protector},
    tun::{Engine, NetworkConfig},
};
use std::{
    io,
    net::{Ipv6Addr, SocketAddr},
    os::{fd::OwnedFd, unix::net::UnixDatagram},
    sync::{
        atomic::{AtomicU64, Ordering},
        Arc,
    },
    time::Duration,
};

struct Guard(AtomicU64);
impl Protector for Guard {
    fn protect(&self, _: i32) -> io::Result<()> {
        self.0.fetch_add(1, Ordering::Relaxed);
        Ok(())
    }
    fn owner(&self, _: u8, _: SocketAddr, _: SocketAddr) -> Option<u32> {
        Some(54321)
    }
}
fn segment(sequence: u32, ack: Option<u32>, syn: bool) -> Vec<u8> {
    let client: Ipv6Addr = "fdfe:dcba:9876::1".parse().unwrap();
    let mut builder = etherparse::PacketBuilder::ipv6(
        client.octets(),
        "2001:db8::443".parse::<Ipv6Addr>().unwrap().octets(),
        64,
    )
    .tcp(40000, 443, sequence, 65535);
    if syn {
        builder = builder.syn();
    }
    if let Some(ack) = ack {
        builder = builder.ack(ack);
    }
    let mut bytes = vec![];
    builder.write(&mut bytes, &[]).unwrap();
    bytes
}

#[test]
fn ipv6_connection_is_reset_without_an_upstream_attempt_when_the_network_has_no_ipv6() {
    let (tun, peer) = UnixDatagram::pair().unwrap();
    peer.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
    let guard = Arc::new(Guard(AtomicU64::new(0)));
    let mut engine =
        Engine::start(OwnedFd::from(tun), guard.clone(), NetworkConfig::default()).unwrap();
    sockets::set_ipv6_available(false);
    peer.send(&segment(1000, None, true)).unwrap();
    let mut bytes = [0; 4096];
    let mut reset = false;
    while let Ok(len) = peer.recv(&mut bytes) {
        let Some(etherparse::TransportSlice::Tcp(tcp)) =
            etherparse::SlicedPacket::from_ip(&bytes[..len])
                .unwrap()
                .transport
        else {
            continue;
        };
        if tcp.syn() && tcp.ack() {
            peer.send(&segment(
                1001,
                Some(tcp.sequence_number().wrapping_add(1)),
                false,
            ))
            .unwrap();
        }
        if tcp.rst() {
            reset = true;
            break;
        }
    }
    assert!(reset, "the app must get a reset to fall back to IPv4");
    assert_eq!(guard.0.load(Ordering::Relaxed), 0, "no upstream socket");
    sockets::set_ipv6_available(true);
    engine.shutdown();
}
