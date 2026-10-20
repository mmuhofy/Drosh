//! `native*` functions for the selection capability.
//!
//! Selection state lives on the engine; the view sets it from a tap and reads it
//! back as text. `nativeSelectWord`/`nativeSelectLine` return the selected text
//! (empty when the cell is outside any word/line) so a single call both selects
//! and confirms.

use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong};
use jni::JNIEnv;

use crate::ffi::{fail, finish, session_or, ErrorCode};

/// The currently selected text, or an empty string when nothing is selected.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_core_NativeCore_nativeSelectionText<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: jlong,
) -> JString<'local> {
    let text = match session_or(id) {
        Ok(session) => session.selection_text().unwrap_or_default(),
        Err(code) => {
            fail(code);
            String::new()
        }
    };
    env.new_string(text).unwrap_or_default()
}

/// Select the word under (row, col) and return the selected text.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_core_NativeCore_nativeSelectWord<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: jlong,
    row: jint,
    col: jint,
) -> JString<'local> {
    let row = row as usize;
    let col = col as usize;
    let text = match session_or(id) {
        Ok(session) => session.select_word(row, col).unwrap_or_default(),
        Err(code) => {
            fail(code);
            String::new()
        }
    };
    env.new_string(text).unwrap_or_default()
}

/// Select the line under (row, col) and return the selected text.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_core_NativeCore_nativeSelectLine<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: jlong,
    row: jint,
    col: jint,
) -> JString<'local> {
    let row = row as usize;
    let col = col as usize;
    let text = match session_or(id) {
        Ok(session) => session.select_line(row, col).unwrap_or_default(),
        Err(code) => {
            fail(code);
            String::new()
        }
    };
    env.new_string(text).unwrap_or_default()
}

/// Clear any active selection.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_core_NativeCore_nativeClearSelection(
    _env: JNIEnv,
    _class: JClass,
    id: jlong,
) -> jint {
    let result = (|| -> Result<(), ErrorCode> {
        session_or(id)?.clear_selection();
        Ok(())
    })();
    finish(result)
}
