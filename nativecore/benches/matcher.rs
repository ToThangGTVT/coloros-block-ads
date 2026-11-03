use blockads_core::filter::Rules;
fn main() {
    let text = (0..500_000)
        .map(|i| format!("0.0.0.0 ads{i:06}.example.test\n"))
        .collect::<String>();
    let started = std::time::Instant::now();
    let rules = Rules::compile(&text, "@@||allowed.example^");
    drop(text);
    let compile = started.elapsed();
    let started = std::time::Instant::now();
    for i in 0..1_000_000u64 {
        let domain = format!("ads{:06}.example.test", (i * 65537) % 750_000);
        std::hint::black_box(rules.matches(std::hint::black_box(&domain), None, &[]));
    }
    let micros = started.elapsed().as_secs_f64(); // million lookups: elapsed seconds == microseconds/lookup
    let mut usage = std::mem::MaybeUninit::<libc::rusage>::uninit();
    let rss = unsafe {
        libc::getrusage(libc::RUSAGE_SELF, usage.as_mut_ptr());
        usage.assume_init().ru_maxrss as f64
    };
    let peak_mb = rss
        / if cfg!(target_os = "macos") {
            1024.0 * 1024.0
        } else {
            1024.0
        };
    println!("500k rules: compile={compile:?}, lookup={micros:.3} us, peak RSS={peak_mb:.1} MiB");
    assert!(micros < 5.0, "lookup target missed");
    assert!(peak_mb < 80.0, "memory target missed");
}
