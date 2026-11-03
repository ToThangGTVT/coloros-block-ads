//! Observe client ACKs so policy rejections can send an in-window TCP RST without payload.
use std::{collections::HashMap, net::SocketAddr, sync::Mutex};
type ResetEntries = HashMap<(SocketAddr, SocketAddr), (u32, Option<u32>)>;
#[derive(Default)]
pub struct Resets(Mutex<ResetEntries>);
impl Resets {
    pub fn observe(&self, bytes: &[u8]) {
        let Ok(packet) = etherparse::SlicedPacket::from_ip(bytes) else {
            return;
        };
        let (Some(ip), Some(etherparse::TransportSlice::Tcp(tcp))) = (packet.net, packet.transport)
        else {
            return;
        };
        let (source, destination) = match ip {
            etherparse::InternetSlice::Ipv4(ip) => (
                ip.header().source_addr().into(),
                ip.header().destination_addr().into(),
            ),
            etherparse::InternetSlice::Ipv6(ip) => (
                ip.header().source_addr().into(),
                ip.header().destination_addr().into(),
            ),
        };
        let key = (
            SocketAddr::new(source, tcp.source_port()),
            SocketAddr::new(destination, tcp.destination_port()),
        );
        let mut entries = self.0.lock().unwrap_or_else(|e| e.into_inner());
        if tcp.syn() || tcp.rst() {
            entries.remove(&key);
        }
        if tcp.syn() && !tcp.ack() {
            if entries.len() >= 1024 {
                entries.clear();
            }
            entries.insert(key, (0, Some(tcp.sequence_number().wrapping_add(1))));
        }
        if tcp.ack() {
            if entries.len() >= 1024 && !entries.contains_key(&key) {
                entries.clear();
            }
            entries.insert(key, (tcp.acknowledgment_number(), None));
        }
    }
    pub fn packet(&self, client: SocketAddr, destination: SocketAddr) -> Option<Vec<u8>> {
        let (sequence, acknowledgment) = self
            .0
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&(client, destination))?;
        let mut builder = match (destination, client) {
            (SocketAddr::V4(a), SocketAddr::V4(b)) => {
                etherparse::PacketBuilder::ipv4(a.ip().octets(), b.ip().octets(), 64)
            }
            (SocketAddr::V6(a), SocketAddr::V6(b)) => {
                etherparse::PacketBuilder::ipv6(a.ip().octets(), b.ip().octets(), 64)
            }
            _ => return None,
        }
        .tcp(destination.port(), client.port(), sequence, 0)
        .rst();
        if let Some(acknowledgment) = acknowledgment {
            builder = builder.ack(acknowledgment);
        }
        let mut packet = Vec::with_capacity(builder.size(0));
        builder.write(&mut packet, &[]).ok()?;
        Some(packet)
    }
}
