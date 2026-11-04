use blockads_core::filter::{resources, Context, Rules};

const SCRIPTLETS: &str = r#"{"version":"test","scriptlets":[
 {"names":["set-constant","set-constant.js","ubo-set-constant.js","set.js"],
  "scriptlet":"function setConstant(source,args){window[args[0]]=args[1]}"},
 {"names":["trusted-set-cookie"],
  "scriptlet":"function trustedSetCookie(source,args){document.cookie=args[0]}"},
 {"names":["broken"],"scriptlet":"(function(){})"}]}"#;
const REDIRECTS: &str = r#"[
 {"title":"noopjs","aliases":["noop.js","blank-js"],"isBlocking":false,
  "contentType":"application/javascript;base64","content":"KGZ1bmN0aW9uKCl7fSkoKTs="},
 {"title":"1x1-transparent.gif","aliases":["1x1.gif"],"isBlocking":false,
  "contentType":"image/gif;base64","content":"R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw=="},
 {"title":"click2load.html","aliases":[],"isBlocking":true,
  "contentType":"text/html;base64","content":"PGh0bWw+"}]"#;

fn check(rules: &Rules, url: &str, kind: &str) -> Option<blockads_core::filter::Matched> {
    let domain = url.split('/').nth(2).unwrap();
    rules.check(
        domain,
        &Context {
            url,
            source: "https://site.test/",
            kind,
            method: "GET",
        },
        &[],
    )
}

// One test: resources are process-global.
#[test]
fn scriptlets_redirects_and_removeparam() {
    resources::install("", "").unwrap();
    let rules = Rules::compile(
        "site.test##+js(set-constant, adsEnabled, false)\nsite.test##+js(ubo-set-constant.js, b, 1)\nsite.test##+js(trusted-set-cookie, a=b)\n||ads.test/lib.js$script,redirect=noopjs\n||ads.test/pixel$image,redirect=1x1.gif\n*$image,redirect-rule=1x1.gif,domain=site.test\n||tracker.test^\n$removeparam=utm_source",
        "other.test##+js(set, flag, \"</script><script>alert(1)//\")",
    );
    assert!(
        rules.report.errors.is_empty(),
        "{:?}",
        rules.report.errors.first().map(|e| &e.message)
    );

    // Without resources nothing is injected; installing them refreshes cached pages.
    assert!(rules.cosmetic_page("site.test").scriptlets.is_empty());
    let loaded = resources::install(SCRIPTLETS, REDIRECTS).unwrap();
    assert_eq!(
        (loaded.scriptlets, loaded.redirects, loaded.skipped),
        (2, 2, 2),
        "the non-function scriptlet and the blocking redirect are skipped"
    );
    let page = rules.cosmetic_page("site.test");
    assert!(page
        .scriptlets
        .contains("function blockads_set_constant(...args)"));
    assert!(page
        .scriptlets
        .contains("blockads_set_constant(\"adsEnabled\", \"false\")"));
    assert!(
        page.scriptlets
            .contains("blockads_set_constant(\"b\", \"1\")"),
        "uBO alias"
    );
    assert!(
        !page.scriptlets.contains("trustedSetCookie"),
        "trusted scriptlets need a permission lists do not have"
    );
    let other = rules.cosmetic_page("other.test");
    assert!(other.scriptlets.contains("blockads_set_constant(\"flag\""));
    assert!(!other.scriptlets.contains("</"), "{}", other.scriptlets);

    let js = check(&rules, "https://ads.test/lib.js", "script").unwrap();
    assert!(js.blocked);
    assert_eq!(
        js.redirect.as_deref(),
        Some("data:application/javascript;base64,KGZ1bmN0aW9uKCl7fSkoKTs=")
    );
    let pixel = check(&rules, "https://ads.test/pixel", "image").unwrap();
    assert!(pixel
        .redirect
        .unwrap()
        .starts_with("data:image/gif;base64,"));
    // redirect-rule only changes how an already blocked request is answered.
    let tracked = check(&rules, "https://tracker.test/t.gif", "image").unwrap();
    assert!(tracked.blocked);
    assert!(tracked
        .redirect
        .unwrap()
        .starts_with("data:image/gif;base64,"));
    assert!(check(&rules, "https://cdn.test/photo.jpg", "image").is_none());

    let clean = check(
        &rules,
        "https://news.test/a?id=1&utm_source=feed",
        "document",
    )
    .unwrap();
    assert!(!clean.blocked);
    assert_eq!(clean.rewritten.as_deref(), Some("https://news.test/a?id=1"));

    assert!(
        resources::install("{", "").is_err(),
        "malformed input is rejected"
    );
    assert!(
        !rules.cosmetic_page("site.test").scriptlets.is_empty(),
        "previous set stays active"
    );
    resources::install("", "").unwrap();
    assert!(rules.cosmetic_page("site.test").scriptlets.is_empty());
}

// Real AdGuard Scriptlets files, e.g. from
// https://cdn.jsdelivr.net/npm/@adguard/scriptlets@2/dist/{scriptlets.corelibs.json,redirects.json}
#[test]
#[ignore = "Requires BLOCKADS_RESOURCES pointing at downloaded AdGuard Scriptlets files"]
fn adguard_scriptlets_files_install() {
    let dir = std::env::var("BLOCKADS_RESOURCES").unwrap();
    let read = |name: &str| std::fs::read_to_string(format!("{dir}/{name}")).unwrap();
    let loaded = resources::install(&read("corelibs.json"), &read("redirects.json")).unwrap();
    eprintln!("{loaded:?}");
    assert!(
        loaded.scriptlets > 90 && loaded.redirects > 40,
        "{loaded:?}"
    );
    let rules = Rules::compile(
        "site.test##+js(set-constant, ads, true)\nsite.test##+js(aopr, adblock)\nsite.test##+js(ubo-nowebrtc.js)\n||ads.test/gpt.js$script,redirect=googletagservices_gpt.js",
        "",
    );
    let page = rules.cosmetic_page("site.test");
    assert_eq!(
        page.scriptlets.matches("try {").count(),
        3,
        "{}",
        &page.scriptlets[..200]
    );
    let gpt = check(&rules, "https://ads.test/gpt.js", "script").unwrap();
    assert!(gpt.redirect.unwrap().len() > 1000, "GPT stub");
    resources::install("", "").unwrap();
}
