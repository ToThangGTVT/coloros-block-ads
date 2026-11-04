//! Cached declarative CSS with domain scopes and exclusions. No procedural selectors or scripts.
use std::{
    collections::{BTreeSet, HashMap, VecDeque},
    sync::{Arc, Mutex, OnceLock},
};

const MAX_CSS: usize = 64 * 1024;
#[derive(Default)]
pub(super) struct Cosmetic {
    domains: HashMap<String, Vec<Entry>>,
    generic: Vec<Entry>,
    count: usize,
    cache: Mutex<Cache>,
}
#[derive(Default)]
struct Cache {
    values: HashMap<String, Arc<str>>,
    order: VecDeque<String>,
}
struct Entry {
    selector: String,
    excluded: Vec<String>,
    exception: bool,
}
impl Cosmetic {
    pub fn parse(&mut self, line: &str) -> Option<Result<(), &'static str>> {
        if line.starts_with('!') || line.starts_with('[') {
            return None;
        }
        let (scope, selector, exception) = if let Some((scope, selector)) = line.split_once("#@#") {
            (scope, selector, true)
        } else if let Some((scope, selector)) = line.split_once("##") {
            (scope, selector, false)
        } else {
            return None;
        };
        Some(self.insert(scope, selector.trim(), exception))
    }
    fn insert(&mut self, scope: &str, selector: &str, exception: bool) -> Result<(), &'static str> {
        if self.count >= 50_000 || selector.len() > 2048 {
            return Err("Vượt giới hạn quy tắc ẩn phần tử");
        }
        if !selector_regex().is_match(selector) {
            return Err("Chỉ hỗ trợ CSS đơn giản: tag, .class, #id, thuộc tính và tổ hợp; không hỗ trợ scriptlet/pseudo-class");
        }
        let mut included = Vec::new();
        let mut excluded = Vec::new();
        for domain in scope.split(',').filter(|d| !d.is_empty()) {
            let domain = domain.trim().to_ascii_lowercase();
            let (name, negative) = domain
                .strip_prefix('~')
                .map_or((domain.as_str(), false), |d| (d, true));
            if !super::valid_domain(name)
                || !name.contains('.')
                || included.len() + excluded.len() >= 100
            {
                return Err("Quy tắc ẩn phần tử cần tên miền cụ thể, tối đa 100 tên miền");
            }
            if negative {
                excluded.push(name.to_owned());
            } else {
                included.push(name.to_owned());
            }
        }
        if self.count + included.len().max(1) > 50_000 {
            return Err("Vượt giới hạn quy tắc ẩn phần tử");
        }
        self.count += included.len().max(1);
        if included.is_empty() {
            self.generic.push(Entry {
                selector: selector.to_owned(),
                excluded,
                exception,
            });
            return Ok(());
        }
        for domain in included {
            self.domains.entry(domain).or_default().push(Entry {
                selector: selector.to_owned(),
                excluded: excluded.clone(),
                exception,
            });
        }
        Ok(())
    }
    pub fn css(&self, domain: &str) -> Arc<str> {
        if self.domains.is_empty() && self.generic.is_empty() {
            return Arc::from("");
        }
        let mut cache = self.cache.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(css) = cache.values.get(domain) {
            return css.clone();
        }
        let mut hidden = BTreeSet::new();
        let mut exceptions = BTreeSet::new();
        for entry in &self.generic {
            if entry
                .excluded
                .iter()
                .any(|d| domain == d || domain.strip_suffix(d).is_some_and(|p| p.ends_with('.')))
            {
                continue;
            }
            if entry.exception {
                exceptions.insert(entry.selector.as_str());
            } else {
                hidden.insert(entry.selector.as_str());
            }
        }
        let mut current = Some(domain);
        while let Some(part) = current {
            if let Some(entries) = self.domains.get(part) {
                for entry in entries {
                    if entry.excluded.iter().any(|d| {
                        domain == d || domain.strip_suffix(d).is_some_and(|p| p.ends_with('.'))
                    }) {
                        continue;
                    }
                    if entry.exception {
                        exceptions.insert(entry.selector.as_str());
                    } else {
                        hidden.insert(entry.selector.as_str());
                    }
                }
            }
            current = part.split_once('.').map(|(_, rest)| rest);
        }
        let mut css = String::new();
        for selector in hidden.difference(&exceptions) {
            let declaration = "{display:none!important}\n";
            if css.len() + selector.len() + declaration.len() > MAX_CSS {
                break;
            }
            css.push_str(selector);
            css.push_str(declaration);
        }
        let css: Arc<str> = css.into();
        if cache.values.len() >= 128 {
            if let Some(oldest) = cache.order.pop_front() {
                cache.values.remove(&oldest);
            }
        }
        cache.order.push_back(domain.to_owned());
        cache.values.insert(domain.to_owned(), css.clone());
        css
    }
}
fn selector_regex() -> &'static regex::Regex {
    static REGEX: OnceLock<regex::Regex> = OnceLock::new();
    REGEX.get_or_init(|| {
        // Conservative ASCII subset. In particular no HTML delimiters, CSS escapes, URLs,
        // declaration delimiters or pseudo selectors can reach the injected style element.
        let ident = r"[a-zA-Z_][a-zA-Z0-9_-]*|-+[a-zA-Z_][a-zA-Z0-9_-]*";
        let ident = format!("(?:{ident})");
        let attr = format!(r#"\[\s*{ident}(?:\s*[*^$|~]?=\s*(?:{ident}|"[a-zA-Z0-9_ .:/-]*"|'[a-zA-Z0-9_ .:/-]*'))?\s*\]"#);
        let component = format!(r"(?:{ident}|\*|[.#]{ident}|{attr})(?:[.#]{ident}|{attr})*");
        let pattern = format!(r"^{component}(?:(?:\s*[>+~,]\s*|\s+){component})*$").replace(r"\s", r"[ \t\r\n\x0c]");
        regex::Regex::new(&pattern).unwrap()
    })
}
