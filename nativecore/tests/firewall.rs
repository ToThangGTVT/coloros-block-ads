use blockads_core::{
    policy,
    sockets::Protector,
    tun::{Engine, NetworkConfig},
};
use std::{
    io,
    net::{SocketAddr, UdpSocket},
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
    fn owner(&self, _: u8, source: SocketAddr, _: SocketAddr) -> Option<u32> {
        Some(if source.port() == 32000 { 12345 } else { 54321 })
    }
}

fn packet(
    v6: bool,
    port: u16,
    destination: u16,
    tcp: Option<(u32, Option<u32>, bool)>,
    data: &[u8],
) -> Vec<u8> {
    let builder = if v6 {
        etherparse::PacketBuilder::ipv6(
            "fdfe:dcba:9876::1"
                .parse::<std::net::Ipv6Addr>()
                .unwrap()
                .octets(),
            std::net::Ipv6Addr::LOCALHOST.octets(),
            64,
        )
    } else {
        etherparse::PacketBuilder::ipv4([172, 19, 0, 1], [127, 0, 0, 1], 64)
    };
    let mut bytes = vec![];
    if let Some((sequence, ack, syn)) = tcp {
        let mut builder = builder.tcp(port, destination, sequence, 65535);
        if syn {
            builder = builder.syn();
        }
        if let Some(ack) = ack {
            builder = builder.ack(ack);
        }
        builder.write(&mut bytes, data).unwrap();
    } else {
        builder
            .udp(port, destination)
            .write(&mut bytes, data)
            .unwrap();
    }
    bytes
}

// Keep global policy mutations in one test; cover real TUN packets rather than only JSON.
#[test]
fn firewall_blocks_tcp_dns_udp_and_ipv6_overrides_bypass_and_revokes_existing_udp() {
    for v6 in [false, true] {
        assert!(policy::configure(
            r#"{"blockedUids":[12345],"bypassUids":[12345],"blockQuic":false}"#
        ));
        assert!(policy::Owner::from_uid(Some(12345)).blocked);
        assert!(!policy::Owner::from_uid(Some(12345)).bypass);
        assert!(!policy::Owner::from_uid(None).blocked);
        let (tun, peer) = UnixDatagram::pair().unwrap();
        peer.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        let guard = Arc::new(Guard(AtomicU64::new(0)));
        let mut engine =
            Engine::start(OwnedFd::from(tun), guard.clone(), NetworkConfig::default()).unwrap();
        for destination in [53, 443, 12345] {
            peer.send(&packet(v6, 32000, destination, None, b"blocked"))
                .unwrap();
        }
        for destination in [53, 443] {
            peer.send(&packet(
                v6,
                32000,
                destination,
                Some((1000, None, true)),
                &[],
            ))
            .unwrap();
            let mut bytes = [0; 4096];
            loop {
                let len = peer.recv(&mut bytes).unwrap_or_else(|error| {
                    panic!(
                        "TCP v6={v6} port={destination}: {error}, stats={:?}",
                        engine.stats.snapshot()
                    )
                });
                if let Some(etherparse::TransportSlice::Tcp(tcp)) =
                    etherparse::SlicedPacket::from_ip(&bytes[..len])
                        .unwrap()
                        .transport
                {
                    if tcp.syn() && tcp.ack() && tcp.source_port() == destination {
                        let ack = tcp.sequence_number().wrapping_add(1);
                        peer.send(&packet(
                            v6,
                            32000,
                            destination,
                            Some((1001, Some(ack), false)),
                            b"blocked",
                        ))
                        .unwrap();
                    }
                    if tcp.rst() || tcp.fin() {
                        break;
                    }
                }
            }
        }
        assert_eq!(
            guard.0.load(Ordering::Relaxed),
            0,
            "blocked flow opened an upstream socket"
        );

        let echo = UdpSocket::bind(if v6 { "[::1]:0" } else { "127.0.0.1:0" }).unwrap();
        echo.set_read_timeout(Some(Duration::from_millis(200)))
            .unwrap();
        let destination = echo.local_addr().unwrap().port();
        let mut buffer = [0; 4096];
        // An unrelated UID is still allowed while the firewall blocks 12345.
        peer.send(&packet(v6, 32001, destination, None, b"other-app"))
            .unwrap();
        let (len, _) = echo.recv_from(&mut buffer).unwrap();
        assert_eq!(&buffer[..len], b"other-app");

        assert!(policy::configure(r#"{"blockQuic":false}"#));
        peer.send(&packet(v6, 32000, destination, None, b"allowed"))
            .unwrap();
        let (len, upstream) = echo.recv_from(&mut buffer).unwrap();
        assert_eq!(&buffer[..len], b"allowed");
        echo.send_to(b"reply", upstream).unwrap();
        loop {
            let len = peer.recv(&mut buffer).unwrap();
            if matches!(
                etherparse::SlicedPacket::from_ip(&buffer[..len])
                    .unwrap()
                    .transport,
                Some(etherparse::TransportSlice::Udp(_))
            ) {
                break;
            }
        }

        assert!(policy::configure(
            r#"{"blockedUids":[12345],"blockQuic":false}"#
        ));
        std::thread::sleep(Duration::from_millis(50));
        // Same NAT flow must be revoked, including unsolicited incoming packets.
        echo.send_to(b"late-reply", upstream).unwrap();
        peer.send(&packet(v6, 32000, destination, None, b"must-not-leak"))
            .unwrap();
        assert!(echo.recv_from(&mut buffer).is_err());
        peer.set_read_timeout(Some(Duration::from_millis(100)))
            .unwrap();
        while let Ok(len) = peer.recv(&mut buffer) {
            assert!(
                !matches!(
                    etherparse::SlicedPacket::from_ip(&buffer[..len])
                        .unwrap()
                        .transport,
                    Some(etherparse::TransportSlice::Udp(_))
                ),
                "blocked UDP reply reached the app"
            );
        }
        assert!(engine.running.load(Ordering::Acquire));
        engine.shutdown();
    }
    policy::configure("{}");
}
