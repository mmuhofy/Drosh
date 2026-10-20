//! `native*` functions for the ANSI-stripping capability.

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::jlong;
use jni::JNIEnv;

use crate::ffi::{fail, session_or};

/// Strip ANSI escape sequences from `data`.
///
/// Records are newline-separated, so any caller that stores the result as a
/// transcript must have the control bytes gone. An unknown handle or bad buffer
/// yields an empty string rather than a panic across JNI.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_core_NativeCore_nativeStripAnsi<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: jlong,
    data: JByteArray,
) -> JString<'local> {
    let session = match session_or(id) {
        Ok(session) => session,
        Err(code) => {
            fail(code);
            return env.new_string("").unwrap_or_default();
        }
    };

    let bytes = match env.convert_byte_array(&data) {
        Ok(bytes) => bytes,
        Err(_) => return env.new_string("").unwrap_or_default(),
    };

    env.new_string(session.strip_ansi(&bytes)).unwrap_or_default()
}
