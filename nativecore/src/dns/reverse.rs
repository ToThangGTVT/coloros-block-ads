//! Conservative, per-UID IP attribution. Shared IPs become ambiguous rather than guessed.
use hickory_resolver::proto::{op::Message, rr::RData};
use std::{
    collections::HashMap,
    net::IpAddr,
    sync::{Mutex, OnceLock},
    time::{Duration, Instant},
};
struct Entry {
    domain: String,
    expires: Instant,
    ambiguous: bool,
}
type Entries = HashMap<(Option<u32>, IpAddr), Entry>;
static ENTRIES: OnceLock<Mutex<Entries>> = OnceLock::new();
pub fn clear() {
    ENTRIES
        .get_or_init(Mutex::default)
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .clear();
}
pub fn remember(uid: Option<u32>, domain: &str, response: &Message) {
    let mut entries = ENTRIES
        .get_or_init(Mutex::default)
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    let now = Instant::now();
    if entries.len() >= 4096 {
        entries.retain(|_, e| e.expires > now);
    }
    for record in &response.answers {
        let ip = match &record.data {
            RData::A(ip) => IpAddr::V4(ip.0),
            RData::AAAA(ip) => IpAddr::V6(ip.0),
            _ => continue,
        };
        if record.ttl == 0 || ip.is_unspecified() {
            continue;
        }
        let expires = now + Duration::from_secs(record.ttl as u64);
        let key = (uid, ip);
        if let Some(entry) = entries.get_mut(&key).filter(|e| e.expires > now) {
            entry.ambiguous |= entry.domain != domain;
            entry.expires = entry.expires.max(expires);
        } else if entries.len() < 4096 {
            entries.insert(
                key,
                Entry {
                    domain: domain.to_owned(),
                    expires,
                    ambiguous: false,
                },
            );
        }
    }
}
pub fn lookup(uid: Option<u32>, ip: IpAddr) -> Option<String> {
    ENTRIES
        .get_or_init(Mutex::default)
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .get(&(uid, ip))
        .filter(|e| !e.ambiguous && e.expires > Instant::now())
        .map(|e| e.domain.clone())
}
