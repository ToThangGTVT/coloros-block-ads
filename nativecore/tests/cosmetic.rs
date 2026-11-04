use blockads_core::filter::Rules;
use std::sync::Arc;

#[test]
fn domain_scoping_exclusions_exceptions_and_cache() {
    let rules = Rules::compile(
        "example.test,~private.example.test##.banner\nexample.test##div > .ad[data-kind=\"sponsor\"]\nexample.test##.kept\nexample.test#@#.kept\nsub.example.test#@#.banner\n##.generic",
        "example.test###custom-ad",
    );
    assert!(rules.report.errors.is_empty());
    assert_eq!(rules.report.ignored, 0);
    let css = rules.cosmetic_css("example.test");
    assert!(css.contains(".banner{display:none!important}"));
    assert!(css.contains("div > .ad[data-kind=\"sponsor\"]"));
    assert!(css.contains("#custom-ad"));
    assert!(!css.contains(".kept"));
    assert!(!rules.cosmetic_css("sub.example.test").contains(".banner"));
    assert!(!rules
        .cosmetic_css("private.example.test")
        .contains(".banner"));
    assert_eq!(
        rules.cosmetic_css("notexample.test").as_ref(),
        ".generic{display:none!important}\n"
    );
    assert!(Arc::ptr_eq(&css, &rules.cosmetic_css("example.test")));
    let updated = Rules::compile("example.test##.new", "");
    assert!(!updated.cosmetic_css("example.test").contains(".banner"));
}

#[test]
fn css_cannot_escape_into_html_or_execute_code() {
    for selector in [
        ".ad</style><script>alert(1)</script>",
        ".ad{background:url(https://evil.test)}",
        ".ad;body",
        ".ad\\3c style",
        ".ad/*comment*/",
        "+js(abort-on-property-read, ads)",
        "div:has(.ad)",
        "div:has-text(ad)",
        "[x=\"</style>\"]",
        "@import 'x'",
        "",
    ] {
        let rules = Rules::compile("", &format!("example.test##{selector}"));
        assert!(!rules.report.errors.is_empty(), "accepted {selector}");
        assert!(rules.cosmetic_css("example.test").is_empty());
    }
    let rules = Rules::compile(
        "",
        "~cafef.vn##[id^=\"adm-slot\"]\ngenk.vn#@#.allowed\n##.allowed",
    );
    assert!(rules.report.errors.is_empty());
    assert!(rules
        .cosmetic_css("m.genk.vn")
        .contains("[id^=\"adm-slot\"]"));
    assert!(!rules.cosmetic_css("m.genk.vn").contains(".allowed"));
    assert!(!rules.cosmetic_css("m.cafef.vn").contains("adm-slot"));
}

#[test]
fn cached_styles_have_bounded_size_and_duplicate_rules_are_collapsed() {
    let list = (0..6000)
        .map(|n| format!("example.test##.advertisement-{n}\n"))
        .collect::<String>();
    let rules = Rules::compile(&list, "example.test##.advertisement-0");
    let css = rules.cosmetic_css("example.test");
    assert!(css.len() <= 64 * 1024);
    assert_eq!(css.matches(".advertisement-0{").count(), 1);
    for n in 0..200 {
        rules.cosmetic_css(&format!("sub{n}.example.test"));
    }
    assert_eq!(css.as_ref(), rules.cosmetic_css("example.test").as_ref());
}
