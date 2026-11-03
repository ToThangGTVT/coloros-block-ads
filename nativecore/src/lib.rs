//! Android native engine boundary. Packet processing starts in Phase 1.

pub mod dns;
pub mod filter;
pub mod mitm;
pub mod tcp;
pub mod tun;
pub mod udp;

use jni::{objects::JObject, sys::jstring, JNIEnv};

pub const VERSION: &str = concat!(env!("CARGO_PKG_VERSION"), "-phase0");

/// Called by EngineBridge.nativeVersion on the JVM's attached thread.
/// No environment or local reference is retained beyond this call.
#[no_mangle]
pub extern "system" fn Java_com_utc_color_blockads_core_enginebridge_EngineBridge_nativeVersion(
    mut env: JNIEnv,
    _instance: JObject,
) -> jstring {
    match env.new_string(VERSION) {
        Ok(version) => version.into_raw(),
        Err(_) => {
            // Preserve a pending allocation exception; otherwise report failure to Kotlin.
            if !env.exception_check().unwrap_or(true) {
                let _ = env.throw_new(
                    "java/lang/IllegalStateException",
                    "Cannot allocate native version",
                );
            }
            std::ptr::null_mut()
        }
    }
}
