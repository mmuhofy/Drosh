//! The session, across the JNI boundary.
//!
//! The whole point of a session here is that the Kotlin side asks once: a frame
//! crosses in one piece, and there is no second call that could answer from a
//! different moment.

use jni::objects::{JByteArray, JClass, JIntArray, JString};
use jni::JNIEnv;

use super::{jboolean, jbyteArray, jint, jintArray, jlong, jstring};
use super::{new_byte_array, new_int_array, new_string, read_string, session_lock};
use crate::ghostty;
use crate::session::{Session, SessionHandle};

/// Create a session with a `columns` x `rows` terminal. Zero on failure.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeCreate(
    _env: JNIEnv,
    _class: JClass,
    columns: jint,
    rows: jint,
) -> jlong {
    if !(1..=0xffff).contains(&columns) || !(1..=0xffff).contains(&rows) {
        return 0;
    }
    match Session::new(columns as u16, rows as u16) {
        Ok(session) => SessionHandle::into_raw(session) as usize as jlong,
        Err(error) => {
            log::warn!("drosh-native: the session could not be made: {error:?}");
            0
        }
    }
}

/// Destroy a session. A zero handle is a no-op.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeFree(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle <= 0 {
        return;
    }
    // Rebuilt into a Box so the session's own `Drop` runs.
    unsafe { SessionHandle::destroy(handle as usize as *mut SessionHandle) };
}

/// Feed a byte stream to the parser.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeWrite(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    data: JByteArray,
    length: jint,
) -> jint {
    let Some(mut session) = session_lock(handle) else {
        return -1;
    };
    if length < 0 {
        return -1;
    }

    let mut bytes = match env.convert_byte_array(&data) {
        Ok(bytes) => bytes,
        Err(_) => return -2,
    };
    bytes.truncate(length as usize);
    session.write(&bytes);
    0
}

/// Resize the grid and record the cell size.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeResize(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    columns: jint,
    rows: jint,
    cell_width: jint,
    cell_height: jint,
) -> jint {
    let Some(mut session) = session_lock(handle) else {
        return -1;
    };
    if columns <= 0 || rows <= 0 {
        return -1;
    }
    let ok = session.resize(
        columns as u16,
        rows as u16,
        cell_width.max(1) as u32,
        cell_height.max(1) as u32,
    );
    if ok {
        0
    } else {
        -1
    }
}

/// Produce the next frame as a byte array, or null when there is nothing to
/// draw.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeFrame(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jbyteArray {
    let Some(mut session) = session_lock(handle) else {
        return std::ptr::null_mut();
    };
    match session.render() {
        Ok(bytes) => {
            let owned = bytes.to_vec();
            new_byte_array(&mut env, &owned)
        }
        Err(error) => {
            log::debug!("drosh-native: no frame: {error:?}");
            std::ptr::null_mut()
        }
    }
}

/// The scrollbar: `[total rows, viewport offset]`.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeScrollbar(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jintArray {
    let Some(session) = session_lock(handle) else {
        return std::ptr::null_mut();
    };
    let Some(scrollbar) = session.scrollbar() else {
        return std::ptr::null_mut();
    };
    let values = [scrollbar.total as i32, scrollbar.offset as i32];
    new_int_array(&mut env, &values)
}

/// True when the alternate screen is on: a program, not a shell.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeAlternateScreen(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    let Some(session) = session_lock(handle) else {
        return 0;
    };
    if session.active_screen() == ghostty::GHOSTTY_TERMINAL_SCREEN_ALTERNATE {
        1
    } else {
        0
    }
}

/// The engine's input-relevant flags: bit 0 mouse tracking, bit 1 kitty keyboard.
///
/// The C API has no mode bitfield; it exposes the two flags an embedder acts on,
/// so they are packed into the shape the host expects.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeModes(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    let Some(session) = session_lock(handle) else {
        return -1;
    };

    let mut bits = 0;
    if session.mouse_tracking() {
        bits |= 1;
    }
    if session.kitty_flags() != 0 {
        bits |= 2;
    }
    bits
}

/// Scroll the viewport. `tag` is a `GhosttyTerminalScrollViewportTag`.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeScroll(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    tag: jint,
    amount: jint,
) -> jint {
    let Some(session) = session_lock(handle) else {
        return -1;
    };

    let mut behavior: ghostty::GhosttyTerminalScrollViewport = unsafe { std::mem::zeroed() };
    let before = session.scrollbar().map(|s| s.offset).unwrap_or(0);
    unsafe {
        super::set_enum(&mut behavior.tag, tag as u32);
        if tag == ghostty::GHOSTTY_SCROLL_VIEWPORT_DELTA as i32 {
            behavior.value.delta = amount as isize;
        } else if tag == ghostty::GHOSTTY_SCROLL_VIEWPORT_ROW as i32 {
            behavior.value.row = if amount > 0 { amount as usize } else { 0 };
        }
        ghostty::ghostty_terminal_scroll_viewport(session.terminal(), behavior);
    }

    // Did the viewport actually move? At an end of the history the engine
    // clamps, and a caller that cannot tell would keep accumulating pixels
    // against a grid that is not moving.
    let after = session.scrollbar().map(|s| s.offset).unwrap_or(0);
    if after == before {
        1
    } else {
        0
    }
}

/// The shell's phase, as its `OSC 133` marks report it.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeShellPhase(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    let Some(session) = session_lock(handle) else {
        return -1;
    };
    session.shell_phase() as jint
}

/// The working directory the shell last reported, or an empty string.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeWorkingDirectory(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let Some(session) = session_lock(handle) else {
        return new_string(&mut env, "");
    };
    let directory = session.working_directory().unwrap_or("").to_string();
    new_string(&mut env, &directory)
}

/// Search the grid; returns `(row, column, length)` triples.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeSearch(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    query: JString,
    limit: jint,
) -> jintArray {
    let Some(mut session) = session_lock(handle) else {
        return std::ptr::null_mut();
    };
    if limit <= 0 {
        return std::ptr::null_mut();
    }
    let text = read_string(&mut env, &query.into_raw());
    let triples = super::selection::search(&session, &text, limit as usize);
    new_int_array(&mut env, &triples)
}

/// Silence an unused-import warning on a target without the JNI types.
#[allow(dead_code)]
fn _unused(_: JIntArray) {}
