//! The terminal's bell: the engine's callback, and the app's answer to it.
//!
//! ## Why a bridge
//!
//! The engine's bell callback carries a terminal and a userdata pointer and
//! nothing else: there is no place for a JVM in it. The VM and the app's sink
//! class are captured once — in `nativeCreate`, where a `JNIEnv` is in hand —
//! and read back by the callback, which arrives on the PTY reader thread.
//!
//! ## Why it never panics
//!
//! A panic here unwinds into C and aborts the process, and a bell is exactly
//! the kind of thing a program sends in a tight loop. Every branch is checked
//! and every failure is a log line.

use std::ffi::c_void;
use std::sync::OnceLock;

use jni::objects::{GlobalRef, JObject, JValue};
use jni::JavaVM;

use crate::ghostty;

/// The class the bell is handed to.
const SINK_CLASS: &str = "dev/drosh/TerminalBell";

/// `TerminalBell.ring()` — no arguments, no answer.
const SINK_METHOD: &str = "ring";
const SINK_SIGNATURE: &str = "()V";

/// The VM and the sink, captured once.
struct Bridge {
    vm: JavaVM,
    sink: GlobalRef,
}

static BRIDGE: OnceLock<Bridge> = OnceLock::new();

/// Capture the VM and the bell's class. Idempotent, and never fatal.
pub fn install(env: &mut jni::JNIEnv) {
    if BRIDGE.get().is_some() {
        return;
    }
    match capture(env) {
        Ok(bridge) => {
            let _ = BRIDGE.set(bridge);
        }
        Err(error) => {
            log::warn!("drosh-native: the bell sink could not be captured: {error}")
        }
    }
}

fn capture(env: &mut jni::JNIEnv) -> Result<Bridge, jni::errors::Error> {
    let vm = env.get_java_vm()?;
    let class = env.find_class(SINK_CLASS)?;
    let sink = env.new_global_ref(&class)?;
    Ok(Bridge { vm, sink })
}

/// The engine's `GHOSTTY_TERMINAL_OPT_BELL` callback.
///
/// # Safety
/// Called by the engine when the terminal receives a BEL character.
pub unsafe extern "C" fn on_bell(_terminal: ghostty::GhosttyTerminal, _userdata: *mut c_void) {
    let Some(bridge) = BRIDGE.get() else {
        return;
    };

    // The reader thread is already attached to the JVM; this attaches only if
    // it somehow is not.
    let Ok(mut env) = bridge.vm.attach_current_thread() else {
        log::warn!("drosh-native: the bell thread could not reach the JVM");
        return;
    };

    let result = env.call_static_method(&bridge.sink, SINK_METHOD, SINK_SIGNATURE, &[]);
    if let Err(error) = result {
        log::warn!("drosh-native: the bell could not be delivered: {error}");
    }
}
