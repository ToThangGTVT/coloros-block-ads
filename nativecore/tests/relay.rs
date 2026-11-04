use blockads_core::{
    sockets::{self, Protector, Stats},
    tun::{Engine, NetworkConfig},
};
use std::{
    io,
    net::{SocketAddr, UdpSocket},
    os::{
        fd::{AsRawFd, OwnedFd},
        unix::net::UnixDatagram,
    },
    sync::{
        atomic::{AtomicU64, Ordering},
        Arc,
    },
    time::Duration,
};

struct Guard {
    allow: bool,
    calls: AtomicU64,
}
impl Protector for Guard {
    fn protect(&self, fd: i32) -> io::Result<()> {
        // The callback must run on an unconnected socket, before any network traffic.
        let mut addr: libc::sockaddr_storage = unsafe { std::mem::zeroed() };
        let mut len = std::mem::size_of_val(&addr) as libc::socklen_t;
        let result = unsafe {
            libc::getpeername(
                fd,
                (&mut addr as *mut libc::sockaddr_storage).cast(),
                &mut len,
            )
        };
        assert_eq!(result, -1, "socket was connected before protect");
        self.calls.fetch_add(1, Ordering::Relaxed);
        if self.allow {
            Ok(())
        } else {
            Err(io::ErrorKind::PermissionDenied.into())
        }
    }
}
fn guard(allow: bool) -> Arc<Guard> {
    Arc::new(Guard {
        allow,
        calls: AtomicU64::new(0),
    })
}

#[tokio::test]
async fn denied_protection_never_connects_tcp_or_sends_udp() {
    let tcp = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let udp = tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let protector = guard(false);
    let stats = Arc::new(Stats::default());
    assert_eq!(
        sockets::connect_tcp(tcp.local_addr().unwrap(), protector.as_ref(), &stats)
            .await
            .unwrap_err()
            .kind(),
        io::ErrorKind::PermissionDenied
    );
    assert_eq!(
        sockets::connect_udp(udp.local_addr().unwrap(), protector.as_ref(), &stats)
            .await
            .unwrap_err()
            .kind(),
        io::ErrorKind::PermissionDenied
    );
    assert!(
        tokio::time::timeout(Duration::from_millis(30), tcp.accept())
            .await
            .is_err()
    );
    assert!(
        tokio::time::timeout(Duration::from_millis(30), udp.recv(&mut [0; 32]))
            .await
            .is_err()
    );
    assert_eq!(protector.calls.load(Ordering::Relaxed), 2);
    assert_eq!(&stats.snapshot()[..5], &[0, 0, 0, 0, 2]);
}

#[tokio::test]
async fn tcp_protect_precedes_connect_and_half_close_is_preserved() {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let echo = tokio::spawn(async move {
        let (mut socket, _) = listener.accept().await.unwrap();
        let mut request = Vec::new();
        socket.read_to_end(&mut request).await.unwrap();
        assert_eq!(request, b"request");
        socket.write_all(b"reply-after-fin").await.unwrap();
    });
    let protector = guard(true);
    let mut client = sockets::connect_tcp(address, protector.as_ref(), &Arc::new(Stats::default()))
        .await
        .unwrap();
    client.write_all(b"request").await.unwrap();
    client.shutdown().await.unwrap();
    let mut response = Vec::new();
    client.read_to_end(&mut response).await.unwrap();
    assert_eq!(response, b"reply-after-fin");
    echo.await.unwrap();
    assert_eq!(protector.calls.load(Ordering::Relaxed), 1);
}

fn udp_packet(client: SocketAddr, destination: SocketAddr, payload: &[u8]) -> Vec<u8> {
    let builder = match (client, destination) {
        (SocketAddr::V4(a), SocketAddr::V4(b)) => {
            etherparse::PacketBuilder::ipv4(a.ip().octets(), b.ip().octets(), 64)
        }
        (SocketAddr::V6(a), SocketAddr::V6(b)) => {
            etherparse::PacketBuilder::ipv6(a.ip().octets(), b.ip().octets(), 64)
        }
        _ => panic!("address family mismatch"),
    }
    .udp(client.port(), destination.port());
    let mut bytes = Vec::new();
    builder.write(&mut bytes, payload).unwrap();
    bytes
}

#[test]
fn raw_tun_udp_ipv4_ipv6_reuses_nat_and_closes_twenty_times() {
    for v6 in [false, true] {
        let echo = UdpSocket::bind(if v6 { "[::1]:0" } else { "127.0.0.1:0" }).unwrap();
        echo.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
        let destination = echo.local_addr().unwrap();
        let client: SocketAddr = if v6 {
            "[fdfe:dcba:9876::1]:31000"
        } else {
            "172.19.0.1:31000"
        }
        .parse()
        .unwrap();
        let (device, peer) = UnixDatagram::pair().unwrap();
        peer.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
        let protector = guard(true);
        let mut engine = Engine::start(
            OwnedFd::from(device),
            protector.clone(),
            NetworkConfig {
                dns: None,
                ..NetworkConfig::default()
            },
        )
        .unwrap();
        peer.send(&[0x45, 0, 0, 20]).unwrap(); // Malformed input must not kill UDP.
        for payload in [b"first".as_slice(), b"second".as_slice(), b"".as_slice()] {
            peer.send(&udp_packet(client, destination, payload))
                .unwrap();
            let mut data = [0; 4096];
            let (len, source) = echo.recv_from(&mut data).unwrap();
            assert_eq!(&data[..len], payload);
            echo.send_to(&data[..len], source).unwrap();
            let len = peer.recv(&mut data).unwrap();
            let parsed = etherparse::SlicedPacket::from_ip(&data[..len]).unwrap();
            match parsed.transport.unwrap() {
                etherparse::TransportSlice::Udp(packet) => {
                    assert_eq!(packet.source_port(), destination.port());
                    assert_eq!(packet.destination_port(), client.port());
                    assert_eq!(packet.payload(), payload);
                }
                _ => panic!("not UDP"),
            }
        }
        assert_eq!(
            engine.stats.snapshot()[3],
            1,
            "NAT must reuse a protected socket"
        );
        assert_eq!(protector.calls.load(Ordering::Relaxed), 1);
        engine.update_network(NetworkConfig {
            dns: Some("1.1.1.1".parse().unwrap()),
            ..NetworkConfig::default()
        });
        engine.shutdown();
        assert!(!engine.running.load(Ordering::Acquire));
    }
    // Warm runtime globals before measuring. Every subsequent engine must release its fd.
    let fd_count = || {
        std::fs::read_dir(if cfg!(target_os = "linux") {
            "/proc/self/fd"
        } else {
            "/dev/fd"
        })
        .unwrap()
        .count()
    };
    let before = fd_count();
    for _ in 0..20 {
        let (device, peer) = UnixDatagram::pair().unwrap();
        let descriptor = device.as_raw_fd();
        let mut engine = Engine::start(
            OwnedFd::from(device),
            guard(true),
            NetworkConfig {
                dns: None,
                ..NetworkConfig::default()
            },
        )
        .unwrap();
        engine.shutdown();
        assert_eq!(
            unsafe { libc::fcntl(descriptor, libc::F_GETFD) },
            -1,
            "TUN fd leaked"
        );
        drop(peer);
    }
    assert!(
        fd_count() <= before + 1,
        "native descriptors leaked across twenty cycles"
    );
}

#[test]
fn virtual_dns_uses_underlying_server_and_other_destinations_are_unchanged() {
    let config = NetworkConfig {
        dns: Some("192.0.2.53".parse().unwrap()),
        ..NetworkConfig::default()
    };
    assert_eq!(
        config.destination("172.19.0.2:53".parse().unwrap()),
        Some("192.0.2.53:53".parse().unwrap())
    );
    assert_eq!(
        config.destination("[fdfe:dcba:9876::2]:53".parse().unwrap()),
        Some("192.0.2.53:53".parse().unwrap())
    );
    assert_eq!(
        config.destination("203.0.113.1:443".parse().unwrap()),
        Some("203.0.113.1:443".parse().unwrap())
    );
    assert_eq!(
        NetworkConfig {
            dns: None,
            ..NetworkConfig::default()
        }
        .destination("172.19.0.2:53".parse().unwrap()),
        None
    );
}

#[test]
fn raw_tun_tcp_handshake_data_and_response() {
    use std::io::{Read, Write};
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let server = std::thread::spawn(move || {
        let (mut socket, _) = listener.accept().unwrap();
        socket
            .set_read_timeout(Some(Duration::from_secs(3)))
            .unwrap();
        let mut data = [0; 4];
        socket.read_exact(&mut data).unwrap();
        assert_eq!(&data, b"ping");
        socket.write_all(b"pong").unwrap();
    });
    let packet = |seq, ack: Option<u32>, syn, payload: &[u8]| {
        let mut builder = etherparse::PacketBuilder::ipv4([172, 19, 0, 1], [127, 0, 0, 1], 64)
            .tcp(32000, port, seq, 65535);
        if syn {
            builder = builder.syn();
        }
        if let Some(ack) = ack {
            builder = builder.ack(ack);
        }
        let mut bytes = Vec::new();
        builder.write(&mut bytes, payload).unwrap();
        bytes
    };
    let (device, peer) = UnixDatagram::pair().unwrap();
    peer.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
    let mut engine = Engine::start(
        OwnedFd::from(device),
        guard(true),
        NetworkConfig {
            dns: None,
            ..NetworkConfig::default()
        },
    )
    .unwrap();
    peer.send(&packet(1000, None, true, &[])).unwrap();
    let mut bytes = [0; 4096];
    let len = peer.recv(&mut bytes).unwrap();
    let parsed = etherparse::SlicedPacket::from_ip(&bytes[..len]).unwrap();
    let Some(etherparse::TransportSlice::Tcp(tcp)) = parsed.transport else {
        panic!("not TCP")
    };
    assert!(tcp.syn() && tcp.ack());
    let ack = tcp.sequence_number().wrapping_add(1);
    peer.send(&packet(1001, Some(ack), false, &[])).unwrap();
    peer.send(&packet(1001, Some(ack), false, b"ping")).unwrap();
    let mut received = false;
    for _ in 0..8 {
        let len = peer.recv(&mut bytes).unwrap();
        let parsed = etherparse::SlicedPacket::from_ip(&bytes[..len]).unwrap();
        if let Some(etherparse::TransportSlice::Tcp(tcp)) = parsed.transport {
            if !tcp.payload().is_empty() {
                assert_eq!(tcp.payload(), b"pong");
                received = true;
                peer.send(&packet(
                    1005,
                    Some(tcp.sequence_number().wrapping_add(4)),
                    false,
                    &[],
                ))
                .unwrap();
                break;
            }
        }
    }
    assert!(received);
    server.join().unwrap();
    engine.shutdown();
}

#[test]
fn raw_tun_tcp_segments_use_the_large_tun_mtu() {
    use std::io::Write;
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let server = std::thread::spawn(move || {
        let (mut socket, _) = listener.accept().unwrap();
        socket.write_all(&[7; 20_000]).unwrap();
    });
    let mss = (blockads_core::tun::MTU - 40) as u16;
    let packet = |seq, ack: Option<u32>, syn, payload: &[u8]| {
        let mut builder = etherparse::PacketBuilder::ipv4([172, 19, 0, 1], [127, 0, 0, 1], 64)
            .tcp(32003, port, seq, 65535);
        if syn {
            builder = builder
                .syn()
                .options(&[etherparse::TcpOptionElement::MaximumSegmentSize(mss)])
                .unwrap();
        }
        if let Some(ack) = ack {
            builder = builder.ack(ack);
        }
        let mut bytes = Vec::new();
        builder.write(&mut bytes, payload).unwrap();
        bytes
    };
    let (device, peer) = UnixDatagram::pair().unwrap();
    // macOS limits datagrams to 2 KiB unless the socket buffers are raised.
    for socket in [&device, &peer] {
        let size: libc::c_int = 1 << 20;
        for option in [libc::SO_SNDBUF, libc::SO_RCVBUF] {
            let result = unsafe {
                libc::setsockopt(
                    socket.as_raw_fd(),
                    libc::SOL_SOCKET,
                    option,
                    (&size as *const libc::c_int).cast(),
                    std::mem::size_of_val(&size) as libc::socklen_t,
                )
            };
            assert_eq!(result, 0);
        }
    }
    peer.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
    let mut engine =
        Engine::start(OwnedFd::from(device), guard(true), NetworkConfig::default()).unwrap();
    peer.send(&packet(1000, None, true, &[])).unwrap();
    let mut bytes = vec![0; 65535];
    let len = peer.recv(&mut bytes).unwrap();
    let parsed = etherparse::SlicedPacket::from_ip(&bytes[..len]).unwrap();
    let Some(etherparse::TransportSlice::Tcp(tcp)) = parsed.transport else {
        panic!("not TCP")
    };
    assert!(tcp.syn() && tcp.ack());
    let ack = tcp.sequence_number().wrapping_add(1);
    peer.send(&packet(1001, Some(ack), false, &[])).unwrap();
    // Lowercase first byte: not HTTP or TLS, so the relay connects without waiting for more.
    peer.send(&packet(1001, Some(ack), false, b"x")).unwrap();
    let mut largest = 0;
    while largest <= 1500 {
        let len = peer.recv(&mut bytes).unwrap();
        let parsed = etherparse::SlicedPacket::from_ip(&bytes[..len]).unwrap();
        if let Some(etherparse::TransportSlice::Tcp(tcp)) = parsed.transport {
            largest = largest.max(tcp.payload().len());
        }
    }
    assert!(
        largest <= mss as usize,
        "segment exceeded the advertised MSS"
    );
    server.join().unwrap();
    engine.shutdown();
}
