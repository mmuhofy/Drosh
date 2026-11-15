//! The engine's search, across the JNI boundary.

use jni::objects::{JClass, JString};
use jni::JNIEnv;

use super::{jboolean, jint, jintArray, jlong, jstring};
use super::{new_int_array, new_string, read_string, session_lock};
use crate::session::Session;

/// Set the needle. An empty query clears it.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSearch_nativeSetNeedle(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    query: JString,
) -> jboolean {
    let Some(mut session) = session_lock(handle) else {
        return 0;
    };
    let text = read_string(&mut env, &query.into_raw());
    if session.search_set_needle(&text) {
        1
    } else {
        0
    }
}

/// Feed the terminal's state into the search.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSearch_nativeFeed(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    let Some(mut session) = session_lock(handle) else {
        return 0;
    };
    if session.search_feed() {
        1
    } else {
        0
    }
}

/// Make a bounded amount of progress. Returns the status.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSearch_nativeTick(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    let Some(mut session) = session_lock(handle) else {
        return 2;
    };
    session.search_tick()
}

/// Feed and tick until the search is caught up.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSearch_nativeRun(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    let Some(mut session) = session_lock(handle) else {
        return 0;
    };
    // At least one feed, then loop.
    session.search_feed();
    let mut guard: usize = 0;
    loop {
        let status = session.search_tick();
        if status == 1 {
            // FEED_REQUIRED
            session.search_feed();
        } else if status == 2 {
            // COMPLETE
            break;
        }
        guard += 1;
        if guard > 100_000 {
            break;
        }
    }
    1
}

/// Select the next match.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSearch_nativeSelectNext(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    let Some(mut session) = session_lock(handle) else {
        return 0;
    };
    if session.search_select_next() {
        1
    } else {
        0
    }
}

/// Select the previous match.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSearch_nativeSelectPrev(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    let Some(mut session) = session_lock(handle) else {
        return 0;
    };
    if session.search_select_prev() {
        1
    } else {
        0
    }
}

/// Set the scroll policy.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSearch_nativeSetSelectScroll(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    policy: jint,
) -> jboolean {
    let Some(mut session) = session_lock(handle) else {
        return 0;
    };
    if session.search_set_select_scroll(policy) {
        1
    } else {
        0
    }
}

/// Total matches.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSearch_nativeTotalMatches(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    let Some(session) = session_lock(handle) else {
        return 0;
    };
    session.search_total_matches() as jint
}

/// Selected index, or -1.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSearch_nativeSelectedIndex(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    let Some(session) = session_lock(handle) else {
        return -1;
    };
    match session.search_selected_index() {
        Some(index) => index as jint,
        None => -1,
    }
}

/// Selected match viewport row/column/length, or empty.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSearch_nativeSelectedMatch(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jintArray {
    let Some(session) = session_lock(handle) else {
        return std::ptr::null_mut();
    };
    match session.search_selected_match_viewport() {
        Some((row, column, length)) => new_int_array(&mut env, &[row as i32, column as i32, length as i32]),
        None => new_int_array(&mut env, &[]),
    }
}

/// Text of the selected match, or null.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSearch_nativeSelectedMatchText(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let Some(session) = session_lock(handle) else {
        return std::ptr::null_mut();
    };
    match session.search_selected_match_text() {
        Some(text) => new_string(&mut env, &text),
        None => std::ptr::null_mut(),
    }
}

/// Free the search handle.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSearch_nativeFree(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle <= 0 {
        return;
    }
    if let Some(mut session) = session_lock(handle) {
        session.free_search();
    }
}

#[allow(dead_code)]
fn _unused(_: Session) {}