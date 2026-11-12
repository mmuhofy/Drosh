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

use super::{jint, jlong, jstring, jintArray};
use super::{new_int_array, new_string, read_string, session_lock};
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
    let session = session_lock(handle)?;

    // A viewport point is what the caller's coordinates are: the renderer draws
    // the viewport and the host's row is a screen row.
    let mut point: ghostty::GhosttyPoint = unsafe { std::mem::zeroed() };
    unsafe {
        super::set_enum(&mut point.tag, ghostty::GHOSTTY_POINT_TAG_VIEWPORT as u32);
        point.value.coordinate.x = column as u16;
        point.value.coordinate.y = row as u32;
    }

    let mut reference: ghostty::GhosttyGridRef = unsafe { std::mem::zeroed() };
    let result = unsafe {
        ghostty::ghostty_terminal_grid_ref(session.terminal(), point, &mut reference)
    };
    if !ghostty::ok(result) {
        return None;
    }

    let mut selection: ghostty::GhosttySelection = unsafe { std::mem::zeroed() };
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

    format(&session, selection)
}

/// Feed the selection gesture a press.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSelection_nativeSelectionPress(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    row: jint,
    column: jint,
    behavior: jint,
) -> jintArray {
    let Some(mut session) = session_lock(handle) else {
        return std::ptr::null_mut();
    };
    if row < 0 || column < 0 {
        return std::ptr::null_mut();
    }
    let cells = session.selection_event(
        ghostty::GHOSTTY_SELECTION_GESTURE_EVENT_TYPE_PRESS,
        row as u16,
        column as u16,
        None,
        Some(behavior.max(0) as u32),
    );
    // The cells, when there are any. An empty array means the event was
    // applied and produced no selection yet — the engine makes the selection
    // when the gesture ends, not when it starts — and a null means the event
    // was refused.
    match cells {
        Some(cells) => new_int_array(
            &mut env,
            &[
                cells.start_row as i32,
                cells.start_column as i32,
                cells.end_row as i32,
                cells.end_column as i32,
            ],
        ),
        None => new_int_array(&mut env, &[]),
    }
}

/// Feed the selection gesture a drag, with the geometry it reads cells with.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSelection_nativeSelectionDrag(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    row: jint,
    column: jint,
    columns: jint,
    cell_width: jint,
    screen_height: jint,
) -> jintArray {
    let Some(mut session) = session_lock(handle) else {
        return std::ptr::null_mut();
    };
    if row < 0 || column < 0 {
        return std::ptr::null_mut();
    }
    let geometry = crate::session::SelectionGeometry {
        columns: columns.max(1) as u32,
        cell_width: cell_width.max(1) as u32,
        padding_left: 0,
        screen_height: screen_height.max(1) as u32,
    };
    let cells = session.selection_event(
        ghostty::GHOSTTY_SELECTION_GESTURE_EVENT_TYPE_DRAG,
        row as u16,
        column as u16,
        Some(geometry),
        Some(1),
    );
    // The cells, when there are any. An empty array means the event was
    // applied and produced no selection yet — the engine makes the selection
    // when the gesture ends, not when it starts — and a null means the event
    // was refused.
    match cells {
        Some(cells) => new_int_array(
            &mut env,
            &[
                cells.start_row as i32,
                cells.start_column as i32,
                cells.end_row as i32,
                cells.end_column as i32,
            ],
        ),
        None => new_int_array(&mut env, &[]),
    }
}

/// Feed the selection gesture a release.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSelection_nativeSelectionRelease(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    row: jint,
    column: jint,
    behavior: jint,
) -> jintArray {
    let Some(mut session) = session_lock(handle) else {
        return std::ptr::null_mut();
    };
    if row < 0 || column < 0 {
        return std::ptr::null_mut();
    }
    let cells = session.selection_event(
        ghostty::GHOSTTY_SELECTION_GESTURE_EVENT_TYPE_RELEASE,
        row as u16,
        column as u16,
        None,
        Some(behavior.max(0) as u32),
    );
    // The cells, when there are any. An empty array means the event was
    // applied and produced no selection yet — the engine makes the selection
    // when the gesture ends, not when it starts — and a null means the event
    // was refused.
    match cells {
        Some(cells) => new_int_array(
            &mut env,
            &[
                cells.start_row as i32,
                cells.start_column as i32,
                cells.end_row as i32,
                cells.end_column as i32,
            ],
        ),
        None => new_int_array(&mut env, &[]),
    }
}

/// The text of the terminal's current selection.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSelection_nativeSelectionText(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let Some(session) = session_lock(handle) else {
        return std::ptr::null_mut();
    };
    match session.selection_text() {
        Some(text) => new_string(&mut env, &text),
        None => std::ptr::null_mut(),
    }
}

/// The URI of the hyperlink the cell at a viewport position belongs to, or null.
///
/// ## Two calls
///
/// The engine writes the URI into a buffer the caller owns, so its length is
/// asked for first — with a null buffer — and then the bytes. The engine's
/// answer to the measuring call is `GHOSTTY_OUT_OF_SPACE` for a cell that has a
/// link, which is the documented contract rather than an error; a cell with no
/// link answers `GHOSTTY_SUCCESS` and a length of zero.
///
/// ## Why a reference and not a row scan
///
/// A link runs across the cells that share its `OSC 8` run, and the engine is
/// the one that knows which cells those are. Resolving the single tapped cell
/// to a grid reference asks it directly.
pub fn hyperlink_at(session: &Session, row: u32, column: u16) -> Option<String> {
    let mut point: ghostty::GhosttyPoint = unsafe { std::mem::zeroed() };
    unsafe {
        super::set_enum(&mut point.tag, ghostty::GHOSTTY_POINT_TAG_VIEWPORT as u32);
        point.value.coordinate.x = column;
        point.value.coordinate.y = row;
    }

    let mut reference: ghostty::GhosttyGridRef = unsafe { std::mem::zeroed() };
    // A sized struct: `size` tells the engine which layout the caller expects
    // back, so it is set before the call rather than left to whatever the
    // engine writes.
    reference.size = std::mem::size_of::<ghostty::GhosttyGridRef>();
    let result = unsafe {
        ghostty::ghostty_terminal_grid_ref(session.terminal(), point, &mut reference)
    };
    if !ghostty::ok(result) {
        return None;
    }

    let mut length: usize = 0;
    unsafe {
        ghostty::ghostty_grid_ref_hyperlink_uri(
            &reference,
            std::ptr::null_mut(),
            0,
            &mut length,
        )
    };
    if length == 0 {
        return None;
    }

    let mut buffer = vec![0u8; length];
    let result = unsafe {
        ghostty::ghostty_grid_ref_hyperlink_uri(
            &reference,
            buffer.as_mut_ptr(),
            buffer.len(),
            &mut length,
        )
    };
    if !ghostty::ok(result) {
        return None;
    }
    buffer.truncate(length);
    Some(String::from_utf8_lossy(&buffer).into_owned())
}

/// Everything the engine holds, as text.
fn select_all(handle: jlong) -> Option<String> {
    let session = session_lock(handle)?;
    let mut selection: ghostty::GhosttySelection = unsafe { std::mem::zeroed() };
    let result = unsafe { ghostty::ghostty_terminal_select_all(session.terminal(), &mut selection) };
    if !ghostty::ok(result) {
        return None;
    }
    format(&session, selection)
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

/// The session's text, as the engine holds it.
///
/// `select_all` covers everything the grid has — the viewport and the
/// scrollback — and formatting it is the engine's own idea of what the text
/// is. It does not touch the render state, which matters: the preview runs
/// while the render thread is drawing, and a second `render_state_update` on
/// the same state would have the two fighting over it.
pub fn transcript(session: &Session) -> Option<String> {
    let mut selection: ghostty::GhosttySelection = unsafe { std::mem::zeroed() };
    let result =
        unsafe { ghostty::ghostty_terminal_select_all(session.terminal(), &mut selection) };
    if !ghostty::ok(result) {
        return None;
    }
    format(session, selection)
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
    let mut selections: Vec<ghostty::GhosttySelection> =
        vec![unsafe { std::mem::zeroed() }; wanted];
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

    let columns = session.columns();
    let rows = session.rows();

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