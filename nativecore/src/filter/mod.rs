//! Immutable exact/suffix domain matcher. Compiles off the tunnel worker and swaps atomically.
mod cosmetic;
pub use cosmetic::Page;
mod network;
pub mod resources;
pub use network::Context;
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
    scoped: HashMap<Box<str>, Vec<Scoped>>,
    patterns: Vec<Pattern>,
    cosmetic: cosmetic::Cosmetic,
    network: network::Network,
}
#[derive(Debug, PartialEq)]
pub struct Decision<'a> {
    pub blocked: bool,
    pub domain: &'a str,
    pub suffix: bool,
    pub rank: u8,
}

/// Where a rule list applies.
#[derive(Clone, Copy, PartialEq)]
enum Scope {
    /// Domain matcher only: DNS answers and connection decisions. Cosmetic and request-context
    /// rules are ignored, as AdGuard Home ignores them.
    Dns,
    /// Request and cosmetic rules for decrypted HTTPS only; never blocks a DNS answer.
    Browser,
    /// Everywhere: user rules and the `system` text of [`Rules::compile`].
    All,
}
impl Rules {
    /// `system` applies everywhere, like `custom`. The app passes DNS and browser lists apart
    /// with [`Rules::compile_lists`].
    pub fn compile(system: &str, custom: &str) -> Self {
        Self::build(&[(system, Scope::All, false), (custom, Scope::All, true)])
    }
    pub fn compile_lists(dns: &str, browser: &str, custom: &str) -> Self {
        Self::build(&[
            (dns, Scope::Dns, false),
            (browser, Scope::Browser, false),
            (custom, Scope::All, true),
        ])
    }
    fn build(sources: &[(&str, Scope, bool)]) -> Self {
        let capacity = sources
            .iter()
            .filter(|(_, scope, _)| *scope != Scope::Browser)
            .map(|(text, _, _)| text.lines().count())
            .sum();
        let mut rules = Self {
            entries: HashMap::with_capacity(capacity),
            ..Self::default()
        };
        let mut network = network::Builder::default();
        let mut cosmetic = String::new();
        for &(text, scope, is_custom) in sources {
            for (index, line) in text.lines().enumerate() {
                let line = line.trim();
                let parsed = if cosmetic::candidate(line) {
                    if scope == Scope::Dns {
                        Err("Danh sách DNS không áp dụng ẩn phần tử")
                    } else {
                        cosmetic::accepts(line).map(|()| {
                            cosmetic.push_str(line);
                            cosmetic.push('\n');
                            rules.report.loaded += 1;
                        })
                    }
                } else if scope == Scope::Browser {
                    if comment(line) {
                        Ok(())
                    } else if network::accepts(line) {
                        network.push(line, is_custom);
                        if cosmetic::switches_hiding(line) {
                            cosmetic.push_str(line);
                            cosmetic.push('\n');
                        }
                        rules.report.loaded += 1;
                        Ok(())
                    } else {
                        Err("Network rule chưa được hỗ trợ")
                    }
                } else {
                    let candidate = scope == Scope::All && network::candidate(line);
                    let parsed = rules.parse(line, is_custom);
                    if candidate && (parsed.is_ok() || network::accepts(line)) {
                        network.push(line, is_custom);
                        if cosmetic::switches_hiding(line) {
                            cosmetic.push_str(line);
                            cosmetic.push('\n');
                        }
                        if parsed.is_err() {
                            // Context-dependent rule: enforced only by the HTTPS proxy.
                            rules.report.loaded += 1;
                            continue;
                        }
                    }
                    parsed
                };
                if let Err(message) = parsed {
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
        rules.network = network.build();
        rules.cosmetic = cosmetic::Cosmetic::build(cosmetic);
        rules
    }
    fn parse(&mut self, line: &str, custom: bool) -> Result<(), &'static str> {
        let line = line.trim();
        if comment(line) {
            return Ok(());
        }
        let original = line;
        let allow = line.starts_with("@@");
        let line = line.strip_prefix("@@").unwrap_or(line);
        let fields: Vec<_> = line
            .split('#')
            .next()
            .unwrap_or("")
            .split_whitespace()
            .collect();
        if fields.len() >= 2 && matches!(fields[0], "0.0.0.0" | "127.0.0.1" | "::" | "::0") {
            for domain in &fields[1..] {
                if domain.eq_ignore_ascii_case("localhost") || domain.ends_with(".localhost") {
                    continue;
                }
                self.insert(domain, false, allow, custom, false)?;
            }
            return Ok(());
        }
        let (pattern, modifiers, explicit_regex) = if line.starts_with('/') {
            let end = line
                .rfind('/')
                .filter(|end| *end > 0)
                .ok_or("Regex thiếu dấu / kết thúc")?;
            (
                &line[1..end],
                line[end + 1..]
                    .strip_prefix('$')
                    .unwrap_or(&line[end + 1..]),
                true,
            )
        } else {
            let (p, m) = line.split_once('$').unwrap_or((line, ""));
            (p, m, false)
        };
        let mut important = false;
        let mut apps = Vec::new();
        for modifier in modifiers.split(',').filter(|m| !m.is_empty()) {
            if modifier == "important" {
                important = true;
            } else if let Some(packages) = modifier.strip_prefix("app=") {
                for package in packages.split('|') {
                    if package.trim_start_matches('~').is_empty()
                        || !package
                            .trim_start_matches('~')
                            .bytes()
                            .all(|c| c.is_ascii_alphanumeric() || c == b'.' || c == b'_')
                    {
                        return Err("Package $app không hợp lệ");
                    }
                    apps.push(package.to_owned());
                }
            } else {
                return Err("Modifier chưa được hỗ trợ");
            }
        }
        let rank = rank(allow, custom, important);
        let domain = if explicit_regex {
            None
        } else if let Some(value) = pattern.strip_prefix("||").and_then(|p| p.strip_suffix('^')) {
            if valid_domain(value) {
                Some((value, true))
            } else {
                None
            }
        } else if valid_domain(pattern) {
            Some((pattern, false))
        } else {
            None
        };
        if let Some((domain, suffix)) = domain {
            if apps.is_empty() {
                return self.insert(domain, suffix, allow, custom, important);
            }
            self.scoped
                .entry(
                    domain
                        .trim_end_matches('.')
                        .to_ascii_lowercase()
                        .into_boxed_str(),
                )
                .or_default()
                .push(Scoped {
                    suffix,
                    rank,
                    apps,
                    rule: original.into(),
                });
        } else {
            if pattern.len() > 2048 || self.patterns.len() >= 256 {
                return Err("Giới hạn 256 URL/regex rule, mỗi rule tối đa 2048 ký tự");
            }
            if pattern.is_empty() || pattern.chars().any(char::is_whitespace) {
                return Err("Network rule không hợp lệ");
            }
            let regex = if explicit_regex {
                pattern.to_owned()
            } else {
                network_regex(pattern)?
            };
            let regex = bounded_regex(&regex)?;
            self.patterns.push(Pattern {
                regex,
                domain_only: explicit_regex,
                rank,
                apps,
                rule: original.into(),
            });
        }
        self.report.loaded += 1;
        Ok(())
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
        if !valid_domain(&domain) {
            return Err("Domain không hợp lệ");
        }
        let rank = rank(allow, custom, important);
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
            rank: best,
        })
    }
    pub fn matches(&self, domain: &str, url: Option<&str>, packages: &[String]) -> Option<Matched> {
        let mut best = self.lookup(domain).map(|d| Matched {
            blocked: d.blocked,
            rank: d.rank,
            rule: format!(
                "{}{}{}{}",
                if d.blocked { "" } else { "@@" },
                if d.suffix { "||" } else { "" },
                d.domain,
                if d.suffix { "^" } else { "" }
            ),
            redirect: None,
            rewritten: None,
        });
        let mut current = Some(domain);
        while let Some(part) = current {
            if let Some(entries) = self.scoped.get(part) {
                for entry in entries {
                    if (entry.suffix || part == domain) && app_matches(&entry.apps, packages) {
                        choose(&mut best, entry.rank, &entry.rule);
                    }
                }
            }
            current = part.split_once('.').map(|(_, rest)| rest);
        }
        for pattern in &self.patterns {
            if pattern.rank <= best.as_ref().map_or(0, |b| b.rank)
                || !app_matches(&pattern.apps, packages)
            {
                continue;
            }
            let candidate = url.or_else(|| pattern.domain_only.then_some(domain));
            if candidate.is_some_and(|input| pattern.regex.is_match(input)) {
                choose(&mut best, pattern.rank, &pattern.rule);
            }
        }
        best
    }
    /// Decrypted HTTPS request: domain/app rules plus full network syntax with request context.
    pub fn check(&self, domain: &str, context: &Context, packages: &[String]) -> Option<Matched> {
        let mut best = self.matches(domain, Some(context.url), packages);
        let verdict = self.network.check(context);
        if let Some((rank, rule)) = verdict.decision {
            choose(&mut best, rank, &rule);
        }
        if let Some(best) = &mut best {
            if best.blocked {
                best.redirect = verdict.redirect;
            } else {
                best.rewritten = verdict.rewritten;
            }
        } else if let Some(rewritten) = verdict.rewritten {
            best = Some(Matched {
                blocked: false,
                rank: 0,
                rule: "$removeparam".into(),
                redirect: None,
                rewritten: Some(rewritten),
            });
        }
        best
    }
    /// Bounded per-domain cache belongs to this immutable rule snapshot; reload invalidates it.
    pub fn cosmetic_page(&self, domain: &str) -> Arc<cosmetic::Page> {
        self.cosmetic.page(domain)
    }
    /// CSS for generic `.class`/`#id` rules matching what the page reported.
    pub fn generic_css(&self, page: &cosmetic::Page, classes: &[&str], ids: &[&str]) -> String {
        self.cosmetic.generic(page, classes, ids)
    }
}
/// Blank, `! comment`, `# comment` or an `[Adblock Plus 2.0]` header.
fn comment(line: &str) -> bool {
    line.is_empty() || line.starts_with(['!', '#', '['])
}
fn bounded_regex(pattern: &str) -> Result<regex::Regex, &'static str> {
    regex::RegexBuilder::new(pattern)
        .case_insensitive(true)
        .size_limit(128 * 1024)
        .dfa_size_limit(32 * 1024)
        .nest_limit(32)
        .build()
        .map_err(|_| "Regex không hợp lệ hoặc vượt giới hạn bộ nhớ")
}
fn valid_domain(domain: &str) -> bool {
    let domain = domain.trim_end_matches('.');
    !domain.is_empty()
        && domain.len() <= 253
        && domain.parse::<std::net::IpAddr>().is_err()
        && domain.split('.').all(|label| {
            !label.is_empty()
                && label.len() <= 63
                && !label.starts_with('-')
                && !label.ends_with('-')
                && label
                    .bytes()
                    .all(|c| c.is_ascii_alphanumeric() || c == b'-' || c == b'_')
        })
}
fn rank(allow: bool, custom: bool, important: bool) -> u8 {
    if custom {
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
    }
}
struct Scoped {
    suffix: bool,
    rank: u8,
    apps: Vec<String>,
    rule: String,
}
struct Pattern {
    regex: regex::Regex,
    domain_only: bool,
    rank: u8,
    apps: Vec<String>,
    rule: String,
}
#[derive(Debug)]
pub struct Matched {
    pub blocked: bool,
    rank: u8,
    pub rule: String,
    /// `data:` URL of a `$redirect` resource to answer a blocked request with.
    pub redirect: Option<String>,
    /// Request URL after `$removeparam`, for a request that is not blocked.
    pub rewritten: Option<String>,
}
fn choose(best: &mut Option<Matched>, rank: u8, rule: &str) {
    if rank > best.as_ref().map_or(0, |b| b.rank) {
        *best = Some(Matched {
            blocked: rank % 2 == 1,
            rank,
            rule: rule.to_owned(),
            redirect: None,
            rewritten: None,
        });
    }
}
fn app_matches(selectors: &[String], packages: &[String]) -> bool {
    if selectors.is_empty() {
        return true;
    }
    if packages.is_empty() {
        return false;
    }
    let mut positive = false;
    let mut matched = false;
    for selector in selectors {
        if let Some(negative) = selector.strip_prefix('~') {
            if packages.iter().any(|p| p == negative) {
                return false;
            }
        } else {
            positive = true;
            matched |= packages.contains(selector);
        }
    }
    !positive || matched
}
fn network_regex(pattern: &str) -> Result<String, &'static str> {
    let (pattern, mut output) = if let Some(value) = pattern.strip_prefix("||") {
        let end = value.find(['/', '^', '*', '|']).unwrap_or(value.len());
        if !valid_domain(&value[..end]) {
            return Err("Domain trong network rule không hợp lệ");
        }
        let prefix = String::from("^[a-z][a-z0-9+.-]*://(?:[^/]*\\.)?");
        if value.as_bytes().get(end) == Some(&b'/') {
            (
                &value[end..],
                format!("{prefix}{}(?::[0-9]+)?", regex::escape(&value[..end])),
            )
        } else {
            (value, prefix)
        }
    } else if let Some(value) = pattern.strip_prefix('|') {
        (value, String::from("^"))
    } else {
        (pattern, String::new())
    };
    let anchored = pattern.ends_with('|');
    let pattern = if anchored {
        &pattern[..pattern.len() - 1]
    } else {
        pattern
    };
    for c in pattern.chars() {
        match c {
            '*' => output.push_str(".*"),
            '^' => output.push_str("(?:[^A-Za-z0-9_%.-]|$)"),
            _ => output.push_str(&regex::escape(&c.to_string())),
        }
    }
    if anchored {
        output.push('$');
    }
    Ok(output)
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
    install(Rules::compile(system, custom))
}
pub fn replace_lists(dns: &str, browser: &str, custom: &str) -> Report {
    install(Rules::compile_lists(dns, browser, custom))
}
fn install(mut rules: Rules) -> Report {
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
