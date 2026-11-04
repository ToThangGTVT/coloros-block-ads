use blockads_core::filter::{Context, Rules};

fn context<'a>(url: &'a str, source: &'a str, kind: &'a str) -> Context<'a> {
    Context {
        url,
        source,
        kind,
        method: "GET",
    }
}
fn blocked(rules: &Rules, url: &str, source: &str, kind: &str) -> Option<bool> {
    let domain = url.split('/').nth(2).unwrap();
    rules
        .check(domain, &context(url, source, kind), &[])
        .map(|m| m.blocked)
}

#[test]
fn context_options_need_matching_type_party_and_initiator() {
    let rules = Rules::compile(
        "||cdn.test/sdk.js$script,third-party\n||cdn.test/pixel.gif$image,domain=news.test|~live.news.test\n/banner[0-9]+\\.png/$image",
        "",
    );
    assert!(rules.report.errors.is_empty());
    assert_eq!(rules.report.ignored, 0);
    assert_eq!(rules.report.loaded, 3);
    let sdk = "https://cdn.test/sdk.js";
    assert_eq!(
        blocked(&rules, sdk, "https://news.test/a", "script"),
        Some(true)
    );
    assert_eq!(
        blocked(&rules, sdk, "https://www.cdn.test/", "script"),
        None
    );
    assert_eq!(blocked(&rules, sdk, "https://news.test/a", "image"), None);
    let pixel = "https://cdn.test/pixel.gif";
    assert_eq!(
        blocked(&rules, pixel, "https://m.news.test/", "image"),
        Some(true)
    );
    assert_eq!(
        blocked(&rules, pixel, "https://live.news.test/", "image"),
        None
    );
    assert_eq!(blocked(&rules, pixel, "https://other.test/", "image"), None);
    assert_eq!(
        blocked(
            &rules,
            "https://img.test/banner42.png",
            "https://x.test/",
            "image"
        ),
        Some(true)
    );
    assert!(
        rules.matches("cdn.test", None, &[]).is_none(),
        "context rules must not block DNS or opaque TLS"
    );
}

#[test]
fn exceptions_important_and_custom_rules_keep_domain_matcher_priority() {
    let rules = Rules::compile(
        "0.0.0.0 tracker.test\n@@||tracker.test/consent.js$script\n||ads.test/a.js$script\n@@||ads.test/a.js$script,domain=ok.test\n||hard.test/x.js$script,important\n@@||hard.test/x.js$script\n@@||list-allowed.test/y.js$script",
        "@@||hard.test/x.js$script,domain=mine.test\n||list-allowed.test/y.js$script",
    );
    assert!(rules.report.errors.is_empty());
    assert!(
        rules.lookup("tracker.test").unwrap().blocked,
        "DNS stays blocked"
    );
    let consent = "https://tracker.test/consent.js";
    assert_eq!(
        blocked(&rules, consent, "https://site.test/", "script"),
        Some(false),
        "list exception lifts a hosts block for that request"
    );
    assert_eq!(
        blocked(
            &rules,
            "https://tracker.test/t.js",
            "https://site.test/",
            "script"
        ),
        Some(true)
    );
    let script = "https://ads.test/a.js";
    assert_eq!(
        blocked(&rules, script, "https://x.test/", "script"),
        Some(true)
    );
    assert_eq!(
        blocked(&rules, script, "https://ok.test/", "script"),
        Some(false)
    );
    let hard = "https://hard.test/x.js";
    assert_eq!(
        blocked(&rules, hard, "https://x.test/", "script"),
        Some(true),
        "list $important beats list exception"
    );
    assert_eq!(
        blocked(&rules, hard, "https://mine.test/", "script"),
        Some(false),
        "custom exception beats list $important"
    );
    assert_eq!(
        blocked(
            &rules,
            "https://list-allowed.test/y.js",
            "https://x.test/",
            "script"
        ),
        Some(true),
        "custom block beats list exception"
    );
}

#[test]
fn custom_context_rules_are_accepted_but_unsafe_rules_still_fail() {
    let rules = Rules::compile("", "||a.test^$script\n||b.test/ads/*$xmlhttprequest");
    assert!(rules.report.errors.is_empty());
    assert_eq!(rules.report.loaded, 2);
    assert!(rules.matches("a.test", None, &[]).is_none());
    assert_eq!(
        blocked(&rules, "https://a.test/x.js", "https://a.test/", "script"),
        Some(true)
    );
    assert_eq!(
        blocked(
            &rules,
            "https://b.test/ads/1",
            "https://b.test/",
            "xmlhttprequest"
        ),
        Some(true)
    );
    for line in [
        "/(a{1000000})/$script",
        "||a.test^$app=com.x,script",
        "||a.test^$not-a-real-option",
    ] {
        assert!(
            !Rules::compile("", line).report.errors.is_empty(),
            "accepted {line}"
        );
    }
}

#[test]
fn plain_domain_lists_stay_out_of_the_engine() {
    // A bare domain is an exact host in domain lists, never a URL substring.
    let rules = Rules::compile("ads.test\n||track.test^\n0.0.0.0 host.test", "");
    assert_eq!(
        blocked(
            &rules,
            "https://site.test/?next=ads.test",
            "https://site.test/",
            "document"
        ),
        None
    );
    assert_eq!(
        blocked(&rules, "https://ads.test/", "https://ads.test/", "document"),
        Some(true)
    );
}
