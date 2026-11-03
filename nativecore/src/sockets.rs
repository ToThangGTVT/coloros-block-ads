//! The only constructors for outbound data sockets. Fail closed before connect/send.
use socket2::{Domain, Protocol, Socket, Type};
use std::{
    io,
    net::SocketAddr,
    os::fd::{AsRawFd, RawFd},
    sync::{
        atomic::{AtomicU64, Ordering},
        Arc,
    },
};
use tokio::net::{TcpSocket, TcpStream, UdpSocket};

pub trait Protector: Send + Sync {
    fn protect(&self, fd: RawFd) -> io::Result<()>;
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
    let socket = if addr.is_ipv4() {
        TcpSocket::new_v4()?
    } else {
        TcpSocket::new_v6()?
    };
    protect(socket.as_raw_fd(), protector, stats)?;
    let stream =
        tokio::time::timeout(std::time::Duration::from_secs(15), socket.connect(addr)).await??;
    stream.set_nodelay(true)?;
    stats.tcp_opened.fetch_add(1, Ordering::Relaxed);
    Ok(stream)
}

pub async fn connect_udp(
    addr: SocketAddr,
    protector: &dyn Protector,
    stats: &Arc<Stats>,
) -> io::Result<UdpSocket> {
    let socket = Socket::new(Domain::for_address(addr), Type::DGRAM, Some(Protocol::UDP))?;
    protect(socket.as_raw_fd(), protector, stats)?;
    socket.set_nonblocking(true)?;
    let local: SocketAddr = if addr.is_ipv4() {
        "0.0.0.0:0"
    } else {
        "[::]:0"
    }
    .parse()
    .expect("static address");
    socket.bind(&local.into())?;
    let socket = UdpSocket::from_std(socket.into())?;
    socket.connect(addr).await?;
    stats.udp_opened.fetch_add(1, Ordering::Relaxed);
    Ok(socket)
}
