//! The only constructors for outbound data sockets. Fail closed before connect/send.
use socket2::{Domain, Protocol, Socket, Type};
use std::{
    io,
    net::SocketAddr,
    os::fd::{AsRawFd, RawFd},
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        Arc,
    },
};
use tokio::net::{TcpSocket, TcpStream, UdpSocket};

pub trait Protector: Send + Sync {
    fn protect(&self, fd: RawFd) -> io::Result<()>;
    fn owner(&self, _protocol: u8, _source: SocketAddr, _destination: SocketAddr) -> Option<u32> {
        None
    }
}

#[derive(Default)]
pub struct Stats {
    pub packets_in: AtomicU64,
    pub packets_out: AtomicU64,
    pub tcp_opened: AtomicU64,
    pub udp_opened: AtomicU64,
    pub protect_denied: AtomicU64,
    pub tcp_up: AtomicU64,
    pub tcp_down: AtomicU64,
    pub network_resets: AtomicU64,
}
impl Stats {
    pub fn snapshot(&self) -> [i64; 8] {
        [
            &self.packets_in,
            &self.packets_out,
            &self.tcp_opened,
            &self.udp_opened,
            &self.protect_denied,
            &self.tcp_up,
            &self.tcp_down,
            &self.network_resets,
        ]
        .map(|value| value.load(Ordering::Relaxed) as i64)
    }
}

static IPV6: AtomicBool = AtomicBool::new(true);
/// Whether the physical network can reach the IPv6 internet. The VPN always offers an IPv6
/// route, so without this apps would open IPv6 connections the engine cannot forward.
pub fn ipv6_available() -> bool {
    IPV6.load(Ordering::Acquire)
}
pub fn set_ipv6_available(available: bool) {
    IPV6.store(available, Ordering::Release);
}
/// Asks the kernel for a route from a protected socket. UDP connect() sends nothing. A failed
/// protect leaves the previous answer: it says nothing about the physical network.
pub fn probe_ipv6(protector: &dyn Protector) {
    let Ok(socket) = Socket::new(Domain::IPV6, Type::DGRAM, Some(Protocol::UDP)) else {
        return;
    };
    if protector.protect(socket.as_raw_fd()).is_err() {
        return;
    }
    let public = SocketAddr::from(([0x2001, 0x4860, 0x4860, 0, 0, 0, 0, 0x8888], 53));
    set_ipv6_available(socket.connect(&public.into()).is_ok());
}
fn protect(fd: RawFd, protector: &dyn Protector, stats: &Stats) -> io::Result<()> {
    protector.protect(fd).inspect_err(|_| {
        stats.protect_denied.fetch_add(1, Ordering::Relaxed);
    })
}

pub async fn connect_tcp(
    addr: SocketAddr,
    protector: &dyn Protector,
    stats: &Arc<Stats>,
) -> io::Result<TcpStream> {
    connect_tcp_bound(addr, None, protector, stats).await
}
pub async fn connect_tcp_bound(
    addr: SocketAddr,
    bind: Option<SocketAddr>,
    protector: &dyn Protector,
    stats: &Arc<Stats>,
) -> io::Result<TcpStream> {
    let socket = if addr.is_ipv4() {
        TcpSocket::new_v4()?
    } else {
        TcpSocket::new_v6()?
    };
    protect(socket.as_raw_fd(), protector, stats)?;
    if let Some(local) = bind {
        socket.bind(local)?;
    }
    let stream =
        tokio::time::timeout(std::time::Duration::from_secs(15), socket.connect(addr)).await??;
    stream.set_nodelay(true)?;
    stats.tcp_opened.fetch_add(1, Ordering::Relaxed);
    Ok(stream)
}

pub fn bind_udp(
    local: SocketAddr,
    protector: &dyn Protector,
    stats: &Arc<Stats>,
) -> io::Result<std::net::UdpSocket> {
    let socket = Socket::new(Domain::for_address(local), Type::DGRAM, Some(Protocol::UDP))?;
    protect(socket.as_raw_fd(), protector, stats)?;
    socket.set_nonblocking(true)?;
    socket.bind(&local.into())?;
    stats.udp_opened.fetch_add(1, Ordering::Relaxed);
    Ok(socket.into())
}
pub async fn connect_udp(
    addr: SocketAddr,
    protector: &dyn Protector,
    stats: &Arc<Stats>,
) -> io::Result<UdpSocket> {
    let local = if addr.is_ipv4() {
        "0.0.0.0:0"
    } else {
        "[::]:0"
    }
    .parse()
    .expect("static address");
    let socket = UdpSocket::from_std(bind_udp(local, protector, stats)?)?;
    socket.connect(addr).await?;
    Ok(socket)
}
