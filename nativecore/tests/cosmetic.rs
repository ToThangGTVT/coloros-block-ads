use blockads_core::filter::Rules;
use std::sync::Arc;

#[test]
fn domain_scoping_exclusions_exceptions_and_cache() {
    let rules = Rules::compile(
        "example.test,~private.example.test##.banner\nexample.test##div > .ad[data-kind=\"sponsor\"]\nexample.test##.kept\nexample.test#@#.kept\nsub.example.test#@#.banner\n##.generic\n##[id^=\"div-gpt-ad\"]",
        "example.test###custom-ad",
    );
    assert!(rules.report.errors.is_empty());
    assert_eq!(rules.report.ignored, 0);
    let page = rules.cosmetic_page("example.test");
    let css = &page.css;
    assert!(css.contains(".banner{display:none!important}"));
    assert!(css.contains(".ad[data-kind"));
    assert!(css.contains("#custom-ad"));
    assert!(!css.contains(".kept"));
    assert!(
        css.contains("[id^=\"div-gpt-ad\"]"),
        "non class/id generic rules apply to every page"
    );
    assert!(
        !css.contains(".generic"),
        "class/id generic rules are looked up per page"
    );
    assert!(!rules
        .cosmetic_page("sub.example.test")
        .css
        .contains(".banner"));
    assert!(!rules
        .cosmetic_page("private.example.test")
        .css
        .contains(".banner"));
    let other = rules.cosmetic_page("notexample.test");
    assert!(other.generic);
    assert_eq!(
        rules.generic_css(&other, &["generic", "unrelated"], &["x"]),
        ".generic{display:none!important}\n"
    );
    assert!(Arc::ptr_eq(&page, &rules.cosmetic_page("example.test")));
    let updated = Rules::compile("example.test##.new", "");
    assert!(!updated
        .cosmetic_page("example.test")
        .css
        .contains(".banner"));
}

#[test]
fn modern_css_and_procedural_rules_are_supported() {
    let rules = Rules::compile(
        "example.test##div:has(> .ad)\nexample.test#?#aside:-abp-has(.sponsor)\nexample.test##.box:has-text(Quảng cáo)\nexample.test##.popup:remove()\nexample.test##.sticky:style(position: static !important)\nexample.test##.ad:upward(2)",
        "",
    );
    assert!(
        rules.report.errors.is_empty(),
        "{:?}",
        rules.report.errors.first().map(|e| &e.message)
    );
    let page = rules.cosmetic_page("example.test");
    assert!(page.css.contains("div:has(> .ad){display:none!important}"));
    assert!(
        page.css
            .contains("aside:has(.sponsor){display:none!important}"),
        "{}\n{}",
        page.css,
        page.procedural
    );
    assert!(page.css.contains(".sticky{position: static !important}"));
    for operator in ["has-text", "\"remove\"", "upward"] {
        assert!(page.procedural.contains(operator), "{}", page.procedural);
    }
    assert!(page.procedural.starts_with('['));
}

#[test]
fn css_cannot_escape_into_html_or_execute_code() {
    for (selector, accepted) in [
        (".ad{background:url(https://evil.test)}", false),
        (".ad:style(background: url(https://evil.test))", false),
        (".ad:style(color: red; /* x */)", false),
        ("@import 'x'", false),
        ("", false),
        ("[x=\"</style><script>alert(1)</script>\"]", true),
    ] {
        let rules = Rules::compile("", &format!("example.test##{selector}"));
        assert_eq!(rules.report.errors.is_empty(), accepted, "{selector}");
        let page = rules.cosmetic_page("example.test");
        assert!(!page.css.contains('<'), "{}", page.css);
        assert!(!page.css.contains("url("));
    }
    let rules = Rules::compile("", "example.test##[title=\"</script>\"]:has-text(x)");
    assert!(!rules.cosmetic_page("example.test").procedural.contains('<'));
    assert!(
        !Rules::compile("", "example.test##+js(set-constant, ads, false)")
            .report
            .errors
            .is_empty()
    );
    let rules = Rules::compile(
        "",
        "~cafef.vn##[id^=\"adm-slot\"]\ngenk.vn#@#.allowed\n##.allowed",
    );
    assert!(rules.report.errors.is_empty());
    assert!(rules
        .cosmetic_page("m.genk.vn")
        .css
        .contains("[id^=\"adm-slot\"]"));
    let genk = rules.cosmetic_page("m.genk.vn");
    assert!(rules.generic_css(&genk, &["allowed"], &[]).is_empty());
    assert!(!rules.cosmetic_page("m.cafef.vn").css.contains("adm-slot"));
}

#[test]
fn generichide_disables_generic_rules_only() {
    let rules = Rules::compile(
        "##.generic\n##[data-ad]\nexample.test##.own\n@@||example.test^$generichide",
        "",
    );
    assert!(rules.report.errors.is_empty());
    let page = rules.cosmetic_page("example.test");
    assert!(!page.generic);
    assert!(page.css.contains(".own"));
    assert!(!page.css.contains("data-ad"));
    assert!(rules.generic_css(&page, &["generic"], &[]).is_empty());
    let other = rules.cosmetic_page("other.test");
    assert!(other.generic && other.css.contains("data-ad"));
}

#[test]
fn large_lists_are_not_truncated_and_duplicates_collapse() {
    let list = (0..6000)
        .map(|n| format!("example.test##.advertisement-{n}\n"))
        .collect::<String>();
    let rules = Rules::compile(&list, "example.test##.advertisement-0");
    let page = rules.cosmetic_page("example.test");
    assert_eq!(page.css.matches(".advertisement-0{").count(), 1);
    assert!(page.css.contains(".advertisement-5999{"));
    for n in 0..200 {
        rules.cosmetic_page(&format!("sub{n}.example.test"));
    }
    assert_eq!(page.css, rules.cosmetic_page("example.test").css);
}
