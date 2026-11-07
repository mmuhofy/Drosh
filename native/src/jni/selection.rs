//! Selection and search, on the engine's own API.
//!
//! ## Why the engine answers
//!
//! Word and line boundaries are the engine's, not ours: they depend on the
//! semantic escape characters it was configured with and on the cell widths it
//! computed. A second implementation would disagree with what the renderer
//! highlights, and the disagreement shows as a selection that copies one word
//! and highlights two.
//!
//! ## Snapshot, not state
//!
//! Selection is snapshot based: `select_word` takes a grid reference and returns
//! a selection the caller owns; it is not installed as the terminal's current
//! selection. That is why every entry point here takes a row and a column,
//! resolves them to a reference, and formats the result in one go.

use jni::objects::{JClass, JString};
use jni::JNIEnv;

use super::{jint, jlong, jstring};
use super::{new_string, read_string, session_from};
use crate::ghostty;
use crate::session::Session;

/// Select the word under (`row`, `column`) and return the text.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSelection_nativeSelectWord(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    row: jint,
    column: jint,
) -> jstring {
    let text = with_selection(handle, row, column, true).unwrap_or_default();
    new_string(&mut env, &text)
}

/// Select the whole line under (`row`, `column`) and return the text.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSelection_nativeSelectLine(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    row: jint,
    column: jint,
) -> jstring {
    let text = with_selection(handle, row, column, false).unwrap_or_default();
    new_string(&mut env, &text)
}

/// Select everything the engine holds, and return the text.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSelection_nativeSelectAll(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let text = select_all(handle).unwrap_or_default();
    new_string(&mut env, &text)
}

/// The word or line under a viewport cell, as text.
fn with_selection(handle: jlong, row: jint, column: jint, word: bool) -> Option<String> {
    if row < 0 || column < 0 || column > 0xffff {
        return None;
    }
    let session = session_from(handle)?;

    // A viewport point is what the caller's coordinates are: the renderer draws
    // the viewport and the host's row is a screen row.
    let mut point: ghostty::GhosttyPoint = unsafe { std::mem::zeroed() };
    unsafe {
        super::set_enum(&mut point.tag, ghostty::GHOSTTY_POINT_TAG_VIEWPORT as u32);
        point.value.coordinate.x = column as u16;
        point.value.coordinate.y = row as u32;
    }

    let mut reference = std::ptr::null_mut();
    let result = unsafe {
        ghostty::ghostty_terminal_grid_ref(session.terminal(), point, &mut reference)
    };
    if !ghostty::ok(result) {
        return None;
    }

    let mut selection = std::ptr::null_mut();
    let result = unsafe {
        if word {
            let mut options: ghostty::GhosttyTerminalSelectWordOptions = std::mem::zeroed();
            options.size = std::mem::size_of::<ghostty::GhosttyTerminalSelectWordOptions>();
            options.ref_ = reference;
            ghostty::ghostty_terminal_select_word(session.terminal(), &options, &mut selection)
        } else {
            let mut options: ghostty::GhosttyTerminalSelectLineOptions = std::mem::zeroed();
            options.size = std::mem::size_of::<ghostty::GhosttyTerminalSelectLineOptions>();
            options.ref_ = reference;
            ghostty::ghostty_terminal_select_line(session.terminal(), &options, &mut selection)
        }
    };
    if !ghostty::ok(result) {
        return None;
    }

    format(session, selection)
}

/// Everything the engine holds, as text.
fn select_all(handle: jlong) -> Option<String> {
    let session = session_from(handle)?;
    let mut selection = std::ptr::null_mut();
    let result = unsafe { ghostty::ghostty_terminal_select_all(session.terminal(), &mut selection) };
    if !ghostty::ok(result) {
        return None;
    }
    format(session, selection)
}

/// Format a selection snapshot into a string.
fn format(session: &Session, selection: ghostty::GhosttySelection) -> Option<String> {
    let mut options: ghostty::GhosttyTerminalSelectionFormatOptions = unsafe { std::mem::zeroed() };
    options.size = std::mem::size_of::<ghostty::GhosttyTerminalSelectionFormatOptions>();
    options.selection = &selection;

    let mut out_ptr: *mut u8 = std::ptr::null_mut();
    let mut out_len: usize = 0;
    let result = unsafe {
        ghostty::ghostty_terminal_selection_format_alloc(
            session.terminal(),
            std::ptr::null(),
            options,
            &mut out_ptr,
            &mut out_len,
        )
    };
    if !ghostty::ok(result) || out_ptr.is_null() || out_len == 0 {
        return None;
    }

    let text = unsafe { String::from_utf8_lossy(std::slice::from_raw_parts(out_ptr, out_len)).into_owned() };
    unsafe { ghostty::ghostty_free(std::ptr::null(), out_ptr, out_len) };
    Some(text)
}

/// Search the grid and return `(row, column, length)` triples.
///
/// `VIEWPORT_MATCHES` rather than `MATCHES`: the latter is in the engine's own
/// grid coordinates including the scrollback, while the viewport list is already
/// resolved to what the renderer draws.
pub fn search(session: &Session, query: &str, limit: usize) -> Vec<i32> {
    if query.is_empty() || limit == 0 {
        return Vec::new();
    }

    let mut search = std::ptr::null_mut();
    let result = unsafe {
        ghostty::ghostty_search_new(std::ptr::null(), &mut search, session.terminal())
    };
    if !ghostty::ok(result) {
        return Vec::new();
    }
    let search = SearchHandle(search);

    let needle = ghostty::GhosttyString {
        ptr: query.as_ptr(),
        len: query.len(),
    };
    let result = unsafe {
        ghostty::ghostty_search_set(
            search.0,
            ghostty::GHOSTTY_SEARCH_OPT_NEEDLE,
            &needle as *const _ as *const std::ffi::c_void,
        )
    };
    if !ghostty::ok(result) {
        return Vec::new();
    }

    if !ghostty::ok(unsafe { ghostty::ghostty_search_run(search.0) }) {
        return Vec::new();
    }

    let mut total: usize = 0;
    let result = unsafe {
        ghostty::ghostty_search_get(
            search.0,
            ghostty::GHOSTTY_SEARCH_DATA_TOTAL_MATCHES,
            &mut total as *mut usize as *mut std::ffi::c_void,
        )
    };
    if !ghostty::ok(result) || total == 0 {
        return Vec::new();
    }

    let wanted = total.min(limit);
    let mut selections = vec![std::ptr::null_mut(); wanted];
    let mut buffer = ghostty::GhosttySelectionBuffer {
        ptr: selections.as_mut_ptr(),
        cap: wanted,
        len: 0,
    };
    let result = unsafe {
        ghostty::ghostty_search_get(
            search.0,
            ghostty::GHOSTTY_SEARCH_DATA_VIEWPORT_MATCHES,
            &mut buffer as *mut _ as *mut std::ffi::c_void,
        )
    };
    if !ghostty::ok(result) {
        return Vec::new();
    }

    let mut columns: u16 = 0;
    let mut rows: u16 = 0;
    let _ = session.get(ghostty::GHOSTTY_TERMINAL_DATA_COLS, &mut columns);
    let _ = session.get(ghostty::GHOSTTY_TERMINAL_DATA_ROWS, &mut rows);

    let mut triples = Vec::with_capacity(buffer.len * 3);
    for index in 0..buffer.len {
        let Some(bounds) = viewport_bounds(session, selections[index], columns, rows) else {
            continue;
        };
        triples.push(bounds.0);
        triples.push(bounds.1);
        triples.push(bounds.2);
    }
    triples
}

/// A match's extent in viewport coordinates, or None when it is off screen.
///
/// The engine exposes a selection as a *predicate* — `contains(terminal,
/// selection, point)` — not as a rectangle. That is deliberate: a selection can
/// be rectangular, linear across rows, or semantic, and a rectangle is a wrong
/// answer for two of the three. So the extent is found by asking, and the scan
/// is bounded by the viewport.
fn viewport_bounds(
    session: &Session,
    selection: ghostty::GhosttySelection,
    columns: u16,
    rows: u16,
) -> Option<(i32, i32, i32)> {
    let mut first_row: i32 = -1;
    let mut first_column: i32 = -1;
    let mut last_column: i32 = -1;

    for row in 0..rows {
        for column in 0..columns {
            let mut point: ghostty::GhosttyPoint = unsafe { std::mem::zeroed() };
            unsafe {
                super::set_enum(&mut point.tag, ghostty::GHOSTTY_POINT_TAG_VIEWPORT as u32);
                point.value.coordinate.x = column;
                point.value.coordinate.y = u32::from(row);
            }

            let mut contained = false;
            let result = unsafe {
                ghostty::ghostty_terminal_selection_contains(
                    session.terminal(),
                    &selection,
                    point,
                    &mut contained,
                )
            };
            if !ghostty::ok(result) || !contained {
                continue;
            }

            if first_row < 0 {
                first_row = i32::from(row);
                first_column = i32::from(column);
            }
            last_column = i32::from(column);
        }
    }

    if first_row < 0 {
        return None;
    }
    Some((first_row, first_column, (last_column - first_column + 1).max(0)))
}

/// Frees a search handle when it goes out of scope.
struct SearchHandle(ghostty::GhosttySearch);

impl Drop for SearchHandle {
    fn drop(&mut self) {
        unsafe { ghostty::ghostty_search_free(self.0) };
    }
}

#[allow(dead_code)]
fn _unused(_: JString) {
    let _ = read_string;
}