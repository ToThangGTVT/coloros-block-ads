//! Full Adblock Plus/uBO network syntax for decrypted HTTPS requests, backed by Brave's
//! adblock-rust. Plain domain rules stay in the DNS matcher; this engine only receives rules that
//! need request context (path, `$third-party`, `$script`, `$domain=`, ...).
use adblock::{
    lists::{parse_filter, FilterSet, ParseOptions, ParsedLine, RuleTypes},
    sourcemap::FilterRuleDebugInfo,
    Engine,
};

/// Request seen by the HTTPS proxy. `kind` uses adblock request types (`script`, `image`,
/// `subdocument`, `xmlhttprequest`, ...); `source` is the initiating page or frame URL.
pub struct Context<'a> {
    pub url: &'a str,
    pub source: &'a str,
    pub kind: &'a str,
    pub method: &'a str,
}
#[derive(Default)]
pub(super) struct Network {
    system: Option<Engine>,
    custom: Option<Engine>,
}
#[derive(Default)]
pub(super) struct Builder {
    system: String,
    custom: String,
}
fn options() -> ParseOptions {
    ParseOptions {
        rule_types: RuleTypes::NetworkOnly,
        ..ParseOptions::default()
    }
}
/// Lines the domain matcher cannot express. `[@@]||domain^[$important]`, plain domains, hosts
/// entries and `$app` rules stay there, which keeps large DNS lists out of the engine.
pub(super) fn candidate(line: &str) -> bool {
    if line.is_empty()
        || line.starts_with(['!', '[', '#'])
        || line.contains(char::is_whitespace)
        || ["##", "#@", "#$", "#%", "#?"]
            .iter()
            .any(|c| line.contains(c))
    {
        return false;
    }
    let rule = line.strip_prefix("@@").unwrap_or(line);
    if rule.starts_with('/') {
        return true;
    }
    let (pattern, modifiers) = rule.split_once('$').unwrap_or((rule, ""));
    if modifiers.split(',').any(|m| {
        m.trim_start_matches('~')
            .split_once('=')
            .is_some_and(|(key, _)| key == "app")
    }) {
        return false;
    }
    let domain = pattern
        .strip_prefix("||")
        .and_then(|p| p.strip_suffix('^'))
        .unwrap_or(pattern);
    !(matches!(modifiers, "" | "important") && super::valid_domain(domain))
}
/// Whether the engine can load a line the domain matcher rejected. Regex rules keep the same
/// compile limits as the domain matcher, so a list cannot smuggle in an unbounded regex.
pub(super) fn accepts(line: &str) -> bool {
    let rule = line.strip_prefix("@@").unwrap_or(line);
    if let Some(body) = rule.strip_prefix('/') {
        let Some(end) = body.rfind('/') else {
            return false;
        };
        if super::bounded_regex(&body[..end]).is_err() {
            return false;
        }
    }
    if let Some(host) = rule.strip_prefix("||") {
        let end = host.find(['/', '^', '*', '|', '$']).unwrap_or(host.len());
        if !super::valid_domain(&host[..end]) {
            return false;
        }
    }
    matches!(
        parse_filter(line, false, options()),
        Ok(ParsedLine::Network(_))
    )
}
impl Builder {
    pub fn push(&mut self, line: &str, custom: bool) {
        let list = if custom {
            &mut self.custom
        } else {
            &mut self.system
        };
        list.push_str(line);
        list.push('\n');
    }
    pub fn build(self) -> Network {
        let engine = |list: String| {
            (!list.is_empty()).then(|| {
                // Debug keeps the raw rule so the query log can name what matched.
                let mut set = FilterSet::new(true);
                set.add_filter_list(list, options());
                Engine::new_with_filter_set(set)
            })
        };
        Network {
            system: engine(self.system),
            custom: engine(self.custom),
        }
    }
}
fn text(info: Option<FilterRuleDebugInfo>) -> String {
    info.and_then(|i| i.raw_line)
        .unwrap_or_else(|| "adblock".into())
}
impl Network {
    /// Returns `(rank, rule)` using the domain matcher's ranks: custom allow 6 > custom block 5 >
    /// important 3 > list exception 2 > list block 1.
    pub fn check(&self, context: &Context) -> Option<(u8, String)> {
        let request = adblock::request::Request::new(
            context.url,
            context.source,
            context.kind,
            context.method,
        )
        .ok()?;
        for (engine, ranks) in [(&self.custom, (5, 5, 6)), (&self.system, (3, 1, 2))] {
            let Some(engine) = engine else {
                continue;
            };
            // Exceptions are always reported so a list `@@` can lift a DNS-list block for this
            // request, and a custom `@@` can lift any list block.
            let result = engine.check_network_request_subset(&request, false, true);
            if result.important {
                return Some((ranks.0, text(result.filter)));
            }
            if let Some(exception) = result.exception {
                return Some((ranks.2, text(Some(exception))));
            }
            if result.filter.is_some() {
                return Some((ranks.1, text(result.filter)));
            }
        }
        None
    }
}
