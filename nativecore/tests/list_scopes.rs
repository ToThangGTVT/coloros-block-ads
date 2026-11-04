use blockads_core::filter::{Context, Rules};

fn check(rules: &Rules, url: &str) -> Option<bool> {
    let domain = url.split('/').nth(2).unwrap();
    rules
        .check(
            domain,
            &Context {
                url,
                source: "https://site.test/",
                kind: "script",
                method: "GET",
            },
            &[],
        )
        .map(|m| m.blocked)
}

#[test]
fn dns_and_browser_lists_do_not_mix() {
    let rules = Rules::compile_lists(
        "||dns.test^\n0.0.0.0 hosts.test\nsite.test##.dns-banner",
        "! Title: browser\n[Adblock Plus 2.0]\n||ads.test^\nsite.test##.banner\n/mellow.test^$script\n||bapi-*.track.test^\n||192.0.2.7^\n/^https:\\/\\/rx\\.test\\/ad/$script",
        "||custom.test^",
    );
    assert!(rules.report.errors.is_empty());
    assert_eq!(rules.report.ignored, 1, "a DNS list cannot hide elements");

    // DNS lists answer DNS (and keep applying to decrypted requests of those domains).
    assert!(rules.lookup("dns.test").unwrap().blocked);
    assert!(rules.lookup("hosts.test").unwrap().blocked);
    assert_eq!(check(&rules, "https://dns.test/x.js"), Some(true));
    // Browser lists never block a DNS answer, only decrypted requests.
    assert!(rules.lookup("ads.test").is_none());
    assert_eq!(check(&rules, "https://ads.test/x.js"), Some(true));
    assert_eq!(
        check(&rules, "https://cdn.test/mellow.test/a.js"),
        Some(true)
    );
    assert_eq!(check(&rules, "https://bapi-eu.track.test/e"), Some(true));
    assert_eq!(check(&rules, "https://192.0.2.7/a.js"), Some(true));
    assert_eq!(check(&rules, "https://rx.test/ad.js"), Some(true));
    assert_eq!(check(&rules, "https://cdn.test/app.js"), None);
    // User rules apply everywhere.
    assert!(rules.lookup("custom.test").unwrap().blocked);

    let page = rules.cosmetic_page("site.test");
    assert!(page.css.contains(".banner"));
    assert!(!page.css.contains(".dns-banner"));

    // The old single-text form still applies everywhere.
    let mixed = Rules::compile("||ads.test^\nsite.test##.banner", "");
    assert!(mixed.lookup("ads.test").unwrap().blocked);
    assert!(mixed.cosmetic_page("site.test").css.contains(".banner"));
}
