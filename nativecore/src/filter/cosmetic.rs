//! Element hiding with Brave's adblock-rust cosmetic engine: host-specific CSS, procedural rules
//! for the in-page script, and generic `.class`/`#id` rules looked up for what the page contains.
use adblock::{
    cosmetic_filter_cache::ProceduralOrActionFilter,
    lists::{parse_filter, FilterSet, ParseOptions, ParsedLine, RuleTypes},
    Engine,
};
use std::{
    collections::{HashMap, HashSet, VecDeque},
    sync::{Arc, Mutex},
};

const MAX_RULE: usize = 4096;
/// What one document needs. Cached per host inside an immutable rule snapshot.
#[derive(Default)]
pub struct Page {
    /// `selector{declarations}` lines; `<` is escaped so the text cannot leave a `<style>`.
    pub css: String,
    /// JSON array of procedural/action filters run by the in-page script; `<` is escaped.
    pub procedural: String,
    /// False when `$generichide` applies or no generic class/id rules exist.
    pub generic: bool,
    /// `+js(...)` scriptlets, ready for a `<script>` element (`</` and `<!--` escaped).
    pub scriptlets: String,
    pub(crate) exceptions: HashSet<String>,
}
impl Page {
    pub fn is_empty(&self) -> bool {
        self.css.is_empty()
            && self.procedural.is_empty()
            && !self.generic
            && self.scriptlets.is_empty()
    }
}
#[derive(Default)]
pub(super) struct Cosmetic {
    engine: Option<Engine>,
    cache: Mutex<Cache>,
}
#[derive(Default)]
struct Cache {
    /// Resource generation the entries were built with; scriptlets depend on it.
    generation: u64,
    values: HashMap<String, Arc<Page>>,
    order: VecDeque<String>,
}
fn options() -> ParseOptions {
    ParseOptions {
        rule_types: RuleTypes::CosmeticOnly,
        ..ParseOptions::default()
    }
}
/// Network rules that switch element hiding off (`$generichide`, `$elemhide`); the cosmetic
/// engine evaluates them itself when building a page.
pub(super) fn switches_hiding(line: &str) -> bool {
    line.starts_with("@@")
        && line.rsplit_once('$').is_some_and(|(_, options)| {
            options
                .split(',')
                .any(|o| matches!(o, "generichide" | "ghide" | "elemhide" | "ehide"))
        })
}
/// Same split as adblock-rust: a `#` followed by another `#` within four bytes. Hosts-file
/// comments (`# text`, `## text`) stay comments.
pub(super) fn candidate(line: &str) -> bool {
    let bytes = line.as_bytes();
    if line.starts_with(['!', '['])
        || (bytes.first() == Some(&b'#')
            && line
                .trim_start_matches('#')
                .starts_with(char::is_whitespace))
    {
        return false;
    }
    bytes
        .iter()
        .position(|c| *c == b'#')
        .is_some_and(|at| bytes[at + 1..bytes.len().min(at + 5)].contains(&b'#'))
}
/// Scriptlets parse without any permission, so neither lists nor custom rules can call
/// `trusted-*` scriptlets.
pub(super) fn accepts(line: &str) -> Result<(), &'static str> {
    if line.len() > MAX_RULE {
        return Err("Quy tắc ẩn phần tử quá dài");
    }
    match parse_filter(line, false, options()) {
        Ok(ParsedLine::Cosmetic(_)) => Ok(()),
        _ => Err("Quy tắc ẩn phần tử không hợp lệ hoặc chưa được hỗ trợ"),
    }
}
fn escape(text: &str) -> String {
    // `<` only occurs inside CSS strings/attribute values, where `\3c ` is the same character.
    text.replace('<', "\\3c ")
}
impl Cosmetic {
    pub fn build(list: String) -> Self {
        let engine = (!list.is_empty()).then(|| {
            let mut set = FilterSet::new(false);
            // All rule types: the list only holds cosmetic lines and `switches_hiding` lines.
            set.add_filter_list(list, ParseOptions::default());
            let mut engine = Engine::new_with_filter_set(set);
            engine.use_resource_storage(super::resources::Shared);
            engine
        });
        Self {
            engine,
            cache: Mutex::default(),
        }
    }
    pub fn page(&self, host: &str) -> Arc<Page> {
        let Some(engine) = &self.engine else {
            return Arc::default();
        };
        let mut cache = self.cache.lock().unwrap_or_else(|e| e.into_inner());
        let generation = super::resources::generation();
        if cache.generation != generation {
            *cache = Cache {
                generation,
                ..Cache::default()
            };
        }
        if let Some(page) = cache.values.get(host) {
            return page.clone();
        }
        let resources = engine.url_cosmetic_resources(&format!("https://{host}/"));
        let mut rules = resources
            .hide_selectors
            .iter()
            .map(|selector| format!("{}{{display:none!important}}\n", escape(selector)))
            .collect::<Vec<_>>();
        let mut procedural = Vec::new();
        for json in &resources.procedural_actions {
            let Ok(filter) = serde_json::from_str::<ProceduralOrActionFilter>(json) else {
                continue;
            };
            match filter.as_css() {
                // Style arguments were validated by the parser (no url(), escapes or comments).
                Some((selector, style)) => {
                    rules.push(format!("{}{{{}}}\n", escape(&selector), escape(&style)))
                }
                None => procedural.push(json.replace('<', "\\u003c")),
            }
        }
        // One rule per selector: an unsupported selector only drops its own rule.
        rules.sort_unstable();
        let page = Arc::new(Page {
            css: rules.concat(),
            procedural: if procedural.is_empty() {
                String::new()
            } else {
                procedural.sort_unstable();
                format!("[{}]", procedural.join(","))
            },
            generic: !resources.generichide,
            // Inside <script> only `</` and `<!--` matter to the HTML parser; `<\/` and `<\!--`
            // mean the same in JS strings, regexes and comments.
            scriptlets: resources
                .injected_script
                .replace("</", "<\\/")
                .replace("<!--", "<\\!--"),
            exceptions: resources.exceptions,
        });
        if cache.values.len() >= 128 {
            if let Some(oldest) = cache.order.pop_front() {
                cache.values.remove(&oldest);
            }
        }
        cache.order.push_back(host.to_owned());
        cache.values.insert(host.to_owned(), page.clone());
        page
    }
    /// Generic rules keyed by classes/ids seen in the page, minus this page's exceptions.
    pub fn generic(&self, page: &Page, classes: &[&str], ids: &[&str]) -> String {
        let Some(engine) = self.engine.as_ref().filter(|_| page.generic) else {
            return String::new();
        };
        let mut selectors = engine.hidden_class_id_selectors(classes, ids, &page.exceptions);
        selectors.sort_unstable();
        selectors.dedup();
        selectors
            .iter()
            .map(|selector| format!("{}{{display:none!important}}\n", escape(selector)))
            .collect()
    }
}
