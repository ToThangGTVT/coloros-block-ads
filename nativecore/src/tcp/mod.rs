//! Transparent TCP relay, preserving half-close and resetting upstreams on network changes.
use crate::{
    sockets::{self, Protector, Stats},
    tun::NetworkConfig,
};
use futures_util::StreamExt;
use netstack_smoltcp::TcpListener;
use std::{sync::Arc, time::Duration};
use tokio::{
    sync::{watch, Semaphore},
    task::JoinSet,
};

pub async fn serve(
    mut listener: TcpListener,
    protector: Arc<dyn Protector>,
    stats: Arc<Stats>,
    mut network: watch::Receiver<NetworkConfig>,
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
                if let Some(applied) = &network.borrow().applied { applied.fetch_add(1, std::sync::atomic::Ordering::Release); }
            }
            _ = flows.join_next(), if !flows.is_empty() => {}
            incoming = listener.next() => {
                let Some((client, _, destination)) = incoming else { break; };
                let Ok(permit) = permits.clone().try_acquire_owned() else { continue; };
                let Some(destination) = network.borrow().destination(destination) else { continue; };
                let protector = protector.clone();
                let stats = stats.clone();
                flows.spawn(async move {
                    let _permit = permit;
                    let mut client = CountedStream { inner: client, stats: stats.clone() };
                    if let Ok(mut upstream) = sockets::connect_tcp(destination, protector.as_ref(), &stats).await {
                        // The stack also has a two-hour dead-peer timeout. Bound abandoned relays.
                        let _ = tokio::time::timeout(Duration::from_secs(7200),
                            tokio::io::copy_bidirectional(&mut client, &mut upstream)).await;
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
