//! The JNI boundary.
//!
//! Every function here is an entry point with a JNI signature and nothing else:
//! it validates the handle, calls the behaviour, and turns a failure into an
//! empty answer or a log line. No terminal logic lives in this directory — that
//! rule is what keeps the boundary boring enough to audit.
//!
//! A handle crosses as a `jlong`. It is a pointer: 64 bits on every ABI this
//! library is built for, and never dereferenced without a null check.

use jni::JNIEnv;

pub mod input;
pub mod pty;
pub mod selection;
pub mod session;
pub mod smoke;

use crate::session::SessionHandle;

/// The locked session behind a handle, or None when the handle is not one.
///
/// The lock is held for the caller's whole body — a guard, not a snapshot —
/// because every operation here is a sequence of engine calls that must not
/// interleave with another thread's.
pub(crate) fn session_lock(handle: jlong) -> Option<std::sync::MutexGuard<'static, crate::session::Session>> {
    if handle <= 0 {
        return None;
    }
    let raw = unsafe { SessionHandle::from_raw(handle as usize as *mut SessionHandle) }?;
    Some(raw.lock())
}

pub(crate) use jni::sys::{jboolean, jbyteArray, jint, jintArray, jlong, jstring};

/// Write a C enum field.
///
/// bindgen may represent a C enum as a Rust enum or as its integer type,
/// depending on the values it sees. Writing through the field's address treats
/// it as the 32-bit integer every C enum is on these targets, so the code does
/// not depend on which representation was chosen.
///
/// # Safety
/// `field` must be a `#[repr(C)]` C enum, which is what bindgen produces.
pub(crate) unsafe fn set_enum<T>(field: &mut T, value: u32) {
    *(field as *mut T as *mut u32) = value;
}

/// Read a C enum field as an integer.
///
/// # Safety
/// `field` must be a `#[repr(C)]` C enum.
pub(crate) unsafe fn get_enum<T>(field: &T) -> u32 {
    *(field as *const T as *const u32)
}

/// Read a Java string, or an empty one when it is null.
pub(crate) fn read_string(env: &mut JNIEnv, text: &jstring) -> String {
    if text.is_null() {
        return String::new();
    }
    // The cast is the JNI's own: a `jstring` is a `jobject` and the crate's
    // typed wrapper is constructed from it.
    let borrowed = unsafe { jni::objects::JString::from_raw(*text) };
    env.get_string(&borrowed)
        .map(|value| value.to_string_lossy().into_owned())
        .unwrap_or_default()
}

/// Copy a byte slice into a new Java array, or null on failure.
pub(crate) fn new_byte_array(env: &mut JNIEnv, bytes: &[u8]) -> jbyteArray {
    match env.byte_array_from_slice(bytes) {
        Ok(array) => array.into_raw(),
        Err(error) => {
            log::warn!("drosh-native: a byte array could not be made: {error}");
            std::ptr::null_mut()
        }
    }
}

/// Make a Java string, or an empty one on failure.
pub(crate) fn new_string(env: &mut JNIEnv, text: &str) -> jstring {
    match env.new_string(text) {
        Ok(string) => string.into_raw(),
        Err(error) => {
            log::warn!("drosh-native: a string could not be made: {error}");
            std::ptr::null_mut()
        }
    }
}

/// Make a Java int array.
pub(crate) fn new_int_array(env: &mut JNIEnv, values: &[i32]) -> jintArray {
    match env.new_int_array(values.len() as i32) {
        Ok(array) => {
            if !values.is_empty() {
                let _ = env.set_int_array_region(&array, 0, values);
            }
            array.into_raw()
        }
        Err(error) => {
            log::warn!("drosh-native: an int array could not be made: {error}");
            std::ptr::null_mut()
        }
    }
}
