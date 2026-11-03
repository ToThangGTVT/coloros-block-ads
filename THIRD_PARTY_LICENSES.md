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

Licenses checked from each crate manifest resolved by Cargo.lock (including host
tools and target-specific crates; not all are packaged on Android).

| Crate | Version | Declared license | Source |
| --- | --- | --- | --- |
| bytes | 1.12.1 | MIT | https://github.com/tokio-rs/bytes |
| cesu8 | 1.1.0 | Apache-2.0/MIT | https://github.com/emk/cesu8-rs |
| cfg-if | 1.0.5 | MIT OR Apache-2.0 | https://github.com/rust-lang/cfg-if |
| combine | 4.6.9 | MIT | https://github.com/Marwes/combine |
| jni | 0.21.1 | MIT/Apache-2.0 | https://github.com/jni-rs/jni-rs |
| jni-sys | 0.3.1 | MIT OR Apache-2.0 | https://github.com/jni-rs/jni-sys |
| jni-sys | 0.4.1 | MIT OR Apache-2.0 | https://github.com/jni-rs/jni-sys |
| jni-sys-macros | 0.4.1 | MIT OR Apache-2.0 | https://github.com/jni-rs/jni-sys |
| log | 0.4.35 | MIT OR Apache-2.0 | https://github.com/rust-lang/log |
| memchr | 2.8.3 | Unlicense OR MIT | https://github.com/BurntSushi/memchr |
| proc-macro2 | 1.0.107 | MIT OR Apache-2.0 | https://github.com/dtolnay/proc-macro2 |
| quote | 1.0.47 | MIT OR Apache-2.0 | https://github.com/dtolnay/quote |
| same-file | 1.0.6 | Unlicense/MIT | https://github.com/BurntSushi/same-file |
| syn | 2.0.119 | MIT OR Apache-2.0 | https://github.com/dtolnay/syn |
| thiserror | 1.0.69 | MIT OR Apache-2.0 | https://github.com/dtolnay/thiserror |
| thiserror-impl | 1.0.69 | MIT OR Apache-2.0 | https://github.com/dtolnay/thiserror |
| unicode-ident | 1.0.27 | (MIT OR Apache-2.0) AND Unicode-3.0 | https://github.com/dtolnay/unicode-ident |
| walkdir | 2.5.0 | Unlicense/MIT | https://github.com/BurntSushi/walkdir |
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
