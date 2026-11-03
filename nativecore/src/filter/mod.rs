//! Immutable exact/suffix domain matcher. Compiles off the tunnel worker and swaps atomically.
use serde::Serialize;
use std::{
    collections::HashMap,
    sync::atomic::{AtomicU64, Ordering},
    sync::{Arc, OnceLock, RwLock},
};

#[derive(Clone, Copy, Default)]
struct Entry {
    exact: u8,
    suffix: u8,
}
#[derive(Default, Serialize, Clone)]
pub struct Report {
    pub loaded: usize,
    pub ignored: usize,
    pub errors: Vec<LineError>,
    pub applied: bool,
}
#[derive(Serialize, Clone)]
pub struct LineError {
    pub line: usize,
    pub message: String,
}
#[derive(Default)]
pub struct Rules {
    entries: HashMap<Box<str>, Entry>,
    pub report: Report,
}
#[derive(Debug, PartialEq)]
pub struct Decision<'a> {
    pub blocked: bool,
    pub domain: &'a str,
    pub suffix: bool,
}

impl Rules {
    pub fn compile(system: &str, custom: &str) -> Self {
        let capacity = system.lines().count() + custom.lines().count();
        let mut rules = Self {
            entries: HashMap::with_capacity(capacity),
            ..Self::default()
        };
        for (text, is_custom) in [(system, false), (custom, true)] {
            for (index, line) in text.lines().enumerate() {
                if let Err(message) = rules.parse(line, is_custom) {
                    rules.report.ignored += 1;
                    if is_custom && rules.report.errors.len() < 100 {
                        rules.report.errors.push(LineError {
                            line: index + 1,
                            message: message.into(),
                        });
                    }
                }
            }
        }
        rules
    }
    fn parse(&mut self, line: &str, custom: bool) -> Result<(), &'static str> {
        let line = line.trim();
        if line.is_empty() || line.starts_with('!') || line.starts_with('#') {
            return Ok(());
        }
        if line.contains("##") || line.contains("#@#") {
            self.report.ignored += 1;
            return Ok(());
        }
        let allow = line.starts_with("@@");
        let line = line.strip_prefix("@@").unwrap_or(line);
        let (domain, suffix, important) = if let Some(network) = line.strip_prefix("||") {
            let (domain, modifiers) = network.split_once('^').ok_or("Cần ký hiệu ^ sau domain")?;
            if !modifiers.is_empty() && modifiers != "$important" {
                return Err("Modifier chưa được hỗ trợ ở Phase 2");
            }
            (domain, true, modifiers == "$important")
        } else {
            let clean = line.split('#').next().unwrap_or("");
            let fields: Vec<_> = clean.split_whitespace().collect();
            if fields.len() >= 2 && matches!(fields[0], "0.0.0.0" | "127.0.0.1" | "::" | "::0") {
                for domain in &fields[1..] {
                    if domain.eq_ignore_ascii_case("localhost") || domain.ends_with(".localhost") {
                        continue;
                    }
                    self.insert(domain, false, false, custom, false)?;
                }
                return Ok(());
            }
            if fields.len() != 1 {
                return Err("Cần hosts entry hoặc ||domain^");
            }
            (fields[0], false, false)
        };
        self.insert(domain, suffix, allow, custom, important)
    }
    fn insert(
        &mut self,
        domain: &str,
        suffix: bool,
        allow: bool,
        custom: bool,
        important: bool,
    ) -> Result<(), &'static str> {
        let domain = domain.trim_end_matches('.').to_ascii_lowercase();
        if domain.is_empty()
            || domain.len() > 253
            || domain.parse::<std::net::IpAddr>().is_ok()
            || !domain.split('.').all(|label| {
                !label.is_empty()
                    && label.len() <= 63
                    && !label.starts_with('-')
                    && !label.ends_with('-')
                    && label
                        .bytes()
                        .all(|c| c.is_ascii_alphanumeric() || c == b'-' || c == b'_')
            })
        {
            return Err("Domain không hợp lệ");
        }
        // Higher rank wins across all suffixes; even ranks are exceptions.
        let rank = if custom {
            if allow {
                6
            } else {
                5
            }
        } else if important {
            if allow {
                4
            } else {
                3
            }
        } else if allow {
            2
        } else {
            1
        };
        let entry = self.entries.entry(domain.into_boxed_str()).or_default();
        let slot = if suffix {
            &mut entry.suffix
        } else {
            &mut entry.exact
        };
        *slot = (*slot).max(rank);
        self.report.loaded += 1;
        Ok(())
    }
    /// Domain is already lowercase, without a trailing dot (DNS normalizes once).
    pub fn lookup<'a>(&self, domain: &'a str) -> Option<Decision<'a>> {
        let mut best = 0;
        let mut matched = domain;
        let mut suffix = false;
        if let Some(entry) = self.entries.get(domain) {
            best = entry.exact;
            if entry.suffix > best {
                best = entry.suffix;
                suffix = true;
            }
        }
        let mut current = domain;
        while let Some((_, remaining)) = current.split_once('.') {
            current = remaining;
            if let Some(entry) = self.entries.get(current) {
                if entry.suffix > best {
                    best = entry.suffix;
                    matched = current;
                    suffix = true;
                }
            }
        }
        (best > 0).then_some(Decision {
            blocked: best % 2 == 1,
            domain: matched,
            suffix,
        })
    }
}
static ACTIVE: OnceLock<RwLock<Arc<Rules>>> = OnceLock::new();
static REVISION: AtomicU64 = AtomicU64::new(0);
pub fn current() -> Arc<Rules> {
    ACTIVE
        .get_or_init(|| RwLock::new(Arc::new(Rules::default())))
        .read()
        .unwrap_or_else(|e| e.into_inner())
        .clone()
}
pub fn revision() -> u64 {
    REVISION.load(Ordering::Acquire)
}
pub fn replace(system: &str, custom: &str) -> Report {
    let mut rules = Rules::compile(system, custom);
    if !rules.report.errors.is_empty() {
        return rules.report;
    }
    rules.report.applied = true;
    let report = rules.report.clone();
    *ACTIVE
        .get_or_init(|| RwLock::new(Arc::new(Rules::default())))
        .write()
        .unwrap_or_else(|e| e.into_inner()) = Arc::new(rules);
    REVISION.fetch_add(1, Ordering::Release);
    report
}
