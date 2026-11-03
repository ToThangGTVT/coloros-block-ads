# Third-party dependencies

Phase 0 license review, recorded before integration. Every new network dependency
and its transitive dependencies must be reviewed before later phases use it.

| Dependency | Version | License | Source |
| --- | --- | --- | --- |
| COUI ui/core/preference/icons/blur/navigation3-ui/shader/squircle | 1.1.0 | Apache-2.0 | https://github.com/suqi8/coui/blob/main/LICENSE |
| AndroidX Compose, Activity, Lifecycle, Navigation3 runtime, Room, DataStore, WorkManager, Paging | version catalog | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt |
| Kotlin, Coroutines, Serialization | version catalog | Apache-2.0 | https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt |
| Rust standard library | 1.99.1 | MIT OR Apache-2.0 (plus bundled third-party notices) | https://github.com/rust-lang/rust/blob/1.99.1/COPYRIGHT |
| jni | 0.21.1 | MIT OR Apache-2.0 | https://github.com/jni-rs/jni-rs/tree/v0.21.1 |
| Dagger / Hilt | 2.60.1 | Apache-2.0 | https://github.com/google/dagger/blob/master/LICENSE.txt |
| MaterialKolor / Material Color Utilities, via COUI | COUI transitive | Apache-2.0 | https://github.com/jordond/MaterialKolor/blob/main/LICENSE |
| JUnit | 4.13.2 | EPL-1.0 | https://github.com/junit-team/junit4/blob/main/LICENSE-junit.txt |

License texts for COUI (including upstream Miuix attribution) and JNI are in
`licenses/`. COUI has transitive Material window-size utilities; this application
does not use Material 3 display components.

Network stack, DNS and rule-engine crates are not selected in Phase 0. Their
licenses and transitive dependencies require review before Phase 1/2. Native code
uses Rust/JNI as requested; no Go runtime or gomobile is distributed.

The blocklist picker reads AdGuard Home's registry
(https://adguardteam.github.io/HostlistsRegistry/assets/filters.json, GPL-3.0,
https://github.com/AdguardTeam/HostlistsRegistry) at runtime. It is not bundled in
the APK; the user downloads each list, which keeps its own license.

## Locked Rust dependency inventory

Reviewed from Cargo.lock manifests, including host/target-specific crates.

| Crate | Version | License | Source |
| --- | --- | --- | --- |
| aho-corasick | 1.1.5 | Unlicense OR MIT | https://github.com/BurntSushi/aho-corasick |
| arrayvec | 0.7.8 | MIT OR Apache-2.0 | https://github.com/bluss/arrayvec |
| asn1-rs | 0.7.2 | MIT OR Apache-2.0 | https://github.com/rusticata/asn1-rs.git |
| asn1-rs-derive | 0.6.0 | MIT OR Apache-2.0 | https://github.com/rusticata/asn1-rs.git |
| asn1-rs-impl | 0.2.0 | MIT/Apache-2.0 | https://github.com/rusticata/asn1-rs.git |
| async-trait | 0.1.92 | MIT OR Apache-2.0 | https://github.com/dtolnay/async-trait |
| atomic-waker | 1.1.2 | Apache-2.0 OR MIT | https://github.com/smol-rs/atomic-waker |
| autocfg | 1.5.1 | Apache-2.0 OR MIT | https://github.com/cuviper/autocfg |
| bit-vec | 0.9.1 | Apache-2.0 OR MIT | https://github.com/contain-rs/bit-vec |
| bitflags | 1.3.2 | MIT/Apache-2.0 | https://github.com/bitflags/bitflags |
| bitflags | 2.13.2 | MIT OR Apache-2.0 | https://github.com/bitflags/bitflags |
| bumpalo | 3.20.3 | MIT OR Apache-2.0 | https://github.com/fitzgen/bumpalo |
| byteorder | 1.5.0 | Unlicense OR MIT | https://github.com/BurntSushi/byteorder |
| bytes | 1.12.1 | MIT | https://github.com/tokio-rs/bytes |
| cc | 1.11.0 | MIT OR Apache-2.0 | https://github.com/rust-lang/cc-rs |
| cesu8 | 1.1.0 | Apache-2.0/MIT | https://github.com/emk/cesu8-rs |
| cfg-if | 1.0.5 | MIT OR Apache-2.0 | https://github.com/rust-lang/cfg-if |
| cfg_aliases | 0.2.2 | MIT | https://github.com/katharostech/cfg_aliases |
| chacha20 | 0.10.2 | MIT OR Apache-2.0 | https://github.com/RustCrypto/stream-ciphers |
| combine | 4.6.9 | MIT | https://github.com/Marwes/combine |
| cpufeatures | 0.3.1 | MIT OR Apache-2.0 | https://github.com/RustCrypto/utils |
| critical-section | 1.2.0 | MIT OR Apache-2.0 | https://github.com/rust-embedded/critical-section |
| crossbeam-channel | 0.5.17 | MIT OR Apache-2.0 | https://github.com/crossbeam-rs/crossbeam |
| crossbeam-epoch | 0.9.21 | MIT OR Apache-2.0 | https://github.com/crossbeam-rs/crossbeam |
| crossbeam-utils | 0.8.23 | MIT OR Apache-2.0 | https://github.com/crossbeam-rs/crossbeam |
| data-encoding | 2.11.1 | MIT | https://github.com/ia0/data-encoding |
| defmt | 0.3.101 | MIT OR Apache-2.0 | https://github.com/knurling-rs/defmt |
| defmt | 1.2.0 | MIT OR Apache-2.0 | https://github.com/knurling-rs/defmt |
| defmt-macros | 1.2.0 | MIT OR Apache-2.0 | https://github.com/knurling-rs/defmt |
| defmt-parser | 1.1.0 | MIT OR Apache-2.0 | https://github.com/knurling-rs/defmt |
| der-parser | 10.0.0 | MIT OR Apache-2.0 | https://github.com/rusticata/der-parser.git |
| deranged | 0.5.9 | MIT OR Apache-2.0 | https://github.com/jhpratt/deranged |
| displaydoc | 0.2.7 | MIT OR Apache-2.0 | https://github.com/yaahc/displaydoc |
| either | 1.19.0 | MIT OR Apache-2.0 | https://github.com/rayon-rs/either |
| equivalent | 1.0.3 | Apache-2.0 OR MIT | https://github.com/indexmap-rs/equivalent |
| etherparse | 0.16.0 | MIT OR Apache-2.0 | https://github.com/JulianSchmid/etherparse |
| find-msvc-tools | 0.1.15 | MIT OR Apache-2.0 | https://github.com/rust-lang/cc-rs |
| fnv | 1.0.7 | Apache-2.0 / MIT | https://github.com/servo/rust-fnv |
| form_urlencoded | 1.2.2 | MIT OR Apache-2.0 | https://github.com/servo/rust-url |
| futures | 0.3.34 | MIT OR Apache-2.0 | https://github.com/rust-lang/futures-rs |
| futures-channel | 0.3.34 | MIT OR Apache-2.0 | https://github.com/rust-lang/futures-rs |
| futures-core | 0.3.34 | MIT OR Apache-2.0 | https://github.com/rust-lang/futures-rs |
| futures-executor | 0.3.34 | MIT OR Apache-2.0 | https://github.com/rust-lang/futures-rs |
| futures-io | 0.3.34 | MIT OR Apache-2.0 | https://github.com/rust-lang/futures-rs |
| futures-macro | 0.3.34 | MIT OR Apache-2.0 | https://github.com/rust-lang/futures-rs |
| futures-sink | 0.3.34 | MIT OR Apache-2.0 | https://github.com/rust-lang/futures-rs |
| futures-task | 0.3.34 | MIT OR Apache-2.0 | https://github.com/rust-lang/futures-rs |
| futures-util | 0.3.34 | MIT OR Apache-2.0 | https://github.com/rust-lang/futures-rs |
| getrandom | 0.2.17 | MIT OR Apache-2.0 | https://github.com/rust-random/getrandom |
| getrandom | 0.4.3 | MIT OR Apache-2.0 | https://github.com/rust-random/getrandom |
| h2 | 0.4.20 | MIT | https://github.com/hyperium/h2 |
| hash32 | 0.3.1 | MIT OR Apache-2.0 | https://github.com/japaric/hash32 |
| hashbrown | 0.17.1 | MIT OR Apache-2.0 | https://github.com/rust-lang/hashbrown |
| heapless | 0.8.0 | MIT OR Apache-2.0 | https://github.com/rust-embedded/heapless |
| hickory-net | 0.26.3 | MIT OR Apache-2.0 | https://github.com/hickory-dns/hickory-dns |
| hickory-proto | 0.26.3 | MIT OR Apache-2.0 | https://github.com/hickory-dns/hickory-dns |
| hickory-resolver | 0.26.3 | MIT OR Apache-2.0 | https://github.com/hickory-dns/hickory-dns |
| http | 1.5.0 | MIT OR Apache-2.0 | https://github.com/hyperium/http |
| http-body | 1.1.0 | MIT | https://github.com/hyperium/http-body |
| http-body-util | 0.1.5 | MIT | https://github.com/hyperium/http-body |
| httparse | 1.10.1 | MIT OR Apache-2.0 | https://github.com/seanmonstar/httparse |
| httpdate | 1.0.3 | MIT OR Apache-2.0 | https://github.com/pyfisch/httpdate |
| hyper | 1.12.0 | MIT | https://github.com/hyperium/hyper |
| hyper-util | 0.1.21 | MIT | https://github.com/hyperium/hyper-util |
| icu_collections | 2.4.0 | Unicode-3.0 | https://github.com/unicode-org/icu4x |
| icu_locale_core | 2.4.0 | Unicode-3.0 | https://github.com/unicode-org/icu4x |
| icu_normalizer | 2.4.0 | Unicode-3.0 | https://github.com/unicode-org/icu4x |
| icu_normalizer_data | 2.4.0 | Unicode-3.0 | https://github.com/unicode-org/icu4x |
| icu_properties | 2.4.0 | Unicode-3.0 | https://github.com/unicode-org/icu4x |
| icu_properties_data | 2.4.0 | Unicode-3.0 | https://github.com/unicode-org/icu4x |
| icu_provider | 2.4.0 | Unicode-3.0 | https://github.com/unicode-org/icu4x |
| idna | 1.1.0 | MIT OR Apache-2.0 | https://github.com/servo/rust-url/ |
| idna_adapter | 1.2.2 | Apache-2.0 OR MIT | https://github.com/hsivonen/idna_adapter |
| indexmap | 2.14.2 | Apache-2.0 OR MIT | https://github.com/indexmap-rs/indexmap |
| ipnet | 2.12.2 | MIT OR Apache-2.0 | https://github.com/krisprice/ipnet |
| itoa | 1.0.18 | MIT OR Apache-2.0 | https://github.com/dtolnay/itoa |
| jni | 0.21.1 | MIT/Apache-2.0 | https://github.com/jni-rs/jni-rs |
| jni | 0.22.4 | MIT OR Apache-2.0 | https://github.com/jni-rs/jni-rs |
| jni-macros | 0.22.4 | MIT OR Apache-2.0 | https://github.com/jni-rs/jni-rs |
| jni-sys | 0.3.1 | MIT OR Apache-2.0 | https://github.com/jni-rs/jni-sys |
| jni-sys | 0.4.1 | MIT OR Apache-2.0 | https://github.com/jni-rs/jni-sys |
| jni-sys-macros | 0.4.1 | MIT OR Apache-2.0 | https://github.com/jni-rs/jni-sys |
| js-sys | 0.3.107 | MIT OR Apache-2.0 | https://github.com/wasm-bindgen/wasm-bindgen/tree/master/crates/js-sys |
| lazy_static | 1.5.1 | MIT OR Apache-2.0 | https://github.com/rust-lang-nursery/lazy-static.rs |
| libc | 0.2.191 | MIT OR Apache-2.0 | https://github.com/rust-lang/libc |
| litemap | 0.8.3 | Unicode-3.0 | https://github.com/unicode-org/icu4x |
| lock_api | 0.4.14 | MIT OR Apache-2.0 | https://github.com/Amanieu/parking_lot |
| log | 0.4.35 | MIT OR Apache-2.0 | https://github.com/rust-lang/log |
| lru-slab | 0.1.3 | MIT OR Apache-2.0 OR Zlib | https://github.com/Ralith/lru-slab |
| managed | 0.8.0 | 0BSD | https://github.com/m-labs/rust-managed.git |
| memchr | 2.8.3 | Unlicense OR MIT | https://github.com/BurntSushi/memchr |
| minimal-lexical | 0.2.1 | MIT/Apache-2.0 | https://github.com/Alexhuszagh/minimal-lexical |
| mio | 1.2.4 | MIT | https://github.com/tokio-rs/mio |
| moka | 0.12.16 | (MIT OR Apache-2.0) AND Apache-2.0 | https://github.com/moka-rs/moka |
| netstack-smoltcp | 0.2.4 | MIT OR Apache-2.0 | https://github.com/cavivie/netstack-smoltcp |
| nom | 7.1.3 | MIT | https://github.com/Geal/nom |
| num-bigint | 0.4.8 | MIT OR Apache-2.0 | https://github.com/rust-num/num-bigint |
| num-conv | 0.2.2 | MIT OR Apache-2.0 | https://github.com/jhpratt/num-conv |
| num-integer | 0.1.47 | MIT OR Apache-2.0 | https://github.com/rust-num/num-integer |
| num-traits | 0.2.19 | MIT OR Apache-2.0 | https://github.com/rust-num/num-traits |
| oid-registry | 0.8.1 | MIT OR Apache-2.0 | https://github.com/rusticata/oid-registry.git |
| once_cell | 1.21.4 | MIT OR Apache-2.0 | https://github.com/matklad/once_cell |
| parking_lot | 0.12.5 | MIT OR Apache-2.0 | https://github.com/Amanieu/parking_lot |
| parking_lot_core | 0.9.12 | MIT OR Apache-2.0 | https://github.com/Amanieu/parking_lot |
| percent-encoding | 2.3.2 | MIT OR Apache-2.0 | https://github.com/servo/rust-url/ |
| pin-project-lite | 0.2.17 | Apache-2.0 OR MIT | https://github.com/taiki-e/pin-project-lite |
| portable-atomic | 1.15.0 | Apache-2.0 OR MIT | https://github.com/taiki-e/portable-atomic |
| potential_utf | 0.1.6 | Unicode-3.0 | https://github.com/unicode-org/icu4x |
| powerfmt | 0.2.1 | MIT OR Apache-2.0 | https://github.com/jhpratt/powerfmt |
| ppv-lite86 | 0.2.21 | MIT OR Apache-2.0 | https://github.com/cryptocorrosion/cryptocorrosion |
| prefix-trie | 0.8.4 | MIT OR Apache-2.0 | https://github.com/tiborschneider/prefix-trie |
| proc-macro2 | 1.0.107 | MIT OR Apache-2.0 | https://github.com/dtolnay/proc-macro2 |
| quinn | 0.11.12 | MIT OR Apache-2.0 | https://github.com/quinn-rs/quinn |
| quinn-proto | 0.11.19 | MIT OR Apache-2.0 | https://github.com/quinn-rs/quinn |
| quinn-udp | 0.5.16 | MIT OR Apache-2.0 | https://github.com/quinn-rs/quinn |
| quote | 1.0.47 | MIT OR Apache-2.0 | https://github.com/dtolnay/quote |
| r-efi | 6.0.0 | MIT OR Apache-2.0 OR LGPL-2.1-or-later | https://github.com/r-efi/r-efi |
| rand | 0.10.3 | MIT OR Apache-2.0 | https://github.com/rust-random/rand |
| rand | 0.8.8 | MIT OR Apache-2.0 | https://github.com/rust-random/rand |
| rand_chacha | 0.3.1 | MIT OR Apache-2.0 | https://github.com/rust-random/rand |
| rand_core | 0.10.1 | MIT OR Apache-2.0 | https://github.com/rust-random/rand_core |
| rand_core | 0.6.4 | MIT OR Apache-2.0 | https://github.com/rust-random/rand |
| rand_pcg | 0.10.2 | MIT OR Apache-2.0 | https://github.com/rust-random/rngs |
| rcgen | 0.14.10 | MIT OR Apache-2.0 | https://github.com/rustls/rcgen |
| redox_syscall | 0.5.18 | MIT | https://gitlab.redox-os.org/redox-os/syscall |
| regex | 1.13.1 | MIT OR Apache-2.0 | https://github.com/rust-lang/regex |
| regex-automata | 0.4.18 | MIT OR Apache-2.0 | https://github.com/rust-lang/regex |
| regex-syntax | 0.8.11 | MIT OR Apache-2.0 | https://github.com/rust-lang/regex |
| ring | 0.17.14 | Apache-2.0 AND ISC | https://github.com/briansmith/ring |
| rustc-hash | 2.1.3 | Apache-2.0 OR MIT | https://github.com/rust-lang/rustc-hash |
| rustc_version | 0.4.1 | MIT OR Apache-2.0 | https://github.com/djc/rustc-version-rs |
| rusticata-macros | 4.1.0 | MIT/Apache-2.0 | https://github.com/rusticata/rusticata-macros.git |
| rustls | 0.23.46 | Apache-2.0 OR ISC OR MIT | https://github.com/rustls/rustls |
| rustls-pki-types | 1.15.1 | MIT OR Apache-2.0 | https://github.com/rustls/pki-types |
| rustls-webpki | 0.103.16 | ISC | https://github.com/rustls/webpki |
| rustversion | 1.0.23 | MIT OR Apache-2.0 | https://github.com/dtolnay/rustversion |
| same-file | 1.0.6 | Unlicense/MIT | https://github.com/BurntSushi/same-file |
| scopeguard | 1.2.0 | MIT OR Apache-2.0 | https://github.com/bluss/scopeguard |
| semver | 1.0.28 | MIT OR Apache-2.0 | https://github.com/dtolnay/semver |
| serde | 1.0.229 | MIT OR Apache-2.0 | https://github.com/serde-rs/serde |
| serde_core | 1.0.229 | MIT OR Apache-2.0 | https://github.com/serde-rs/serde |
| serde_derive | 1.0.229 | MIT OR Apache-2.0 | https://github.com/serde-rs/serde |
| serde_json | 1.0.154 | MIT OR Apache-2.0 | https://github.com/serde-rs/json |
| shlex | 2.0.1 | MIT OR Apache-2.0 | https://github.com/comex/rust-shlex |
| simd_cesu8 | 1.2.0 | Apache-2.0 OR MIT | https://github.com/seancroach/simd_cesu8 |
| simdutf8 | 0.1.5 | MIT OR Apache-2.0 | https://github.com/rusticstuff/simdutf8 |
| slab | 0.4.12 | MIT | https://github.com/tokio-rs/slab |
| smallvec | 1.16.4 | MIT OR Apache-2.0 | https://github.com/servo/rust-smallvec |
| smoltcp | 0.12.0 | 0BSD | https://github.com/smoltcp-rs/smoltcp.git |
| socket2 | 0.6.5 | MIT OR Apache-2.0 | https://github.com/rust-lang/socket2 |
| spin | 0.9.9 | MIT | https://github.com/mvdnes/spin-rs.git |
| stable_deref_trait | 1.2.1 | MIT OR Apache-2.0 | https://github.com/storyyeller/stable_deref_trait |
| subtle | 2.6.1 | BSD-3-Clause | https://github.com/dalek-cryptography/subtle |
| syn | 2.0.119 | MIT OR Apache-2.0 | https://github.com/dtolnay/syn |
| syn | 3.0.9 | MIT OR Apache-2.0 | https://github.com/dtolnay/syn |
| synstructure | 0.13.2 | MIT | https://github.com/mystor/synstructure |
| synstructure | 0.14.0 | MIT | https://github.com/mystor/synstructure |
| tagptr | 0.2.0 | MIT/Apache-2.0 | https://github.com/oliver-giersch/tagptr.git |
| thiserror | 1.0.69 | MIT OR Apache-2.0 | https://github.com/dtolnay/thiserror |
| thiserror | 2.0.21 | MIT OR Apache-2.0 | https://github.com/dtolnay/thiserror |
| thiserror-impl | 1.0.69 | MIT OR Apache-2.0 | https://github.com/dtolnay/thiserror |
| thiserror-impl | 2.0.21 | MIT OR Apache-2.0 | https://github.com/dtolnay/thiserror |
| time | 0.3.55 | MIT OR Apache-2.0 | https://github.com/time-rs/time |
| time-core | 0.1.9 | MIT OR Apache-2.0 | https://github.com/time-rs/time |
| time-macros | 0.2.32 | MIT OR Apache-2.0 | https://github.com/time-rs/time |
| tinystr | 0.8.5 | Unicode-3.0 | https://github.com/unicode-org/icu4x |
| tinyvec | 1.13.3 | Zlib OR Apache-2.0 OR MIT | https://github.com/Lokathor/tinyvec |
| tokio | 1.54.1 | MIT | https://github.com/tokio-rs/tokio |
| tokio-macros | 2.7.2 | MIT | https://github.com/tokio-rs/tokio |
| tokio-rustls | 0.26.6 | MIT OR Apache-2.0 | https://github.com/rustls/tokio-rustls |
| tokio-util | 0.7.20 | MIT | https://github.com/tokio-rs/tokio |
| tracing | 0.1.44 | MIT | https://github.com/tokio-rs/tracing |
| tracing-attributes | 0.1.31 | MIT | https://github.com/tokio-rs/tracing |
| tracing-core | 0.1.36 | MIT | https://github.com/tokio-rs/tracing |
| try-lock | 0.2.5 | MIT | https://github.com/seanmonstar/try-lock |
| unicode-ident | 1.0.27 | (MIT OR Apache-2.0) AND Unicode-3.0 | https://github.com/dtolnay/unicode-ident |
| untrusted | 0.9.0 | ISC | https://github.com/briansmith/untrusted |
| url | 2.5.8 | MIT OR Apache-2.0 | https://github.com/servo/rust-url |
| utf8_iter | 1.1.0 | Apache-2.0 OR MIT | https://github.com/hsivonen/utf8_iter |
| utf_types | 1.0.0 | Apache-2.0 OR MIT | https://github.com/hsivonen/utf_types |
| uuid | 1.29.0 | Apache-2.0 OR MIT | https://github.com/uuid-rs/uuid |
| walkdir | 2.5.0 | Unlicense/MIT | https://github.com/BurntSushi/walkdir |
| want | 0.3.2 | MIT | https://github.com/seanmonstar/want |
| wasi | 0.11.1+wasi-snapshot-preview1 | Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT | https://github.com/bytecodealliance/wasi |
| wasm-bindgen | 0.2.130 | MIT OR Apache-2.0 | https://github.com/wasm-bindgen/wasm-bindgen |
| wasm-bindgen-macro | 0.2.130 | MIT OR Apache-2.0 | https://github.com/wasm-bindgen/wasm-bindgen/tree/master/crates/macro |
| wasm-bindgen-macro-support | 0.2.130 | MIT OR Apache-2.0 | https://github.com/wasm-bindgen/wasm-bindgen/tree/main/crates/macro-support |
| wasm-bindgen-shared | 0.2.130 | MIT OR Apache-2.0 | https://github.com/wasm-bindgen/wasm-bindgen/tree/master/crates/shared |
| web-time | 1.1.0 | MIT OR Apache-2.0 | https://github.com/daxpedda/web-time |
| webpki-roots | 1.0.11 | CDLA-Permissive-2.0 | https://github.com/rustls/webpki-roots |
| winapi-util | 0.1.11 | Unlicense OR MIT | https://github.com/BurntSushi/winapi-util |
| windows-link | 0.2.1 | MIT OR Apache-2.0 | https://github.com/microsoft/windows-rs |
| windows-sys | 0.45.0 | MIT OR Apache-2.0 | https://github.com/microsoft/windows-rs |
| windows-sys | 0.52.0 | MIT OR Apache-2.0 | https://github.com/microsoft/windows-rs |
| windows-sys | 0.61.2 | MIT OR Apache-2.0 | https://github.com/microsoft/windows-rs |
| windows-targets | 0.42.2 | MIT OR Apache-2.0 | https://github.com/microsoft/windows-rs |
| windows-targets | 0.52.6 | MIT OR Apache-2.0 | https://github.com/microsoft/windows-rs |
| windows_aarch64_gnullvm | 0.42.2 | MIT OR Apache-2.0 | https://github.com/microsoft/windows-rs |
| windows_aarch64_gnullvm | 0.52.6 | MIT OR Apache-2.0 | https://github.com/microsoft/windows-rs |
| windows_aarch64_msvc | 0.42.2 | MIT OR Apache-2.0 | https://github.com/microsoft/windows-rs |
| windows_aarch64_msvc | 0.52.6 | MIT OR Apache-2.0 | https://github.com/microsoft/windows-rs |
| windows_i686_gnu | 0.42.2 | MIT OR Apache-2.0 | https://github.com/microsoft/windows-rs |
| windows_i686_gnu | 0.52.6 | MIT OR Apache-2.0 | https://github.com/microsoft/windows-rs |
| windows_i686_gnullvm | 0.52.6 | MIT OR Apache-2.0 | https://github.com/microsoft/windows-rs |
| windows_i686_msvc | 0.42.2 | MIT OR Apache-2.0 | https://github.com/microsoft/windows-rs |
| windows_i686_msvc | 0.52.6 | MIT OR Apache-2.0 | https://github.com/microsoft/windows-rs |
| windows_x86_64_gnu | 0.42.2 | MIT OR Apache-2.0 | https://github.com/microsoft/windows-rs |
| windows_x86_64_gnu | 0.52.6 | MIT OR Apache-2.0 | https://github.com/microsoft/windows-rs |
| windows_x86_64_gnullvm | 0.42.2 | MIT OR Apache-2.0 | https://github.com/microsoft/windows-rs |
| windows_x86_64_gnullvm | 0.52.6 | MIT OR Apache-2.0 | https://github.com/microsoft/windows-rs |
| windows_x86_64_msvc | 0.42.2 | MIT OR Apache-2.0 | https://github.com/microsoft/windows-rs |
| windows_x86_64_msvc | 0.52.6 | MIT OR Apache-2.0 | https://github.com/microsoft/windows-rs |
| writeable | 0.6.4 | Unicode-3.0 | https://github.com/unicode-org/icu4x |
| x509-parser | 0.18.1 | MIT OR Apache-2.0 | https://github.com/rusticata/x509-parser.git |
| yasna | 0.6.0 | MIT OR Apache-2.0 | https://github.com/qnighy/yasna.rs |
| yoke | 0.8.4 | Unicode-3.0 | https://github.com/unicode-org/icu4x |
| yoke-derive | 0.8.4 | Unicode-3.0 | https://github.com/unicode-org/icu4x |
| zerocopy | 0.8.63 | BSD-2-Clause OR Apache-2.0 OR MIT | https://github.com/google/zerocopy |
| zerocopy-derive | 0.8.63 | BSD-2-Clause OR Apache-2.0 OR MIT | https://github.com/google/zerocopy |
| zerofrom | 0.1.8 | Unicode-3.0 | https://github.com/unicode-org/icu4x |
| zerofrom-derive | 0.1.8 | Unicode-3.0 | https://github.com/unicode-org/icu4x |
| zeroize | 1.9.1 | Apache-2.0 OR MIT | https://github.com/RustCrypto/utils |
| zerotrie | 0.2.5 | Unicode-3.0 | https://github.com/unicode-org/icu4x |
| zerovec | 0.11.9 | Unicode-3.0 | https://github.com/unicode-org/icu4x |
| zerovec-derive | 0.11.7 | Unicode-3.0 | https://github.com/unicode-org/icu4x |
| zmij | 1.0.23 | MIT | https://github.com/dtolnay/zmij |

## Phase 1 network dependencies (review before integration)

| Dependency | Pinned version | License | Source |
| --- | --- | --- | --- |
| netstack-smoltcp | 0.2.4 | MIT OR Apache-2.0 | https://github.com/cavivie/netstack-smoltcp |
| smoltcp (transitive) | 0.12.0 | 0BSD | https://github.com/smoltcp-rs/smoltcp/tree/v0.12.0 |
| Tokio | 1.54.1 | MIT | https://github.com/tokio-rs/tokio |
| futures-util | 0.3.34 | MIT OR Apache-2.0 | https://github.com/rust-lang/futures-rs |
| socket2 | 0.6.5 | MIT OR Apache-2.0 | https://github.com/rust-lang/socket2 |
| etherparse | 0.16.0 | MIT OR Apache-2.0 | https://github.com/JulianSchmid/etherparse |
| libc | 0.2.191 | MIT OR Apache-2.0 | https://github.com/rust-lang/libc |
| tracing | 0.1 (locked by Cargo.lock) | MIT | https://github.com/tokio-rs/tracing |

netstack-smoltcp and smoltcp license texts are in licenses/. No Go/gVisor/lwIP
implementation is linked. Upstream tracing is disabled at compile time so no
packet payload can be printed by netstack diagnostic calls.

## Phase 2 DNS dependencies (review before integration)

- Hickory resolver/net/proto **0.26.3**: MIT OR Apache-2.0. Source:
  https://github.com/hickory-dns/hickory-dns; license texts in licenses/hickory-*.
- Quinn **0.11.12**: MIT OR Apache-2.0, https://github.com/quinn-rs/quinn.
- Serde **1.0.229** and serde_json **1.0.154**: MIT OR Apache-2.0,
  https://github.com/serde-rs/serde and https://github.com/serde-rs/json.
- TLS uses Hickory's ring/rustls support and public WebPKI roots, with certificate
  and hostname verification enabled. No insecure certificate verifier is used.
- All Hickory TCP/UDP/QUIC socket creation goes through a protected RuntimeProvider;
  no default provider or system-config fallback may create unprotected sockets.

## Phase 3 matching dependencies (review before integration)

- regex **1.13.1**, regex-automata / regex-syntax: MIT OR Apache-2.0,
  https://github.com/rust-lang/regex (LICENSE-MIT and LICENSE-APACHE).
- aho-corasick (regex transitive): Unlicense OR MIT,
  https://github.com/BurntSushi/aho-corasick. No backtracking regex engine is used.
- User patterns are capped by length, count, compiled program and DFA cache size.
  URL/header bytes are used only in memory for policy; query log stores host metadata.

## Phase 4 chart dependency (review before integration)

MPAndroidChart **v4.0.1**, `com.github.PhilJay.MPAndroidChart:MPChartCompose:v4.0.1`
(with MPChartLib transitively), Apache-2.0.
Source and API examples: https://github.com/PhilJay/MPAndroidChart/tree/v4.0.1.
License text: licenses/MPAndroidChart-Apache-2.0.txt. Integrated at the user's
explicit request. Compose wrapper lives only in core/designsystem, with
COUI colors, typography, spacing and accessible text summaries. JitPack is
restricted to the com.github.PhilJay.MPAndroidChart group; no chart implementation leaks into
feature modules. AndroidX Benchmark 1.5.0 / ProfileInstaller 1.4.1 / UIAutomator
2.4.0 are Apache-2.0 and used for profile generation/install.

## Phase 5 TLS/HTTP dependencies (review before integration)

- rcgen 0.14.10: MIT OR Apache-2.0, https://github.com/rustls/rcgen.
- hyper 1.12.0, hyper-util 0.1.21, http-body-util 0.1.5: MIT,
  https://github.com/hyperium/hyper, https://github.com/hyperium/hyper-util,
  https://github.com/hyperium/http-body.
- Rustls 0.23.46 / tokio-rustls 0.26.6 / WebPKI roots 1.0.11 are already present
  in the Phase 2 locked inventory. Ring provider, TLS hostname/chain verification;
  no insecure verifier. All raw upstream sockets use core/sockets protection.
- Root CA signing uses Android Keystore through a narrowly scoped JNI signer;
  private key bytes are never serialized or returned to Rust. User-created root
  certificates are public, local, excluded from configuration export.
