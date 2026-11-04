//! Rust full-tunnel engine. Only connection metadata counters cross JNI in Phase 1.
pub mod dns;
pub mod filter;
pub mod mitm;
pub mod policy;
pub mod sockets;
pub mod tcp;
pub mod tun;
pub mod udp;

use jni::{
    objects::{GlobalRef, JByteArray, JObject, JString, JValue},
    sys::{jboolean, jbyteArray, jint, jlongArray, jstring, JNI_FALSE, JNI_TRUE},
    JNIEnv, JavaVM,
};
use sockets::Protector;
use std::{
    io,
    os::fd::FromRawFd,
    sync::{atomic::Ordering, Arc, Mutex},
};
use tun::{Engine, NetworkConfig};

pub const VERSION: &str = concat!(env!("CARGO_PKG_VERSION"), "-phase5");
static ENGINE: Mutex<Option<Engine>> = Mutex::new(None);

/// Calls Java from the tunnel worker, which needs this for every new flow. Attaching once avoids
/// an ART attach/detach per call; jni detaches the worker when its thread exits.
///
/// Nothing else cleans up on a permanently attached thread, so each call gets its own local
/// frame, and a pending exception is cleared here instead of leaking into the next callback.
fn callback<T>(vm: &JavaVM, call: impl FnOnce(&mut JNIEnv) -> jni::errors::Result<T>) -> Option<T> {
    let mut env = vm.attach_current_thread_permanently().ok()?;
    let result = env.with_local_frame(8, call);
    if result.is_err() {
        let _ = env.exception_clear();
    }
    result.ok()
}

struct AndroidProtector {
    vm: JavaVM,
    callback: GlobalRef,
}
impl Protector for AndroidProtector {
    fn owner(
        &self,
        protocol: u8,
        source: std::net::SocketAddr,
        destination: std::net::SocketAddr,
    ) -> Option<u32> {
        callback(&self.vm, |env| {
            let local = JObject::from(env.new_string(source.ip().to_string())?);
            let remote = JObject::from(env.new_string(destination.ip().to_string())?);
            env.call_method(
                self.callback.as_obj(),
                "connectionOwner",
                "(ILjava/lang/String;ILjava/lang/String;I)I",
                &[
                    JValue::Int(protocol as i32),
                    JValue::Object(&local),
                    JValue::Int(source.port() as i32),
                    JValue::Object(&remote),
                    JValue::Int(destination.port() as i32),
                ],
            )
            .and_then(|value| value.i())
        })
        .and_then(|uid| u32::try_from(uid).ok())
    }
    fn protect(&self, fd: i32) -> io::Result<()> {
        let allowed = callback(&self.vm, |env| {
            env.call_method(
                self.callback.as_obj(),
                "protect",
                "(I)Z",
                &[JValue::Int(fd)],
            )
            .and_then(|value| value.z())
        });
        // Never permit a failed callback or pending Java exception to bypass protection.
        if allowed == Some(true) {
            Ok(())
        } else {
            Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "socket protection failed",
            ))
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_utc_color_blockads_core_enginebridge_EngineBridge_nativeConfigurePolicy(
    mut env: JNIEnv,
    _: JObject,
    json: JString,
) -> jboolean {
    if env
        .get_string(&json)
        .ok()
        .is_some_and(|s| policy::configure(&String::from(s)))
    {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}
fn config(env: &mut JNIEnv, dns: &JString) -> NetworkConfig {
    let dns = env
        .get_string(dns)
        .ok()
        .map(String::from)
        .and_then(|s| s.split('%').next().unwrap_or("").parse().ok());
    NetworkConfig {
        dns,
        probe_ipv6: true,
        ..NetworkConfig::default()
    }
}

#[no_mangle]
pub extern "system" fn Java_com_utc_color_blockads_core_enginebridge_EngineBridge_nativeVersion(
    mut env: JNIEnv,
    _: JObject,
) -> jstring {
    match env.new_string(VERSION) {
        Ok(version) => version.into_raw(),
        Err(_) => {
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

/// Takes ownership of fd on entry, including every startup failure path.
#[no_mangle]
pub extern "system" fn Java_com_utc_color_blockads_core_enginebridge_EngineBridge_nativeStart(
    mut env: JNIEnv,
    _: JObject,
    fd: jint,
    callback: JObject,
    dns: JString,
) -> jboolean {
    if fd < 0 {
        return JNI_FALSE;
    }
    // SAFETY: Kotlin transfers a detached descriptor exactly once to this function.
    let fd = unsafe { std::os::fd::OwnedFd::from_raw_fd(fd) };
    let mut engine = ENGINE.lock().unwrap_or_else(|p| p.into_inner());
    if engine.is_some() {
        return JNI_FALSE;
    }
    let Ok(vm) = env.get_java_vm() else {
        return JNI_FALSE;
    };
    let Ok(callback) = env.new_global_ref(callback) else {
        return JNI_FALSE;
    };
    let network = config(&mut env, &dns);
    match Engine::start(fd, Arc::new(AndroidProtector { vm, callback }), network) {
        Ok(running) => {
            *engine = Some(running);
            JNI_TRUE
        }
        Err(_) => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_utc_color_blockads_core_enginebridge_EngineBridge_nativeStop(
    _: JNIEnv,
    _: JObject,
) {
    if let Some(mut engine) = ENGINE.lock().unwrap_or_else(|p| p.into_inner()).take() {
        engine.shutdown();
    }
}

#[no_mangle]
pub extern "system" fn Java_com_utc_color_blockads_core_enginebridge_EngineBridge_nativeUpdateNetwork(
    mut env: JNIEnv,
    _: JObject,
    dns: JString,
) -> jboolean {
    let network = config(&mut env, &dns);
    if ENGINE
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .as_ref()
        .is_some_and(|engine| engine.update_network(network))
    {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_utc_color_blockads_core_enginebridge_EngineBridge_nativeIsRunning(
    _: JNIEnv,
    _: JObject,
) -> jboolean {
    if ENGINE
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .as_ref()
        .is_some_and(|e| e.running.load(Ordering::Acquire))
    {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_utc_color_blockads_core_enginebridge_EngineBridge_nativeCounters(
    env: JNIEnv,
    _: JObject,
) -> jlongArray {
    let values = ENGINE
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .as_ref()
        .map(|e| e.stats.snapshot())
        .unwrap_or([0; 8]);
    let Ok(array) = env.new_long_array(values.len() as i32) else {
        return std::ptr::null_mut();
    };
    if env.set_long_array_region(&array, 0, &values).is_err() {
        return std::ptr::null_mut();
    }
    array.into_raw()
}

struct AndroidDnsSink {
    vm: JavaVM,
    callback: GlobalRef,
}
impl dns::EventSink for AndroidDnsSink {
    fn batch(&self, json: &str) {
        callback(&self.vm, |env| {
            let text = JObject::from(env.new_string(json)?);
            env.call_method(
                self.callback.as_obj(),
                "onDnsEvents",
                "(Ljava/lang/String;)V",
                &[JValue::Object(&text)],
            )
            .map(|_| ())
        });
    }
}
#[no_mangle]
pub extern "system" fn Java_com_utc_color_blockads_core_enginebridge_EngineBridge_nativeSetDnsEventSink(
    env: JNIEnv,
    _: JObject,
    callback: JObject,
) {
    if callback.is_null() {
        dns::set_sink(None);
        return;
    }
    if let (Ok(vm), Ok(callback)) = (env.get_java_vm(), env.new_global_ref(callback)) {
        dns::set_sink(Some(Arc::new(AndroidDnsSink { vm, callback })));
    }
}
#[no_mangle]
pub extern "system" fn Java_com_utc_color_blockads_core_enginebridge_EngineBridge_nativeConfigureDns(
    mut env: JNIEnv,
    _: JObject,
    json: JString,
) -> jboolean {
    let Ok(json) = env.get_string(&json).map(String::from) else {
        return JNI_FALSE;
    };
    if dns::configure(&json) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}
#[no_mangle]
pub extern "system" fn Java_com_utc_color_blockads_core_enginebridge_EngineBridge_nativeReplaceRules(
    mut env: JNIEnv,
    _: JObject,
    dns: JString,
    browser: JString,
    custom: JString,
    apply: jboolean,
) -> jstring {
    let (Ok(dns), Ok(browser), Ok(custom)) = (
        env.get_string(&dns).map(String::from),
        env.get_string(&browser).map(String::from),
        env.get_string(&custom).map(String::from),
    ) else {
        return std::ptr::null_mut();
    };
    let report = if apply == JNI_TRUE {
        filter::replace_lists(&dns, &browser, &custom)
    } else {
        filter::Rules::compile_lists(&dns, &browser, &custom).report
    };
    match serde_json::to_string(&report)
        .ok()
        .and_then(|json| env.new_string(json).ok())
    {
        Some(json) => json.into_raw(),
        None => std::ptr::null_mut(),
    }
}
/// Installs AdGuard scriptlet/redirect resources. Returns `{"scriptlets":n,"redirects":n,
/// "skipped":n}`, or `{"error":"..."}` when the input is rejected (previous set stays active).
#[no_mangle]
pub extern "system" fn Java_com_utc_color_blockads_core_enginebridge_EngineBridge_nativeSetResources(
    mut env: JNIEnv,
    _: JObject,
    scriptlets: JString,
    redirects: JString,
) -> jstring {
    let (Ok(scriptlets), Ok(redirects)) = (
        env.get_string(&scriptlets).map(String::from),
        env.get_string(&redirects).map(String::from),
    ) else {
        return std::ptr::null_mut();
    };
    let json = match filter::resources::install(&scriptlets, &redirects) {
        Ok(loaded) => serde_json::to_string(&loaded).ok(),
        Err(error) => serde_json::to_string(&serde_json::json!({ "error": error })).ok(),
    };
    match json.and_then(|json| env.new_string(json).ok()) {
        Some(json) => json.into_raw(),
        None => std::ptr::null_mut(),
    }
}

struct AndroidCaSigner {
    vm: JavaVM,
    callback: GlobalRef,
    point: Vec<u8>,
}
impl rcgen::PublicKeyData for AndroidCaSigner {
    fn der_bytes(&self) -> &[u8] {
        &self.point
    }
    fn algorithm(&self) -> &'static rcgen::SignatureAlgorithm {
        &rcgen::PKCS_ECDSA_P256_SHA256
    }
}
impl rcgen::SigningKey for AndroidCaSigner {
    fn sign(&self, data: &[u8]) -> Result<Vec<u8>, rcgen::Error> {
        if data.len() > 8192 {
            return Err(rcgen::Error::RemoteKeyError);
        }
        let result = (|| {
            let mut env = self.vm.attach_current_thread().ok()?;
            let bytes = JObject::from(env.byte_array_from_slice(data).ok()?);
            let value = env
                .call_method(
                    self.callback.as_obj(),
                    "sign",
                    "([B)[B",
                    &[JValue::Object(&bytes)],
                )
                .and_then(|value| value.l());
            match value {
                Ok(value) if !value.is_null() => env
                    .convert_byte_array(JByteArray::from(value))
                    .ok()
                    .filter(|v| !v.is_empty() && v.len() <= 128),
                _ => {
                    let _ = env.exception_clear();
                    None
                }
            }
        })();
        result.ok_or(rcgen::Error::RemoteKeyError)
    }
}
fn ca_signer(
    env: &mut JNIEnv,
    callback: JObject,
    public: JByteArray,
) -> Option<Arc<dyn mitm::ca::CaSigner>> {
    let point = env.convert_byte_array(public).ok()?;
    if point.len() != 65 || point[0] != 4 {
        return None;
    }
    Some(Arc::new(AndroidCaSigner {
        vm: env.get_java_vm().ok()?,
        callback: env.new_global_ref(callback).ok()?,
        point,
    }))
}
#[no_mangle]
pub extern "system" fn Java_com_utc_color_blockads_core_enginebridge_EngineBridge_nativeGenerateCa(
    mut env: JNIEnv,
    _: JObject,
    callback: JObject,
    public: JByteArray,
) -> jbyteArray {
    let Some(signer) = ca_signer(&mut env, callback, public) else {
        return std::ptr::null_mut();
    };
    mitm::ca::generate(signer)
        .ok()
        .and_then(|der| env.byte_array_from_slice(&der).ok())
        .map_or(std::ptr::null_mut(), |array| array.into_raw())
}
#[no_mangle]
pub extern "system" fn Java_com_utc_color_blockads_core_enginebridge_EngineBridge_nativeSetCa(
    mut env: JNIEnv,
    _: JObject,
    callback: JObject,
    public: JByteArray,
    certificate: JByteArray,
) -> jboolean {
    if callback.is_null() {
        mitm::ca::set(None, vec![]);
        return JNI_TRUE;
    }
    let Some(signer) = ca_signer(&mut env, callback, public) else {
        return JNI_FALSE;
    };
    let Ok(der) = env.convert_byte_array(certificate) else {
        return JNI_FALSE;
    };
    if mitm::ca::set(Some(signer), der) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}
#[no_mangle]
pub extern "system" fn Java_com_utc_color_blockads_core_enginebridge_EngineBridge_nativeConfigureHttps(
    mut env: JNIEnv,
    _: JObject,
    json: JString,
) -> jboolean {
    if env
        .get_string(&json)
        .ok()
        .is_some_and(|text| mitm::configure(&String::from(text)))
    {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_utc_color_blockads_core_enginebridge_EngineBridge_nativeHttpsApiVersion(
    _: JNIEnv,
    _: JObject,
) -> jint {
    1
}
