//! Hyperlinks (`OSC 8`), the scrollbar's prompt marks, and absolute scrolls.
//!
//! The hyperlink URI is not carried in the cell records — a wrapped link can
//! be arbitrarily long, and the renderer would copy it for every cell it
//! covers. The records carry the one bit ("this cell is part of a link");
//! Kotlin asks for the URI here, at the moment the user taps the cell.

use jni::objects::{JClass, JIntArray, JString};
use jni::sys::{jint, jlong};
use jni::JNIEnv;

use crate::ffi::{fail, session_or, write_int_array, ErrorCode, FfiResult};

/// The URI of the hyperlink at viewport (`row`, `column`), or an empty string.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_core_NativeCore_nativeHyperlinkAt<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: jlong,
    row: jint,
    column: jint,
) -> JString<'local> {
    let uri = match session_or(id) {
        Ok(session) => session.hyperlink_at(row, column).unwrap_or_default(),
        Err(code) => {
            fail(code);
            String::new()
        }
    };
    env.new_string(uri).unwrap_or_default()
}

/// Fill `out` with the rows a prompt started on, oldest first.
///
/// Each row is its distance above the live bottom edge, which keeps pointing
/// at the same row as the history grows. Returns the count written, or a
/// negative error code.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_core_NativeCore_nativePromptRows<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: jlong,
    out: JIntArray<'local>,
) -> jint {
    let result = (|| -> Result<usize, ErrorCode> {
        let session = session_or(id)?;
        let marks = session.prompt_marks();
        let len = crate::ffi::int_array_len(&env, &out)?;
        let count = marks.len().min(len);
        let values: Vec<i32> = marks[..count].iter().map(|&m| m as i32).collect();
        write_int_array(&env, &out, &values)?;
        Ok(count)
    })();

    match result {
        Ok(count) => count as jint,
        Err(code) => code.as_jint(),
    }
}

/// Scroll so [offset] rows of history sit above the viewport.
///
/// The scrollbar's drag. Returns the offset the engine actually took — it
/// clamps to the history there is — or a negative error code.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_core_NativeCore_nativeScrollTo(
    _env: JNIEnv,
    _class: JClass,
    id: jlong,
    offset: jint,
) -> jint {
    let result = (|| -> FfiResult<jint> {
        let session = session_or(id)?;
        Ok(session.scroll_to(offset) as jint)
    })();

    match result {
        Ok(actual) => actual,
        Err(code) => code.as_jint(),
    }
}

/// How much history there is above the viewport, in rows.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_core_NativeCore_nativeHistorySize(
    _env: JNIEnv,
    _class: JClass,
    id: jlong,
) -> jint {
    let result = (|| -> FfiResult<jint> {
        let session = session_or(id)?;
        Ok(session.history_size() as jint)
    })();

    match result {
        Ok(size) => size,
        Err(code) => code.as_jint(),
    }
}
