//! Scriptlet and `$redirect` resources, shared by every engine of every rule snapshot. The app
//! downloads AdGuard Scriptlets (GPL-3.0) at runtime, the same way it downloads filter lists;
//! nothing is bundled. Updating them takes effect without recompiling the rules.
use adblock::resources::{
    InMemoryResourceStorage, MimeType, PermissionMask, Resource, ResourceImpl,
    ResourceStorageBackend, ResourceType,
};
use base64::{engine::general_purpose::STANDARD as BASE64, Engine as _};
use serde::{Deserialize, Serialize};
use std::sync::{
    atomic::{AtomicU64, Ordering},
    Arc, OnceLock, RwLock,
};

const MAX_JSON: usize = 4 * 1024 * 1024;
const MAX_RESOURCES: usize = 1000;
/// Required by `trusted-*` scriptlets (cookies, storage, arbitrary text). Lists are parsed with
/// no permissions, so they cannot invoke them.
const TRUSTED: PermissionMask = PermissionMask::from_bits(1);

static STORE: OnceLock<RwLock<Arc<InMemoryResourceStorage>>> = OnceLock::new();
static GENERATION: AtomicU64 = AtomicU64::new(0);

#[derive(Deserialize)]
struct Corelibs {
    scriptlets: Vec<Scriptlet>,
}
#[derive(Deserialize)]
struct Scriptlet {
    names: Vec<String>,
    scriptlet: String,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Redirect {
    title: String,
    #[serde(default)]
    aliases: Vec<String>,
    #[serde(default)]
    is_blocking: bool,
    content_type: String,
    content: String,
}
#[derive(Serialize, Default, Debug)]
pub struct Loaded {
    pub scriptlets: usize,
    pub redirects: usize,
    pub skipped: usize,
}
/// Backend handed to each engine; reads whatever set was installed last.
pub(super) struct Shared;
impl ResourceStorageBackend for Shared {
    fn get_resource(&self, ident: &str) -> Option<ResourceImpl> {
        current().get_resource(ident)
    }
}
fn current() -> Arc<InMemoryResourceStorage> {
    STORE
        .get_or_init(RwLock::default)
        .read()
        .unwrap_or_else(|e| e.into_inner())
        .clone()
}
/// Changes whenever a new resource set is installed; per-page caches key on it.
pub fn generation() -> u64 {
    GENERATION.load(Ordering::Acquire)
}
fn names(primary: &str, all: &[String]) -> (String, Vec<String>) {
    let with_js = |n: &str| {
        if n.ends_with(".js") {
            n.to_owned()
        } else {
            format!("{n}.js")
        }
    };
    let name = with_js(primary);
    let mut aliases = Vec::new();
    for n in all {
        for alias in [n.clone(), with_js(n)] {
            if alias != name && !aliases.contains(&alias) {
                aliases.push(alias);
            }
        }
    }
    (name, aliases)
}
/// AdGuard's CoreLibs functions take `(source, args)`; adblock-rust calls a function resource as
/// `name("arg1", "arg2")`. Wrap each one in a uniquely named adapter.
fn scriptlet(entry: &Scriptlet) -> Option<Resource> {
    let primary = entry.names.first()?;
    if !entry.scriptlet.starts_with("function ") || entry.names.len() > 64 {
        return None;
    }
    let (name, aliases) = names(primary, &entry.names);
    let ident: String = primary
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() { c } else { '_' })
        .collect();
    let source = serde_json::to_string(primary).ok()?;
    let content = format!(
        "function blockads_{ident}(...args) {{\n({body})({{name: {source}, args: args, engine: \"corelibs\", version: \"\", verbose: false, ruleText: \"\", domainName: location.hostname, uniqueId: \"\"}}, args);\n}}",
        body = entry.scriptlet
    );
    Some(Resource {
        name,
        aliases,
        // adblock-rust injects only application/javascript (function style) or templates;
        // fn/javascript is reserved for dependencies.
        kind: ResourceType::Mime(MimeType::ApplicationJavascript),
        content: BASE64.encode(content),
        dependencies: vec![],
        permission: if entry.names.iter().any(|n| n.starts_with("trusted-")) {
            TRUSTED
        } else {
            PermissionMask::default()
        },
    })
}
fn redirect(entry: Redirect) -> Option<Resource> {
    let (mime, encoding) = entry
        .content_type
        .split_once(';')
        .unwrap_or((&entry.content_type, ""));
    let mime = MimeType::from(mime.trim());
    if entry.is_blocking || matches!(mime, MimeType::Unknown | MimeType::FnJavascript) {
        return None;
    }
    let content = if encoding.trim() == "base64" {
        entry.content
    } else {
        BASE64.encode(entry.content)
    };
    let (name, aliases) = (entry.title, entry.aliases);
    Some(Resource {
        name,
        aliases,
        kind: ResourceType::Mime(mime),
        content,
        dependencies: vec![],
        permission: PermissionMask::default(),
    })
}
/// Installs AdGuard `scriptlets.corelibs.json` and `redirects.json`. Either may be empty. The
/// previous set stays active if the input is malformed.
pub fn install(scriptlets: &str, redirects: &str) -> Result<Loaded, String> {
    if scriptlets.len() > MAX_JSON || redirects.len() > MAX_JSON {
        return Err("Tài nguyên quá lớn".into());
    }
    let scriptlets: Vec<Scriptlet> = if scriptlets.trim().is_empty() {
        Vec::new()
    } else {
        serde_json::from_str::<Corelibs>(scriptlets)
            .map_err(|e| format!("Scriptlet không hợp lệ: {e}"))?
            .scriptlets
    };
    let redirects: Vec<Redirect> = if redirects.trim().is_empty() {
        Vec::new()
    } else {
        serde_json::from_str(redirects).map_err(|e| format!("Redirect không hợp lệ: {e}"))?
    };
    if scriptlets.len() + redirects.len() > MAX_RESOURCES {
        return Err("Quá nhiều tài nguyên".into());
    }
    let mut store = InMemoryResourceStorage::default();
    let mut loaded = Loaded::default();
    for resource in redirects.into_iter().map(redirect) {
        match resource.map(|r| store.add_resource(r)) {
            Some(Ok(())) => loaded.redirects += 1,
            _ => loaded.skipped += 1,
        }
    }
    // Some names are both a redirect and a scriptlet (`noeval.js`, `nobab.js`). As in uBO, the
    // redirect script serves both; a scriptlet only keeps aliases nobody else has.
    for resource in scriptlets.iter().map(scriptlet) {
        let resource = resource.filter(|r| store.get_resource(&r.name).is_none());
        match resource.map(|mut r| {
            r.aliases
                .retain(|alias| store.get_resource(alias).is_none());
            store.add_resource(r)
        }) {
            Some(Ok(())) => loaded.scriptlets += 1,
            _ => loaded.skipped += 1,
        }
    }
    *STORE
        .get_or_init(RwLock::default)
        .write()
        .unwrap_or_else(|e| e.into_inner()) = Arc::new(store);
    GENERATION.fetch_add(1, Ordering::Release);
    Ok(loaded)
}
