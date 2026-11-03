//! Bounded connected UDP NAT. Each flow expires after 120 seconds without traffic.
use crate::{
    sockets::{self, Protector, Stats},
    tun::NetworkConfig,
};
use futures_util::StreamExt;
use netstack_smoltcp::UdpSocket;
use std::{collections::HashMap, net::SocketAddr, sync::Arc, time::Duration};
use tokio::{
    sync::{mpsc, watch},
    task::JoinSet,
};

type Key = (SocketAddr, SocketAddr);
pub async fn serve(
    udp: UdpSocket,
    raw_replies: mpsc::Sender<Vec<u8>>,
    protector: Arc<dyn Protector>,
    stats: Arc<Stats>,
    mut network: watch::Receiver<NetworkConfig>,
    dns: crate::dns::Session,
) {
    let (mut incoming, _unused_writer) = udp.split();
    let (reply_tx, mut reply_rx) = mpsc::channel::<(Vec<u8>, SocketAddr, SocketAddr)>(512);
    let mut entries: HashMap<Key, (u64, mpsc::Sender<Vec<u8>>)> = HashMap::new();
    let mut flows = JoinSet::new();
    let mut serial = 0u64;
    let mut dns_jobs = JoinSet::new();
    let dns_limit = Arc::new(tokio::sync::Semaphore::new(128));
    loop {
        tokio::select! {
            biased;
            changed = network.changed() => {
                if changed.is_err() { break; }
                dns_jobs.abort_all(); while dns_jobs.join_next().await.is_some() {}
                dns.reset().await;
                entries.clear();
                flows.abort_all();
                while flows.join_next().await.is_some() {}
                while reply_rx.try_recv().is_ok() {}
                if let Some(applied) = &network.borrow().applied { applied.fetch_add(1, std::sync::atomic::Ordering::Release); }
            }
            _ = dns_jobs.join_next(), if !dns_jobs.is_empty() => {}
            ended = flows.join_next(), if !flows.is_empty() => {
                if let Some(Ok((key, generation))) = ended {
                    if entries.get(&key).is_some_and(|(id, _)| *id == generation) { entries.remove(&key); }
                }
            }
            reply = reply_rx.recv() => {
                if let Some((data, source, destination)) = reply {
                    // Upstream WriteHalf drops empty UDP payloads. Keep all datagrams intact.
                    if let Ok(packet) = encode_reply(&data, source, destination) {
                        if raw_replies.send(packet).await.is_err() { break; }
                    }
                }
            }
            packet = incoming.next() => {
                let Some((mut data, client, original)) = packet else { break; };
                if original.port() == 53 {
                    let Ok(permit) = dns_limit.clone().try_acquire_owned() else { continue; };
                    let session = dns.clone(); let replies = reply_tx.clone(); let physical = network.borrow().dns;
                    dns_jobs.spawn(async move {
                        let _permit = permit;
                        if let Ok(response) = session.resolve(&data, physical, false).await { let _ = replies.send((response, original, client)).await; }
                    });
                    continue;
                }
                let key = (client, original);
                if let Some((_, sender)) = entries.get(&key) {
                    match sender.try_send(data) {
                        Ok(()) => continue,
                        Err(mpsc::error::TrySendError::Full(_)) => continue,
                        Err(mpsc::error::TrySendError::Closed(returned)) => { data = returned; entries.remove(&key); }
                    }
                }
                if entries.len() >= 1024 { continue; }
                let Some(destination) = network.borrow().destination(original) else { continue; };
                serial = serial.wrapping_add(1);
                let generation = serial;
                let (sender, mut receiver) = mpsc::channel::<Vec<u8>>(32);
                let _ = sender.try_send(data);
                entries.insert(key, (generation, sender));
                let protector = protector.clone();
                let stats = stats.clone();
                let replies = reply_tx.clone();
                flows.spawn(async move {
                    if let Ok(socket) = sockets::connect_udp(destination, protector.as_ref(), &stats).await {
                        let mut buffer = vec![0u8; 65535];
                        loop {
                            let activity = async {
                                tokio::select! {
                                    data = receiver.recv() => match data {
                                        Some(data) => socket.send(&data).await.map(|_| ()),
                                        None => Err(std::io::ErrorKind::BrokenPipe.into()),
                                    },
                                    received = socket.recv(&mut buffer) => {
                                        let len = received?;
                                        replies.send((buffer[..len].to_vec(), original, client)).await
                                            .map_err(|_| std::io::ErrorKind::BrokenPipe.into())
                                    }
                                }
                            };
                            if !matches!(tokio::time::timeout(Duration::from_secs(120), activity).await, Ok(Ok(()))) { break; }
                        }
                    }
                    (key, generation)
                });
            }
        }
    }
    dns_jobs.abort_all();
    while dns_jobs.join_next().await.is_some() {}
    flows.abort_all();
    while flows.join_next().await.is_some() {}
}

fn encode_reply(
    data: &[u8],
    source: SocketAddr,
    destination: SocketAddr,
) -> std::io::Result<Vec<u8>> {
    let builder = match (source, destination) {
        (SocketAddr::V4(a), SocketAddr::V4(b)) => {
            etherparse::PacketBuilder::ipv4(a.ip().octets(), b.ip().octets(), 64)
        }
        (SocketAddr::V6(a), SocketAddr::V6(b)) => {
            etherparse::PacketBuilder::ipv6(a.ip().octets(), b.ip().octets(), 64)
        }
        _ => return Err(std::io::ErrorKind::InvalidInput.into()),
    }
    .udp(source.port(), destination.port());
    let mut packet = Vec::with_capacity(builder.size(data.len()));
    builder
        .write(&mut packet, data)
        .map_err(std::io::Error::other)?;
    Ok(packet)
}
