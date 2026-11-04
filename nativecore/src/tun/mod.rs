//! Owned nonblocking TUN descriptor, userspace stack and deterministic runtime lifetime.
use crate::sockets::{Protector, Stats};
use futures_util::{SinkExt, StreamExt};
use std::{
    io,
    net::{IpAddr, SocketAddr},
    os::fd::{AsRawFd, OwnedFd},
    sync::{
        atomic::{AtomicBool, AtomicU8, Ordering},
        Arc,
    },
    thread::JoinHandle,
};
use tokio::{
    io::unix::AsyncFd,
    sync::{oneshot, watch},
    task::JoinSet,
};

/// Must match `setMtu` in BlockAdsVpnService. This only sizes packets between apps and the engine;
/// the kernel segments protected upstream sockets for the real path, so it never reaches the radio.
pub const MTU: usize = 9000;

#[derive(Clone, Default)]
pub struct NetworkConfig {
    pub dns: Option<IpAddr>,
    pub applied: Option<Arc<AtomicU8>>,
    /// Probe whether the physical network has IPv6 (one protected UDP socket, nothing sent).
    pub probe_ipv6: bool,
}
impl NetworkConfig {
    pub fn destination(&self, original: SocketAddr) -> Option<SocketAddr> {
        let is_virtual = original.ip() == "172.19.0.2".parse::<IpAddr>().expect("static IP")
            || original.ip() == "fdfe:dcba:9876::2".parse::<IpAddr>().expect("static IP");
        if is_virtual && original.port() == 53 {
            self.dns.map(|ip| SocketAddr::new(ip, 53))
        } else {
            Some(original)
        }
    }
}

pub struct TunDevice(AsyncFd<OwnedFd>);
impl TunDevice {
    pub fn new(fd: OwnedFd) -> io::Result<Self> {
        // SAFETY: fd is uniquely owned here and valid for both fcntl calls.
        unsafe {
            let flags = libc::fcntl(fd.as_raw_fd(), libc::F_GETFL);
            if flags < 0 || libc::fcntl(fd.as_raw_fd(), libc::F_SETFL, flags | libc::O_NONBLOCK) < 0
            {
                return Err(io::Error::last_os_error());
            }
        }
        // SAFETY: OwnedFd is never externally closed, replaced or reused while registered.
        Ok(Self(unsafe { AsyncFd::register(fd)? }))
    }
    pub async fn read(&self, buffer: &mut [u8]) -> io::Result<usize> {
        loop {
            let mut guard = self.0.readable().await?;
            match guard.try_io(|fd| {
                // SAFETY: the slice is writable and fd remains alive across the syscall.
                let count = unsafe {
                    libc::read(
                        fd.get_ref().as_raw_fd(),
                        buffer.as_mut_ptr().cast(),
                        buffer.len(),
                    )
                };
                if count < 0 {
                    Err(io::Error::last_os_error())
                } else {
                    Ok(count as usize)
                }
            }) {
                Ok(result) => return result,
                Err(_) => continue,
            }
        }
    }
    pub async fn write(&self, buffer: &[u8]) -> io::Result<()> {
        loop {
            let mut guard = self.0.writable().await?;
            match guard.try_io(|fd| {
                // SAFETY: the slice is readable and fd remains alive across the syscall.
                let count = unsafe {
                    libc::write(
                        fd.get_ref().as_raw_fd(),
                        buffer.as_ptr().cast(),
                        buffer.len(),
                    )
                };
                if count < 0 {
                    Err(io::Error::last_os_error())
                } else if count as usize != buffer.len() {
                    Err(io::ErrorKind::WriteZero.into())
                } else {
                    Ok(())
                }
            }) {
                Ok(result) => return result,
                Err(_) => continue,
            }
        }
    }
}

pub struct Engine {
    stop: Option<oneshot::Sender<()>>,
    network: watch::Sender<NetworkConfig>,
    thread: Option<JoinHandle<()>>,
    protector: Arc<dyn Protector>,
    pub running: Arc<AtomicBool>,
    pub stats: Arc<Stats>,
}
impl Engine {
    pub fn start(
        fd: OwnedFd,
        protector: Arc<dyn Protector>,
        config: NetworkConfig,
    ) -> io::Result<Self> {
        if config.probe_ipv6 {
            crate::sockets::probe_ipv6(protector.as_ref());
        }
        let engine_protector = protector.clone();
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()?;
        let (stop_tx, stop_rx) = oneshot::channel();
        let (network_tx, network_rx) = watch::channel(config);
        let (ready_tx, ready_rx) = std::sync::mpsc::sync_channel(1);
        let running = Arc::new(AtomicBool::new(true));
        let stats = Arc::new(Stats::default());
        let thread_running = running.clone();
        let thread_stats = stats.clone();
        let thread = std::thread::Builder::new()
            .name("blockads-tunnel".into())
            .spawn(move || {
                runtime.block_on(async move {
                    let result = async {
                        let tun = Arc::new(TunDevice::new(fd)?);
                        let (stack, runner, udp, tcp) = netstack_smoltcp::StackBuilder::default()
                            .enable_tcp(true)
                            .enable_udp(true)
                            .enable_icmp(false)
                            .mtu(MTU)
                            .stack_buffer_size(512)
                            .tcp_buffer_size(256)
                            .udp_buffer_size(512)
                            .tcp_recv_buffer_size(65536)
                            .tcp_send_buffer_size(65536)
                            .build()?;
                        let (mut sink, mut stream) = stack.split();
                        let dns = crate::dns::Session::new(protector.clone(), thread_stats.clone());
                        let tcp_dns = dns.clone();
                        let (raw_tx, mut raw_rx) = tokio::sync::mpsc::channel::<Vec<u8>>(512);
                        let resets = Arc::new(crate::tcp::reset::Resets::default());
                        let read_resets = resets.clone();
                        let mut tasks: JoinSet<io::Result<()>> = JoinSet::new();
                        let reader = tun.clone();
                        let read_stats = thread_stats.clone();
                        tasks.spawn(async move {
                            let mut buffer = vec![0u8; 65535];
                            loop {
                                let len = reader.read(&mut buffer).await?;
                                if len == 0 {
                                    return Err(io::ErrorKind::UnexpectedEof.into());
                                }
                                if buffer[0] >> 4 == 6 && !crate::policy::current().ipv6 { continue; }
                                read_stats.packets_in.fetch_add(1, Ordering::Relaxed);
                                read_resets.observe(&buffer[..len]);
                                // Reject malformed/fragmented input instead of terminating an upstream UDP stream.
                                if !matches!(etherparse::SlicedPacket::from_ip(&buffer[..len]).ok().and_then(|packet| packet.transport),
                                    Some(etherparse::TransportSlice::Tcp(_) | etherparse::TransportSlice::Udp(_))) { continue; }
                                sink.send(buffer[..len].to_vec()).await?;
                            }
                        });
                        let writer_stats = thread_stats.clone();
                        tasks.spawn(async move {
                            loop {
                                let packet = tokio::select! {
                                    packet = stream.next() => packet.ok_or(std::io::ErrorKind::BrokenPipe)??,
                                    packet = raw_rx.recv() => packet.ok_or(std::io::ErrorKind::BrokenPipe)?,
                                };
                                tun.write(&packet).await?;
                                writer_stats.packets_out.fetch_add(1, Ordering::Relaxed);
                            }

                        });
                        if let Some(runner) = runner {
                            tasks.spawn(runner);
                        }
                        let tcp_protector = protector.clone();
                        let tcp_stats = thread_stats.clone();
                        let tcp_network = network_rx.clone();
                        let tcp_raw = raw_tx.clone();
                        tasks.spawn(async move {
                            crate::tcp::serve(
                                tcp.expect("TCP enabled"),
                                tcp_protector,
                                tcp_stats,
                                tcp_network,
                                tcp_dns,
                                resets,
                                tcp_raw,
                            )
                            .await;
                            Ok(())
                        });
                        tasks.spawn(async move {
                            crate::udp::serve(
                                udp.expect("UDP enabled"),
                                raw_tx,
                                protector,
                                thread_stats,
                                network_rx,
                                dns,
                            )
                            .await;
                            Ok(())
                        });
                        tasks.spawn(async {
                            // Batch metadata every five seconds; full batches still flush early.
                            // The final flush below drains pending data on shutdown.
                            loop { tokio::time::sleep(std::time::Duration::from_secs(5)).await; crate::dns::flush(); }
                            #[allow(unreachable_code)] Ok(())
                        });
                        let _ = ready_tx.send(Ok(()));
                        tokio::select! { _ = stop_rx => {}, _ = tasks.join_next() => {} }
                        tasks.abort_all();
                        while tasks.join_next().await.is_some() {}
                        Ok::<(), io::Error>(())
                    }
                    .await;
                    if let Err(error) = result {
                        let _ = ready_tx.send(Err(error));
                    }
                });
                crate::dns::flush();
            thread_running.store(false, Ordering::Release);
            })?;
        let mut engine = Self {
            stop: Some(stop_tx),
            network: network_tx,
            thread: Some(thread),
            protector: engine_protector,
            running,
            stats,
        };
        match ready_rx.recv_timeout(std::time::Duration::from_secs(5)) {
            Ok(Ok(())) => Ok(engine),
            Ok(Err(error)) => {
                engine.shutdown();
                Err(error)
            }
            Err(_) => {
                engine.shutdown();
                Err(io::ErrorKind::TimedOut.into())
            }
        }
    }
    pub fn update_network(&self, mut config: NetworkConfig) -> bool {
        if config.probe_ipv6 {
            crate::sockets::probe_ipv6(self.protector.as_ref());
        }
        let applied = Arc::new(AtomicU8::new(0));
        config.applied = Some(applied.clone());
        if self.network.send(config).is_err() {
            return false;
        }
        // Network changes are rare. Do not publish readiness until both relay loops
        // have dropped their old sockets, otherwise a new flow can be aborted late.
        for _ in 0..2000 {
            if applied.load(Ordering::Acquire) >= 2 {
                self.stats.network_resets.fetch_add(1, Ordering::Relaxed);
                return true;
            }
            if !self.running.load(Ordering::Acquire) {
                return false;
            }
            std::thread::sleep(std::time::Duration::from_millis(1));
        }
        false
    }
    pub fn shutdown(&mut self) {
        if let Some(stop) = self.stop.take() {
            let _ = stop.send(());
        }
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}
impl Drop for Engine {
    fn drop(&mut self) {
        self.shutdown();
    }
}
