//! Transparent TCP relay, preserving half-close and resetting upstreams on network changes.
pub mod reset;
pub mod sniff;
use crate::{
    sockets::{self, Protector, Stats},
    tun::NetworkConfig,
};
use futures_util::StreamExt;
use netstack_smoltcp::TcpListener;
use std::{sync::Arc, time::Duration};
use tokio::{
    io::AsyncWriteExt,
    sync::{watch, Semaphore},
    task::JoinSet,
};

pub async fn serve(
    mut listener: TcpListener,
    protector: Arc<dyn Protector>,
    stats: Arc<Stats>,
    mut network: watch::Receiver<NetworkConfig>,
    dns: crate::dns::Session,
    resets: Arc<reset::Resets>,
    raw: tokio::sync::mpsc::Sender<Vec<u8>>,
) {
    let permits = Arc::new(Semaphore::new(256));
    let mut flows = JoinSet::new();
    loop {
        tokio::select! {
            biased;
            changed = network.changed() => {
                if changed.is_err() { break; }
                flows.abort_all();
                while flows.join_next().await.is_some() {}
                dns.reset().await;
                if let Some(applied) = &network.borrow().applied { applied.fetch_add(1, std::sync::atomic::Ordering::Release); }
            }
            _ = flows.join_next(), if !flows.is_empty() => {}
            incoming = listener.next() => {
                let Some((client, source, destination)) = incoming else { break; };
                let Ok(permit) = permits.clone().try_acquire_owned() else { continue; };
                let owner = crate::policy::Owner::from_uid(protector.owner(6, source, destination));
                if destination.port() == 53 {
                    let dns = dns.clone(); let physical = network.borrow().dns;
                    flows.spawn(async move { let _permit = permit; crate::dns::serve_tcp(client, dns, physical, owner).await; });
                    continue;
                }
                let Some(destination) = network.borrow().destination(destination) else { continue; };
                let protector = protector.clone();
                let stats = stats.clone();
                let resets = resets.clone(); let raw = raw.clone();
                flows.spawn(async move {
                    let _permit = permit;
                    let activity = Arc::new(std::sync::Mutex::new(std::time::Instant::now()));
                    let mut client = CountedStream { inner: client, stats: stats.clone(), activity: activity.clone() };
                    let (initial, parsed) = if owner.bypass { (vec![], sniff::Parsed::Unknown) } else { sniff::read_initial(&mut client).await };
                    let (domain, url, protocol) = match &parsed {
                        sniff::Parsed::Tls(host) => (host.clone(), None, "tls"),
                        sniff::Parsed::Http { host, url } => (host.clone(), Some(url.as_str()), "http"),
                        _ => (crate::dns::reverse::lookup(owner.uid, destination.ip()).unwrap_or_else(|| destination.ip().to_string()), None, "tcp"),
                    };
                    let decision = if owner.bypass { None } else { crate::filter::current().matches(&domain, url, &owner.packages) };
                    let blocked = decision.as_ref().is_some_and(|d| d.blocked);
                    crate::dns::event(domain, blocked, decision.map(|d| d.rule), false, owner.uid, protocol);
                    if blocked {
                        if matches!(parsed, sniff::Parsed::Http { .. }) { let _ = client.write_all(b"HTTP/1.1 204 No Content\r\nConnection: close\r\nContent-Length: 0\r\n\r\n").await; let _ = client.shutdown().await; }
                        else if let Some(packet) = resets.packet(source, destination) { let _ = raw.send(packet).await; }
                        return;
                    }
                    if let Ok(mut upstream) = sockets::connect_tcp(destination, protector.as_ref(), &stats).await {
                        if upstream.write_all(&initial).await.is_err() { return; }
                        let idle = async {
                            loop {
                                tokio::time::sleep(Duration::from_secs(60)).await;
                                if activity.lock().unwrap_or_else(|e| e.into_inner()).elapsed() >= Duration::from_secs(7200) { break; }
                            }
                        };
                        tokio::select! { _ = tokio::io::copy_bidirectional(&mut client, &mut upstream) => {}, _ = idle => {} }
                    }
                });
            }
        }
    }
    flows.abort_all();
    while flows.join_next().await.is_some() {}
}

struct CountedStream<S> {
    inner: S,
    stats: Arc<Stats>,
    activity: Arc<std::sync::Mutex<std::time::Instant>>,
}
impl<S: tokio::io::AsyncRead + Unpin> tokio::io::AsyncRead for CountedStream<S> {
    fn poll_read(
        self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
        buffer: &mut tokio::io::ReadBuf<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        let this = self.get_mut();
        let before = buffer.filled().len();
        let result = std::pin::Pin::new(&mut this.inner).poll_read(cx, buffer);
        if buffer.filled().len() > before {
            *this.activity.lock().unwrap_or_else(|e| e.into_inner()) = std::time::Instant::now();
        }
        this.stats.tcp_up.fetch_add(
            (buffer.filled().len() - before) as u64,
            std::sync::atomic::Ordering::Relaxed,
        );
        result
    }
}
impl<S: tokio::io::AsyncWrite + Unpin> tokio::io::AsyncWrite for CountedStream<S> {
    fn poll_write(
        self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
        buffer: &[u8],
    ) -> std::task::Poll<std::io::Result<usize>> {
        let this = self.get_mut();
        let result = std::pin::Pin::new(&mut this.inner).poll_write(cx, buffer);
        if let std::task::Poll::Ready(Ok(len)) = &result {
            if *len > 0 {
                *this.activity.lock().unwrap_or_else(|e| e.into_inner()) =
                    std::time::Instant::now();
            }
            this.stats
                .tcp_down
                .fetch_add(*len as u64, std::sync::atomic::Ordering::Relaxed);
        }
        result
    }
    fn poll_flush(
        self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        std::pin::Pin::new(&mut self.get_mut().inner).poll_flush(cx)
    }
    fn poll_shutdown(
        self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        std::pin::Pin::new(&mut self.get_mut().inner).poll_shutdown(cx)
    }
}
