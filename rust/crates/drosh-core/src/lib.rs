//! Drosh terminal core — the Rust half of the terminal, loaded by the Android
//! app as a cdylib over JNI.
//!
//! - [`ffi`]: the JNI boundary (session lifecycle, snapshot, events).
//! - [`session`]: [`alacritty_terminal::Term`] + event loop + PTY.
//! - [`bridge`]: Term → flat cell buffer + damage list, and the event queue.
//! - [`font`]: shaping (`rustybuzz`) and rasterisation (`swash`) — Phase B.
//! - [`util`]: logging and other small shared helpers.
//!
//! See `TODO.md` (Phase A) and `PLAN.md` (decisions D1–D13).

pub mod bridge;
pub mod ffi;
pub mod font;
pub mod session;
pub mod util;

use std::ffi::c_void;
use std::sync::Once;

use jni::objects::{JClass, JString};
use jni::sys::{jint, JNI_VERSION_1_6};
use jni::{JNIEnv, JavaVM};

/// Called by the JVM when the library is loaded.
///
/// # Safety
///
/// This is the JNI entry point: the JVM calls it with its own `JavaVM`
/// pointer. The signature is fixed by the JNI specification.
#[no_mangle]
pub extern "system" fn JNI_OnLoad(_vm: JavaVM, _reserved: *mut c_void) -> jint {
    util::logging::init();
    install_panic_hook();
    // JNI 1.6 is what Android's JVM supports.
    JNI_VERSION_1_6
}

fn install_panic_hook() {
    static HOOK: Once = Once::new();
    HOOK.call_once(|| {
        // With `panic = "abort"` in the release profile this is the only
        // chance to say why the process died, and it keeps the report out of
        // the JNI boundary.
        std::panic::set_hook(Box::new(|info| {
            log::error!("drosh-core panicked: {info}");
        }));
    });
}

/// Version of the loaded core.
///
/// The Kotlin side compares it against the version it was built against, so a
/// stale `libdrosh_core.so` is visible immediately instead of showing up as a
/// mysterious JNI error later.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_engine_core_NativeCore_nativeVersion<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> JString<'local> {
    env.new_string(env!("CARGO_PKG_VERSION")).unwrap_or_default()
}
