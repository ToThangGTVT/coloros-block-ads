//! DNS policy, cache, protected upstream transports and batched metadata events.
pub mod cache;
pub mod reverse;
pub mod upstream;
use crate::{
    filter,
    sockets::{Protector, Stats},
};
use futures_util::StreamExt;
use hickory_resolver::{
    net::{xfer::DnsHandle, DnsError, NetError},
    proto::{
        op::{DnsRequest, DnsRequestOptions, Message, MessageType, OpCode, ResponseCode},
        rr::{
            rdata::{
                svcb::{SvcParamKey, SvcParamValue},
                A, AAAA,
            },
            RData, Record, RecordType,
        },
    },
    NameServerPool,
};
use serde::{Deserialize, Serialize};
use std::{
    io,
    net::IpAddr,
    sync::{
        atomic::{AtomicU64, Ordering},
        Arc, Mutex, OnceLock, RwLock,
    },
    time::{Duration, SystemTime, UNIX_EPOCH},
};

#[derive(Clone, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct Options {
    pub protocol: String,
    pub ip: String,
    pub server_name: String,
    pub path: String,
    pub port: u16,
    pub block_mode: String,
    pub logging: bool,
}
impl Default for Options {
    fn default() -> Self {
        Self {
            protocol: "system".into(),
            ip: String::new(),
            server_name: String::new(),
            path: "/dns-query".into(),
            port: 0,
            block_mode: "zero".into(),
            logging: true,
        }
    }
}
static CONFIG: OnceLock<RwLock<Options>> = OnceLock::new();
static CACHE: OnceLock<Mutex<cache::Cache>> = OnceLock::new();
static REVISION: AtomicU64 = AtomicU64::new(0);
fn cache() -> &'static Mutex<cache::Cache> {
    CACHE.get_or_init(|| Mutex::new(cache::Cache::default()))
}
pub fn configure(json: &str) -> bool {
    let Ok(config) = serde_json::from_str::<Options>(json) else {
        return false;
    };
    if !matches!(
        config.protocol.as_str(),
        "system" | "udp" | "tcp" | "tls" | "https" | "quic"
    ) || !matches!(config.block_mode.as_str(), "zero" | "nxdomain")
    {
        return false;
    }
    if config.protocol != "system" && config.ip.parse::<IpAddr>().is_err() {
        return false;
    }
    if matches!(config.protocol.as_str(), "tls" | "https" | "quic")
        && (config.server_name.is_empty() || !config.path.starts_with('/'))
    {
        return false;
    }
    if !config.logging {
        EVENTS
            .get_or_init(Mutex::default)
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clear();
    }
    *CONFIG
        .get_or_init(|| RwLock::new(Options::default()))
        .write()
        .unwrap_or_else(|e| e.into_inner()) = config;
    REVISION.fetch_add(1, Ordering::Release);
    cache().lock().unwrap_or_else(|e| e.into_inner()).clear();
    true
}
pub fn options() -> Options {
    CONFIG
        .get_or_init(|| RwLock::new(Options::default()))
        .read()
        .unwrap_or_else(|e| e.into_inner())
        .clone()
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Event {
    pub timestamp: u64,
    pub domain: String,
    pub blocked: bool,
    pub matched_rule: Option<String>,
    pub cached: bool,
    pub uid: Option<u32>,
    pub protocol: String,
}
pub trait EventSink: Send + Sync {
    fn batch(&self, json: &str);
}
static SINK: OnceLock<RwLock<Option<Arc<dyn EventSink>>>> = OnceLock::new();
static EVENTS: OnceLock<Mutex<Vec<Event>>> = OnceLock::new();
type Totals = std::collections::HashMap<u64, (u64, u64)>;
static TOTALS: OnceLock<Mutex<Totals>> = OnceLock::new();
pub fn set_sink(sink: Option<Arc<dyn EventSink>>) {
    *SINK
        .get_or_init(|| RwLock::new(None))
        .write()
        .unwrap_or_else(|e| e.into_inner()) = sink;
}
pub fn flush() {
    let events = {
        let mut queue = EVENTS
            .get_or_init(Mutex::default)
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        std::mem::take(&mut *queue)
    };
    let totals = std::mem::take(
        &mut *TOTALS
            .get_or_init(Mutex::default)
            .lock()
            .unwrap_or_else(|e| e.into_inner()),
    );
    if events.is_empty() && totals.is_empty() {
        return;
    }
    let totals: Vec<_> = totals.into_iter().map(|(timestamp, (total, blocked))| serde_json::json!({"timestamp":timestamp,"total":total,"blocked":blocked})).collect();
    let sink = SINK
        .get_or_init(|| RwLock::new(None))
        .read()
        .unwrap_or_else(|e| e.into_inner())
        .clone();
    if let (Some(sink), Ok(json)) = (
        sink,
        serde_json::to_string(&serde_json::json!({"events":events,"totals":totals})),
    ) {
        sink.batch(&json);
    }
}
pub fn event(
    domain: String,
    blocked: bool,
    rule: Option<String>,
    cached: bool,
    uid: Option<u32>,
    protocol: &str,
) {
    let timestamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64;
    {
        let mut totals = TOTALS
            .get_or_init(Mutex::default)
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        let entry = totals.entry(timestamp / 60000 * 60000).or_default();
        entry.0 += 1;
        entry.1 += u64::from(blocked);
    }
    if !options().logging {
        return;
    }
    let full = {
        let mut events = EVENTS
            .get_or_init(Mutex::default)
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        if events.len() >= 4096 {
            events.remove(0);
        }
        events.push(Event {
            timestamp,
            domain,
            blocked,
            matched_rule: rule,
            cached,
            uid,
            protocol: protocol.into(),
        });
        events.len() >= 100
    };
    if full {
        flush();
    }
}
type Pool = NameServerPool<upstream::ProtectedRuntime>;
type SelectedPool = Arc<tokio::sync::Mutex<Option<(u64, Option<IpAddr>, Pool)>>>;
#[derive(Clone)]
pub struct Session {
    runtime: upstream::ProtectedRuntime,
    pool: SelectedPool,
}
impl Session {
    pub fn new(protector: Arc<dyn Protector>, stats: Arc<Stats>) -> Self {
        reverse::clear();
        cache().lock().unwrap_or_else(|e| e.into_inner()).clear();
        Self {
            runtime: upstream::ProtectedRuntime::new(protector, stats),
            pool: Arc::new(tokio::sync::Mutex::new(None)),
        }
    }
    pub async fn reset(&self) {
        reverse::clear();
        self.pool.lock().await.take();
        cache().lock().unwrap_or_else(|e| e.into_inner()).clear();
    }
    pub async fn resolve(
        &self,
        data: &[u8],
        physical: Option<IpAddr>,
        tcp: bool,
    ) -> io::Result<Vec<u8>> {
        self.resolve_for(data, physical, tcp, &crate::policy::Owner::default())
            .await
    }
    pub async fn resolve_for(
        &self,
        data: &[u8],
        physical: Option<IpAddr>,
        tcp: bool,
        owner: &crate::policy::Owner,
    ) -> io::Result<Vec<u8>> {
        let request = Message::from_vec(data).map_err(io::Error::other)?;
        if request.metadata.message_type != MessageType::Query
            || request.metadata.op_code != OpCode::Query
            || request.queries.len() != 1
        {
            return error_response(&request, ResponseCode::FormErr)
                .to_vec()
                .map_err(io::Error::other);
        }
        let domain = request.queries[0]
            .name()
            .to_ascii()
            .trim_end_matches('.')
            .to_ascii_lowercase();
        let rules = filter::current();
        let decision = if owner.bypass {
            None
        } else {
            rules.matches(&domain, None, &owner.packages)
        };
        let rule = decision.as_ref().map(|d| d.rule.clone());
        if decision.as_ref().is_some_and(|d| d.blocked) {
            event(domain, true, rule, false, owner.uid, "dns");
            return encode(
                &blocked_response(&request, &options().block_mode),
                &request,
                tcp,
            );
        }
        if request.queries[0].query_type() == RecordType::AAAA && !crate::sockets::ipv6_available()
        {
            // No IPv6 on the physical network: an empty answer makes apps use IPv4 at once,
            // instead of connecting over the VPN's IPv6 route to an address nobody can reach.
            event(domain, false, rule, false, owner.uid, "dns");
            return encode(
                &error_response(&request, ResponseCode::NoError),
                &request,
                tcp,
            );
        }
        let revision = REVISION.load(Ordering::Acquire);
        let config = options();
        let mut key = data.to_vec();
        key[0] = 0;
        key[1] = 0;
        key.extend_from_slice(&revision.to_be_bytes());
        key.extend_from_slice(&filter::revision().to_be_bytes());
        if let Some(mut response) = cache().lock().unwrap_or_else(|e| e.into_inner()).get(&key) {
            response.metadata.id = request.metadata.id;
            adjust_service_records(&mut response, &domain, owner);
            reverse::remember(owner.uid, &domain, &response);
            event(domain, false, rule, true, owner.uid, "dns");
            return encode(&response, &request, tcp);
        }
        let pool = {
            let mut selected = self.pool.lock().await;
            if selected
                .as_ref()
                .is_none_or(|(v, ip, _)| *v != revision || *ip != physical)
            {
                match upstream::pool(&config, physical, self.runtime.clone()) {
                    Ok(pool) => *selected = Some((revision, physical, pool)),
                    Err(_) => {
                        event(domain, false, rule, false, owner.uid, "dns");
                        return encode(
                            &error_response(&request, ResponseCode::ServFail),
                            &request,
                            tcp,
                        );
                    }
                }
            }
            selected.as_ref().expect("pool initialized").2.clone()
        };
        let mut outgoing = request.clone();
        outgoing.metadata.id = 0;
        let mut request_options = DnsRequestOptions::default();
        request_options.use_edns = request.edns.is_some();
        request_options.recursion_desired = request.metadata.recursion_desired;
        let response = tokio::time::timeout(
            Duration::from_secs(8),
            pool.send(DnsRequest::new(outgoing, request_options)).next(),
        )
        .await;
        let mut response = match response {
            Ok(Some(Ok(response))) => response.into_message(),
            Ok(Some(Err(NetError::Dns(DnsError::NoRecordsFound(no))))) => {
                let mut response = error_response(&request, no.response_code);
                response.authorities = no
                    .authorities
                    .as_ref()
                    .map(|r| r.to_vec())
                    .unwrap_or_default();
                if response.authorities.is_empty() {
                    if let Some(soa) = no.soa {
                        response.authorities.push((*soa).into_record_of_rdata());
                    }
                }
                response
            }
            Ok(Some(Err(NetError::Dns(DnsError::ResponseCode(code))))) => {
                error_response(&request, code)
            }
            _ => error_response(&request, ResponseCode::ServFail),
        };
        response.metadata.id = request.metadata.id;
        cache()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .put(key, response.clone());
        reverse::remember(owner.uid, &domain, &response);
        adjust_service_records(&mut response, &domain, owner);
        event(domain, false, rule, false, owner.uid, "dns");
        encode(&response, &request, tcp)
    }
}
/// Edits HTTPS/SVCB answers on the way out; the cache keeps the original.
/// - Apps whose HTTPS is filtered lose `ech`, so the browser sends the real SNI the proxy needs
///   instead of an outer name such as `cloudflare-ech.com`. Other apps, sensitive hosts and pinned
///   (bypassed) hosts keep ECH.
/// - Without IPv6 on the physical network, `ipv6hint` goes too, like AAAA answers.
fn adjust_service_records(response: &mut Message, domain: &str, owner: &crate::policy::Owner) {
    if !response
        .answers
        .iter()
        .chain(&response.additionals)
        .any(|r| matches!(r.data, RData::HTTPS(_) | RData::SVCB(_)))
    {
        return;
    }
    let mut keys = Vec::new();
    if crate::mitm::eligible(domain, owner) {
        keys.push(SvcParamKey::EchConfigList);
    }
    if !crate::sockets::ipv6_available() {
        keys.push(SvcParamKey::Ipv6Hint);
    }
    strip_params(response, &keys);
}
pub fn strip_ech(response: &mut Message) {
    strip_params(response, &[SvcParamKey::EchConfigList]);
}
fn strip_params(response: &mut Message, keys: &[SvcParamKey]) {
    if keys.is_empty() {
        return;
    }
    for record in response
        .answers
        .iter_mut()
        .chain(response.additionals.iter_mut())
    {
        let params = match &mut record.data {
            RData::HTTPS(https) => &mut https.0.svc_params,
            RData::SVCB(svcb) => &mut svcb.svc_params,
            _ => continue,
        };
        params.retain(|(key, _)| !keys.contains(key));
        // A record that still listed a removed key as mandatory would have to be ignored.
        for (_, value) in params.iter_mut() {
            if let SvcParamValue::Mandatory(mandatory) = value {
                mandatory.0.retain(|key| !keys.contains(key));
            }
        }
        params.retain(|(_, value)| !matches!(value, SvcParamValue::Mandatory(m) if m.0.is_empty()));
    }
}
pub fn error_response(request: &Message, code: ResponseCode) -> Message {
    let mut response = Message::error_msg(request.metadata.id, request.metadata.op_code, code);
    response.queries = request.queries.clone();
    response.edns = request.edns.clone();
    response.metadata.recursion_desired = request.metadata.recursion_desired;
    response.metadata.recursion_available = true;
    response
}
pub fn blocked_response(request: &Message, mode: &str) -> Message {
    let mut response = error_response(
        request,
        if mode == "nxdomain" {
            ResponseCode::NXDomain
        } else {
            ResponseCode::NoError
        },
    );
    if mode != "nxdomain" {
        if let Some(query) = request.queries.first() {
            let data = match query.query_type() {
                RecordType::A => Some(RData::A(A::new(0, 0, 0, 0))),
                RecordType::AAAA => Some(RData::AAAA(AAAA::new(0, 0, 0, 0, 0, 0, 0, 0))),
                _ => None,
            };
            if let Some(data) = data {
                response
                    .answers
                    .push(Record::from_rdata(query.name().clone(), 0, data));
            }
        }
    }
    response
}
fn encode(response: &Message, request: &Message, tcp: bool) -> io::Result<Vec<u8>> {
    let bytes = response.to_vec().map_err(io::Error::other)?;
    let limit = request
        .edns
        .as_ref()
        .map_or(512, |e| e.max_payload() as usize)
        .clamp(512, 1232);
    if !tcp && bytes.len() > limit {
        response.truncate().to_vec().map_err(io::Error::other)
    } else {
        Ok(bytes)
    }
}
pub async fn serve_tcp<S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin>(
    mut client: S,
    session: Session,
    physical: Option<IpAddr>,
    owner: crate::policy::Owner,
) {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    loop {
        let Ok(Ok(length)) =
            tokio::time::timeout(Duration::from_secs(120), client.read_u16()).await
        else {
            break;
        };
        if length < 12 {
            break;
        }
        let mut data = vec![0u8; length as usize];
        if !matches!(
            tokio::time::timeout(Duration::from_secs(5), client.read_exact(&mut data)).await,
            Ok(Ok(_))
        ) {
            break;
        }
        let owner = crate::policy::Owner::from_uid(owner.uid);
        let Ok(response) = session.resolve_for(&data, physical, true, &owner).await else {
            break;
        };
        if client.write_u16(response.len() as u16).await.is_err()
            || client.write_all(&response).await.is_err()
        {
            break;
        }
    }
}
