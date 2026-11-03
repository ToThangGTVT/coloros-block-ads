//! Hickory runtime override: no DNS transport may create an unprotected socket.
use crate::sockets::{self, Protector, Stats};
use hickory_resolver::{
    config::{NameServerConfig, ResolverOpts},
    net::runtime::{
        iocompat::AsyncIoTokioAsStd, QuicSocketBinder, RuntimeProvider, TokioHandle, TokioTime,
    },
    NameServerPool, PoolContext, TlsConfig,
};
use quinn::Runtime;
use std::{
    future::Future,
    io,
    net::{IpAddr, SocketAddr},
    pin::Pin,
    sync::Arc,
    time::Duration,
};

#[derive(Clone)]
pub struct ProtectedRuntime {
    pub protector: Arc<dyn Protector>,
    pub stats: Arc<Stats>,
    handle: TokioHandle,
}
impl ProtectedRuntime {
    pub fn new(protector: Arc<dyn Protector>, stats: Arc<Stats>) -> Self {
        Self {
            protector,
            stats,
            handle: TokioHandle::default(),
        }
    }
}
impl RuntimeProvider for ProtectedRuntime {
    type Handle = TokioHandle;
    type Timer = TokioTime;
    type Udp = tokio::net::UdpSocket;
    type Tcp = AsyncIoTokioAsStd<tokio::net::TcpStream>;
    fn create_handle(&self) -> Self::Handle {
        self.handle.clone()
    }
    fn connect_tcp(
        &self,
        server: SocketAddr,
        bind: Option<SocketAddr>,
        _timeout: Option<Duration>,
    ) -> Pin<Box<dyn Send + Future<Output = io::Result<Self::Tcp>>>> {
        let this = self.clone();
        Box::pin(async move {
            sockets::connect_tcp_bound(server, bind, this.protector.as_ref(), &this.stats)
                .await
                .map(AsyncIoTokioAsStd)
        })
    }
    fn bind_udp(
        &self,
        local: SocketAddr,
        _server: SocketAddr,
    ) -> Pin<Box<dyn Send + Future<Output = io::Result<Self::Udp>>>> {
        let this = self.clone();
        Box::pin(async move {
            tokio::net::UdpSocket::from_std(sockets::bind_udp(
                local,
                this.protector.as_ref(),
                &this.stats,
            )?)
        })
    }
    fn quic_binder(&self) -> Option<&dyn QuicSocketBinder> {
        Some(self)
    }
}
impl QuicSocketBinder for ProtectedRuntime {
    fn bind_quic(
        &self,
        local: SocketAddr,
        _server: SocketAddr,
    ) -> io::Result<Arc<dyn quinn::AsyncUdpSocket>> {
        quinn::TokioRuntime.wrap_udp_socket(sockets::bind_udp(
            local,
            self.protector.as_ref(),
            &self.stats,
        )?)
    }
}
pub fn pool(
    config: &super::Options,
    physical_dns: Option<IpAddr>,
    runtime: ProtectedRuntime,
) -> io::Result<NameServerPool<ProtectedRuntime>> {
    let ip = if config.protocol == "system" {
        physical_dns.ok_or(io::ErrorKind::NotConnected)?
    } else {
        config.ip.parse().map_err(|_| io::ErrorKind::InvalidInput)?
    };
    let name: Arc<str> = config.server_name.as_str().into();
    let mut server = match config.protocol.as_str() {
        "system" | "udp" => NameServerConfig::udp_and_tcp(ip),
        "tcp" => NameServerConfig::tcp(ip),
        "tls" => NameServerConfig::tls(ip, name),
        "https" => NameServerConfig::https(ip, name, Some(config.path.as_str().into())),
        "quic" => NameServerConfig::quic(ip, name),
        _ => return Err(io::ErrorKind::InvalidInput.into()),
    };
    let port = if config.port > 0 {
        config.port
    } else {
        match config.protocol.as_str() {
            "https" => 443,
            "tls" | "quic" => 853,
            _ => 53,
        }
    };
    for connection in &mut server.connections {
        connection.port = port;
    }
    let mut options = ResolverOpts::default();
    options.timeout = Duration::from_secs(5);
    options.attempts = 2;
    options.cache_size = 0;
    let tls = TlsConfig::new().map_err(io::Error::other)?;
    Ok(NameServerPool::from_config(
        [server],
        Arc::new(PoolContext::new(options, tls)),
        runtime,
    ))
}
