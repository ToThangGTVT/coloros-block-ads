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

## Locked Rust dependency inventory

Reviewed from Cargo.lock manifests, including host/target-specific crates.

| Crate | Version | License | Source |
| --- | --- | --- | --- |
| arrayvec | 0.7.8 | MIT OR Apache-2.0 | https://github.com/bluss/arrayvec |
| bitflags | 1.3.2 | MIT/Apache-2.0 | https://github.com/bitflags/bitflags |
| byteorder | 1.5.0 | Unlicense OR MIT | https://github.com/BurntSushi/byteorder |
| bytes | 1.12.1 | MIT | https://github.com/tokio-rs/bytes |
| cesu8 | 1.1.0 | Apache-2.0/MIT | https://github.com/emk/cesu8-rs |
| cfg-if | 1.0.5 | MIT OR Apache-2.0 | https://github.com/rust-lang/cfg-if |
| combine | 4.6.9 | MIT | https://github.com/Marwes/combine |
| defmt | 0.3.101 | MIT OR Apache-2.0 | https://github.com/knurling-rs/defmt |
| defmt | 1.2.0 | MIT OR Apache-2.0 | https://github.com/knurling-rs/defmt |
| defmt-macros | 1.2.0 | MIT OR Apache-2.0 | https://github.com/knurling-rs/defmt |
| defmt-parser | 1.1.0 | MIT OR Apache-2.0 | https://github.com/knurling-rs/defmt |
| etherparse | 0.16.0 | MIT OR Apache-2.0 | https://github.com/JulianSchmid/etherparse |
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
| hash32 | 0.3.1 | MIT OR Apache-2.0 | https://github.com/japaric/hash32 |
| heapless | 0.8.0 | MIT OR Apache-2.0 | https://github.com/rust-embedded/heapless |
| jni | 0.21.1 | MIT/Apache-2.0 | https://github.com/jni-rs/jni-rs |
| jni-sys | 0.3.1 | MIT OR Apache-2.0 | https://github.com/jni-rs/jni-sys |
| jni-sys | 0.4.1 | MIT OR Apache-2.0 | https://github.com/jni-rs/jni-sys |
| jni-sys-macros | 0.4.1 | MIT OR Apache-2.0 | https://github.com/jni-rs/jni-sys |
| libc | 0.2.191 | MIT OR Apache-2.0 | https://github.com/rust-lang/libc |
| lock_api | 0.4.14 | MIT OR Apache-2.0 | https://github.com/Amanieu/parking_lot |
| log | 0.4.35 | MIT OR Apache-2.0 | https://github.com/rust-lang/log |
| managed | 0.8.0 | 0BSD | https://github.com/m-labs/rust-managed.git |
| memchr | 2.8.3 | Unlicense OR MIT | https://github.com/BurntSushi/memchr |
| mio | 1.2.4 | MIT | https://github.com/tokio-rs/mio |
| netstack-smoltcp | 0.2.4 | MIT OR Apache-2.0 | https://github.com/cavivie/netstack-smoltcp |
| once_cell | 1.21.4 | MIT OR Apache-2.0 | https://github.com/matklad/once_cell |
| pin-project-lite | 0.2.17 | Apache-2.0 OR MIT | https://github.com/taiki-e/pin-project-lite |
| ppv-lite86 | 0.2.21 | MIT OR Apache-2.0 | https://github.com/cryptocorrosion/cryptocorrosion |
| proc-macro2 | 1.0.107 | MIT OR Apache-2.0 | https://github.com/dtolnay/proc-macro2 |
| quote | 1.0.47 | MIT OR Apache-2.0 | https://github.com/dtolnay/quote |
| rand | 0.8.8 | MIT OR Apache-2.0 | https://github.com/rust-random/rand |
| rand_chacha | 0.3.1 | MIT OR Apache-2.0 | https://github.com/rust-random/rand |
| rand_core | 0.6.4 | MIT OR Apache-2.0 | https://github.com/rust-random/rand |
| same-file | 1.0.6 | Unlicense/MIT | https://github.com/BurntSushi/same-file |
| scopeguard | 1.2.0 | MIT OR Apache-2.0 | https://github.com/bluss/scopeguard |
| slab | 0.4.12 | MIT | https://github.com/tokio-rs/slab |
| smoltcp | 0.12.0 | 0BSD | https://github.com/smoltcp-rs/smoltcp.git |
| socket2 | 0.6.5 | MIT OR Apache-2.0 | https://github.com/rust-lang/socket2 |
| spin | 0.9.9 | MIT | https://github.com/mvdnes/spin-rs.git |
| stable_deref_trait | 1.2.1 | MIT OR Apache-2.0 | https://github.com/storyyeller/stable_deref_trait |
| syn | 2.0.119 | MIT OR Apache-2.0 | https://github.com/dtolnay/syn |
| syn | 3.0.9 | MIT OR Apache-2.0 | https://github.com/dtolnay/syn |
| thiserror | 1.0.69 | MIT OR Apache-2.0 | https://github.com/dtolnay/thiserror |
| thiserror | 2.0.21 | MIT OR Apache-2.0 | https://github.com/dtolnay/thiserror |
| thiserror-impl | 1.0.69 | MIT OR Apache-2.0 | https://github.com/dtolnay/thiserror |
| thiserror-impl | 2.0.21 | MIT OR Apache-2.0 | https://github.com/dtolnay/thiserror |
| tokio | 1.54.1 | MIT | https://github.com/tokio-rs/tokio |
| tokio-macros | 2.7.2 | MIT | https://github.com/tokio-rs/tokio |
| tokio-util | 0.7.20 | MIT | https://github.com/tokio-rs/tokio |
| tracing | 0.1.44 | MIT | https://github.com/tokio-rs/tracing |
| tracing-attributes | 0.1.31 | MIT | https://github.com/tokio-rs/tracing |
| tracing-core | 0.1.36 | MIT | https://github.com/tokio-rs/tracing |
| unicode-ident | 1.0.27 | (MIT OR Apache-2.0) AND Unicode-3.0 | https://github.com/dtolnay/unicode-ident |
| walkdir | 2.5.0 | Unlicense/MIT | https://github.com/BurntSushi/walkdir |
| wasi | 0.11.1+wasi-snapshot-preview1 | Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT | https://github.com/bytecodealliance/wasi |
| winapi-util | 0.1.11 | Unlicense OR MIT | https://github.com/BurntSushi/winapi-util |
| windows-link | 0.2.1 | MIT OR Apache-2.0 | https://github.com/microsoft/windows-rs |
| windows-sys | 0.45.0 | MIT OR Apache-2.0 | https://github.com/microsoft/windows-rs |
| windows-sys | 0.61.2 | MIT OR Apache-2.0 | https://github.com/microsoft/windows-rs |
| windows-targets | 0.42.2 | MIT OR Apache-2.0 | https://github.com/microsoft/windows-rs |
| windows_aarch64_gnullvm | 0.42.2 | MIT OR Apache-2.0 | https://github.com/microsoft/windows-rs |
| windows_aarch64_msvc | 0.42.2 | MIT OR Apache-2.0 | https://github.com/microsoft/windows-rs |
| windows_i686_gnu | 0.42.2 | MIT OR Apache-2.0 | https://github.com/microsoft/windows-rs |
| windows_i686_msvc | 0.42.2 | MIT OR Apache-2.0 | https://github.com/microsoft/windows-rs |
| windows_x86_64_gnu | 0.42.2 | MIT OR Apache-2.0 | https://github.com/microsoft/windows-rs |
| windows_x86_64_gnullvm | 0.42.2 | MIT OR Apache-2.0 | https://github.com/microsoft/windows-rs |
| windows_x86_64_msvc | 0.42.2 | MIT OR Apache-2.0 | https://github.com/microsoft/windows-rs |
| zerocopy | 0.8.63 | BSD-2-Clause OR Apache-2.0 OR MIT | https://github.com/google/zerocopy |
| zerocopy-derive | 0.8.63 | BSD-2-Clause OR Apache-2.0 OR MIT | https://github.com/google/zerocopy |

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
