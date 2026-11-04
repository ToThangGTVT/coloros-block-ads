use blockads_core::filter::Rules;
use std::time::Instant;

// $popup and bare or regex $removeparam (URL Tracking) are what adblock-rust skips.
// Lists from AdGuard's browser-extension catalog in uBO syntax, e.g.
// https://filters.adtidy.org/extension/ublock/filters/{2,3,17,18,...}.txt, saved as `<id>.txt`.
#[test]
#[ignore = "Requires BLOCKADS_LISTS pointing at downloaded filter lists"]
fn adguard_extension_lists_compile() {
    let dir = std::env::var("BLOCKADS_LISTS").unwrap();
    let mut paths: Vec<_> = std::fs::read_dir(dir)
        .unwrap()
        .map(|entry| entry.unwrap().path())
        .filter(|path| path.extension().is_some_and(|e| e == "txt"))
        .collect();
    paths.sort();
    let mut all = String::new();
    for path in &paths {
        let text = std::fs::read_to_string(path).unwrap();
        let started = Instant::now();
        let rules = Rules::compile_lists("", &text, "");
        let report = &rules.report;
        eprintln!(
            "{}: {} bytes, {} loaded, {} ignored, {:?}",
            path.file_name().unwrap().to_string_lossy(),
            text.len(),
            report.loaded,
            report.ignored,
            started.elapsed()
        );
        assert!(
            report.ignored * 10 < report.loaded + report.ignored,
            "more than 10% of the rules are unsupported"
        );
        all.push_str(&text);
        all.push('\n');
    }
    let started = Instant::now();
    let rules = Rules::compile_lists("", &all, "");
    eprintln!(
        "together: {} loaded, {} ignored, {:?}",
        rules.report.loaded,
        rules.report.ignored,
        started.elapsed()
    );
}
