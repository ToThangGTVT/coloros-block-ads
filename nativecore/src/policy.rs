//! Per-flow attribution and hot-reloaded filtering policy. Unknown owners stay filtered.
use crate::sockets::Protector;
use serde::Deserialize;
use std::{
    collections::HashMap,
    net::SocketAddr,
    sync::{Arc, OnceLock, RwLock},
    time::{Duration, Instant},
};

#[derive(Clone, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct Options {
    pub block_quic: bool,
    pub ipv6: bool,
    pub bypass_uids: Vec<u32>,
    pub packages: HashMap<u32, Vec<String>>,
}
impl Default for Options {
    fn default() -> Self {
        Self {
            block_quic: true,
            ipv6: true,
            bypass_uids: vec![],
            packages: HashMap::new(),
        }
    }
}
static ACTIVE: OnceLock<RwLock<Arc<Options>>> = OnceLock::new();
pub fn current() -> Arc<Options> {
    ACTIVE
        .get_or_init(|| RwLock::new(Arc::new(Options::default())))
        .read()
        .unwrap_or_else(|e| e.into_inner())
        .clone()
}
pub fn configure(json: &str) -> bool {
    let Ok(options) = serde_json::from_str::<Options>(json) else {
        return false;
    };
    *ACTIVE
        .get_or_init(|| RwLock::new(Arc::new(Options::default())))
        .write()
        .unwrap_or_else(|e| e.into_inner()) = Arc::new(options);
    true
}
#[derive(Default, Clone)]
pub struct Owner {
    pub uid: Option<u32>,
    pub packages: Vec<String>,
    pub bypass: bool,
}
impl Owner {
    pub fn from_uid(uid: Option<u32>) -> Self {
        let policy = current();
        Self {
            uid,
            packages: uid
                .and_then(|uid| policy.packages.get(&uid).cloned())
                .unwrap_or_default(),
            bypass: uid.is_some_and(|uid| policy.bypass_uids.contains(&uid)),
        }
    }
}
type Flow = (u8, SocketAddr, SocketAddr);
#[derive(Default)]
pub struct OwnerCache {
    entries: HashMap<Flow, (Instant, Option<u32>)>,
}
impl OwnerCache {
    pub fn owner(
        &mut self,
        protector: &dyn Protector,
        protocol: u8,
        source: SocketAddr,
        destination: SocketAddr,
    ) -> Owner {
        let key = (protocol, source, destination);
        if let Some((at, uid)) = self.entries.get(&key) {
            if at.elapsed() < Duration::from_secs(5) {
                return Owner::from_uid(*uid);
            }
        }
        if self.entries.len() >= 2048 {
            self.entries
                .retain(|_, (at, _)| at.elapsed() < Duration::from_secs(5));
        }
        if self.entries.len() >= 2048 {
            self.entries.clear();
        }
        let uid = protector.owner(protocol, source, destination);
        self.entries.insert(key, (Instant::now(), uid));
        Owner::from_uid(uid)
    }
}
