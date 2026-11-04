//! Opt-in TLS interception. Requires an installed local CA and explicitly selected apps.
pub mod ca;
mod html;
pub mod proxy;
use crate::policy::Owner;
use serde::Deserialize;
use std::{
    collections::HashMap,
    sync::{Arc, Mutex, OnceLock, RwLock},
    time::{Duration, Instant},
};

#[derive(Clone, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct Options {
    pub enabled: bool,
    pub apps: Vec<String>,
}
static OPTIONS: OnceLock<RwLock<Arc<Options>>> = OnceLock::new();
static REVISION: OnceLock<tokio::sync::watch::Sender<u64>> = OnceLock::new();
type Bypasses = HashMap<(u32, String), Instant>;
static BYPASS: OnceLock<Mutex<Bypasses>> = OnceLock::new();
pub fn current() -> Arc<Options> {
    OPTIONS
        .get_or_init(|| RwLock::new(Arc::new(Options::default())))
        .read()
        .unwrap_or_else(|e| e.into_inner())
        .clone()
}
pub fn subscribe() -> tokio::sync::watch::Receiver<u64> {
    REVISION
        .get_or_init(|| tokio::sync::watch::channel(0).0)
        .subscribe()
}
pub fn changed() {
    REVISION
        .get_or_init(|| tokio::sync::watch::channel(0).0)
        .send_modify(|v| *v = v.wrapping_add(1));
}
pub fn valid_domain(name: &str) -> bool {
    name.contains('.')
        && name.len() <= 253
        && name.parse::<std::net::IpAddr>().is_err()
        && name.split('.').all(|part| {
            !part.is_empty()
                && part.len() <= 63
                && !part.starts_with('-')
                && !part.ends_with('-')
                && part
                    .bytes()
                    .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == b'-')
        })
}
pub fn configure(json: &str) -> bool {
    let Ok(options) = serde_json::from_str::<Options>(json) else {
        return false;
    };
    if options.apps.len() > 100 {
        return false;
    }
    *OPTIONS
        .get_or_init(|| RwLock::new(Arc::new(Options::default())))
        .write()
        .unwrap_or_else(|e| e.into_inner()) = Arc::new(options);
    changed();
    true
}
fn under(domain: &str, parent: &str) -> bool {
    domain == parent
        || domain
            .strip_suffix(parent)
            .is_some_and(|prefix| prefix.ends_with('.'))
}
/// Always excluded, even if explicitly allowed. Never infer sensitive domains from URL payloads.
pub fn sensitive(domain: &str) -> bool {
    const ROOTS: &[&str] = &[
        "paypal.com",
        "stripe.com",
        "visa.com",
        "mastercard.com",
        "momo.vn",
        "zalopay.vn",
        "vnpay.vn",
        "vietcombank.com.vn",
        "bidv.com.vn",
        "vietinbank.vn",
        "techcombank.com",
        "mbbank.com.vn",
        "acb.com.vn",
        "vpbank.com.vn",
        "tpb.vn",
        "sacombank.com.vn",
        "nhs.uk",
        "mychart.com",
        "mayoclinic.org",
        "medlineplus.gov",
    ];
    ROOTS.iter().any(|root| under(domain, root))
        || domain.ends_with(".bank")
        || domain.split('.').any(|label| {
            [
                "bank", "pay", "health", "medical", "hospital", "clinic", "pharma",
            ]
            .iter()
            .any(|word| label.contains(word))
        })
}
pub fn eligible(domain: &str, owner: &Owner) -> bool {
    let options = current();
    if !options.enabled
        || owner.bypass
        || owner.uid.is_none()
        || sensitive(domain)
        || !valid_domain(domain)
        || !options.apps.iter().any(|app| owner.packages.contains(app))
    {
        return false;
    }
    !BYPASS
        .get_or_init(Mutex::default)
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .get(&(owner.uid.unwrap(), domain.to_owned()))
        .is_some_and(|at| at.elapsed() < Duration::from_secs(6 * 3600))
        && ca::authority().is_some()
}
pub fn reject_client(domain: &str, owner: &Owner) {
    bypass(domain, owner, "client từ chối TLS, có thể pinning");
}
pub fn bypass(domain: &str, owner: &Owner, reason: &str) {
    if let Some(uid) = owner.uid {
        let mut bypass = BYPASS
            .get_or_init(Mutex::default)
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        if bypass.len() >= 512 {
            bypass.retain(|_, at| at.elapsed() < Duration::from_secs(6 * 3600));
        }
        if bypass.len() >= 512 {
            bypass.clear();
        }
        bypass.insert((uid, domain.to_owned()), Instant::now());
    }
    crate::dns::event(
        domain.into(),
        false,
        Some(format!("Bỏ qua HTTPS ở lần kết nối kế tiếp · {reason}")),
        false,
        owner.uid,
        "tls-bypass",
    );
}
