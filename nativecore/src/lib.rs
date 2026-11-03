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
    objects::{GlobalRef, JObject, JString, JValue},
    sys::{jboolean, jint, jlongArray, jstring, JNI_FALSE, JNI_TRUE},
    JNIEnv, JavaVM,
};
use sockets::Protector;
use std::{
    io,
    os::fd::FromRawFd,
    sync::{atomic::Ordering, Arc, Mutex},
};
use tun::{Engine, NetworkConfig};

pub const VERSION: &str = concat!(env!("CARGO_PKG_VERSION"), "-phase3");
static ENGINE: Mutex<Option<Engine>> = Mutex::new(None);

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
        let mut env = self.vm.attach_current_thread().ok()?;
        let local = JObject::from(env.new_string(source.ip().to_string()).ok()?);
        let remote = JObject::from(env.new_string(destination.ip().to_string()).ok()?);
        let result = env
            .call_method(
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
            .and_then(|v| v.i());
        match result {
            Ok(uid) if uid >= 0 => Some(uid as u32),
            _ => {
                let _ = env.exception_clear();
                None
            }
        }
    }
    fn protect(&self, fd: i32) -> io::Result<()> {
        let mut env = self.vm.attach_current_thread().map_err(io::Error::other)?;
        let result = env.call_method(
            self.callback.as_obj(),
            "protect",
            "(I)Z",
            &[JValue::Int(fd)],
        );
        match result.and_then(|value| value.z()) {
            Ok(true) => Ok(()),
            _ => {
                // Never permit a failed callback or pending Java exception to bypass protection.
                let _ = env.exception_clear();
                Err(io::Error::new(
                    io::ErrorKind::PermissionDenied,
                    "socket protection failed",
                ))
            }
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
        if let Ok(mut env) = self.vm.attach_current_thread() {
            if let Ok(text) = env.new_string(json) {
                let text = JObject::from(text);
                if env
                    .call_method(
                        self.callback.as_obj(),
                        "onDnsEvents",
                        "(Ljava/lang/String;)V",
                        &[JValue::Object(&text)],
                    )
                    .is_err()
                {
                    let _ = env.exception_clear();
                }
            }
        }
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
    system: JString,
    custom: JString,
    apply: jboolean,
) -> jstring {
    let (Ok(system), Ok(custom)) = (
        env.get_string(&system).map(String::from),
        env.get_string(&custom).map(String::from),
    ) else {
        return std::ptr::null_mut();
    };
    let report = if apply == JNI_TRUE {
        filter::replace(&system, &custom)
    } else {
        filter::Rules::compile(&system, &custom).report
    };
    match serde_json::to_string(&report)
        .ok()
        .and_then(|json| env.new_string(json).ok())
    {
        Some(json) => json.into_raw(),
        None => std::ptr::null_mut(),
    }
}
