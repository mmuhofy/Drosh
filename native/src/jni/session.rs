//! The session, across the JNI boundary.
//!
//! The whole point of a session here is that the Kotlin side asks once: a frame
//! crosses in one piece, and there is no second call that could answer from a
//! different moment.

use jni::objects::{JByteArray, JByteBuffer, JClass, JIntArray, JString};
use jni::JNIEnv;

use super::{jboolean, jbyteArray, jint, jintArray, jlong, jstring};
use super::{
    frames_lock, new_byte_array, new_int_array, new_string, read_string, session_handle,
    session_lock,
};
use crate::ghostty;
use crate::session::{Session, SessionHandle};

/// Create a session with a `columns` x `rows` terminal. Zero on failure.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeCreate(
    mut env: JNIEnv,
    _class: JClass,
    columns: jint,
    rows: jint,
) -> jlong {
    if !(1..=0xffff).contains(&columns) || !(1..=0xffff).contains(&rows) {
        return 0;
    }
    // The clipboard bridge is captured from the first session that is made.
    // This is the one place a `JNIEnv` for the app's class loader is in hand
    // before a program's OSC 52 write needs it on the reader thread.
    crate::clipboard::install(&mut env);
    crate::bell::install(&mut env);
    match Session::new(columns as u16, rows as u16) {
        Ok(session) => {
            // The render state is the snapshot the renderer reads, and it has
            // its own lock so the terminal's lock can be released before the
            // frame is built.
            let frames = match session.frame_source() {
                Ok(frames) => frames,
                Err(error) => {
                    log::warn!("drosh-native: the render state could not be made: {error:?}");
                    return 0;
                }
            };
            SessionHandle::into_raw(session, frames) as usize as jlong
        }
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
///
/// The terminal's lock is held for the snapshot and released before the frame
/// is built: the engine's render state is updated in two phases, and only the
/// first one needs the terminal. That is what keeps the keyboard responsive
/// while a program floods the screen.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeFrame(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jbyteArray {
    let Some(session) = session_handle(handle) else {
        return std::ptr::null_mut();
    };
    match session.render_frame() {
        Some(bytes) => new_byte_array(&mut env, &bytes),
        None => std::ptr::null_mut(),
    }
}

/// How many lines of history the terminal keeps.
///
/// Zero turns scrollback off and erases what is already there; lowering the
/// limit prunes immediately, so this is safe to call on a live session rather
/// than only at start.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeSetScrollback(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    lines: jlong,
) -> jboolean {
    let Some(mut session) = session_lock(handle) else {
        return 0;
    };
    session.set_scrollback_lines(lines.max(0) as u64);
    1
}

/// Set the theme's colours: the two defaults and the 256-colour palette.
///
/// `fg` and `bg` are packed 0xRRGGBB. The palette array is 256 entries in the
/// same packing; a shorter array leaves the palette alone, because half a
/// palette is not a palette.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeSetColors(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    fg: jint,
    bg: jint,
    palette: jintArray,
) -> jboolean {
    let Some(mut session) = session_lock(handle) else {
        return 0;
    };

    let colour = |value: jint| ghostty::GhosttyColorRgb {
        r: ((value >> 16) & 0xff) as u8,
        g: ((value >> 8) & 0xff) as u8,
        b: (value & 0xff) as u8,
    };

    let mut colours: Vec<ghostty::GhosttyColorRgb> = Vec::new();
    if !palette.is_null() {
        // A borrowed local reference: the array belongs to the caller, so it is
        // forgotten rather than dropped — dropping a `JIntArray` built from a
        // raw pointer deletes the caller's array.
        let array = unsafe { JIntArray::from_raw(palette) };
        if let Ok(len) = env.get_array_length(&array) {
            let mut raw = vec![0i32; len as usize];
            if env.get_int_array_region(&array, 0, &mut raw).is_ok() {
                colours = raw.into_iter().map(colour).collect();
            }
        }
        std::mem::forget(array);
    }

    session.set_colors(colour(fg), colour(bg), &colours);
    1
}

/// Produce the next frame into the caller's buffer, or null when there is
/// nothing to draw.
///
/// The caller lends a direct buffer and the frame is written into it. That is
/// the whole point: a frame does not become a Java array on the way, so the
/// app's heap does not grow by forty kilobytes every time the terminal
/// redraws.
///
/// The buffer belongs to the caller. It must stay alive and untouched until
/// the next call — the renderer reads and draws the frame before it asks for
/// the next one, which is what makes the reuse safe.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeFrameInto(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    buffer: JByteBuffer,
) -> jint {
    let Some(session) = session_handle(handle) else {
        return 0;
    };
    let capacity = env.get_direct_buffer_capacity(&buffer).unwrap_or(0);
    let address = env.get_direct_buffer_address(&buffer).ok();
    session
        .with_frame(|bytes| {
            let Some(address) = address else {
                return -1;
            };
            if bytes.len() > capacity {
                // The buffer is too small. The caller makes a bigger one and
                // asks again; the frame is not drawn from a truncated buffer.
                return -1;
            }
            unsafe {
                std::ptr::copy_nonoverlapping(bytes.as_ptr(), address, bytes.len());
            }
            bytes.len() as jint
        })
        .unwrap_or(0)
}

/// Set the default cursor's shape (`style`) and whether it blinks.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeSetCursor(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    style: jint,
    blink: jboolean,
) -> jboolean {
    let Some(mut session) = session_lock(handle) else {
        return 0;
    };
    session.set_cursor(style.max(0) as u32, blink != 0);
    1
}

/// Highlight the range the search matched, in viewport rows.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeSetHighlight(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    row: jint,
    column: jint,
    length: jint,
) -> jboolean {
    let Some(mut frames) = frames_lock(handle) else {
        return 0;
    };
    if row < 0 || column < 0 || length <= 0 {
        frames.set_highlight(None);
        return 1;
    }
    frames.set_highlight(Some((row as u16, column as u16, length as u16)));
    1
}

/// Highlight every range the search's other matches cover, in viewport rows.
///
/// `ranges` is `(row, column, length)` triples. An empty array is the caller
/// saying there are no matches, so it clears the marking. A triple with a
/// part the frame cannot carry is skipped rather than clamped: a clamped
/// range marks a different place than the one the caller named, and a wrong
/// highlight is worse than a missing one.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeSetHighlights(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    ranges: jintArray,
) -> jboolean {
    let Some(mut frames) = frames_lock(handle) else {
        return 0;
    };

    let mut triples: Vec<(u16, u16, u16)> = Vec::new();
    if !ranges.is_null() {
        // A borrowed local reference: the array belongs to the caller, so it
        // is forgotten rather than dropped — dropping a `JIntArray` built from
        // a raw pointer deletes the caller's array.
        let array = unsafe { JIntArray::from_raw(ranges) };
        if let Ok(len) = env.get_array_length(&array) {
            let mut raw = vec![0i32; len as usize];
            if env.get_int_array_region(&array, 0, &mut raw).is_ok() {
                for chunk in raw.chunks_exact(3) {
                    let (row, column, length) = (chunk[0], chunk[1], chunk[2]);
                    if row < 0 || column < 0 || length <= 0 {
                        continue;
                    }
                    if row > u16::MAX as i32 || column > u16::MAX as i32 || length > u16::MAX as i32 {
                        continue;
                    }
                    triples.push((row as u16, column as u16, length as u16));
                }
            }
        }
        std::mem::forget(array);
    }

    frames.set_highlights(triples);
    1
}

/// Draw no all-matches highlight; the active one is untouched.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeClearHighlights(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    let Some(mut frames) = frames_lock(handle) else {
        return 0;
    };
    frames.clear_highlights();
    1
}

/// The cells the user's selection covers.
///
/// The engine's own selection is not what the app draws: the app asks the
/// engine for a *snapshot* of a selection and the engine does not install a
/// snapshot, so the range is marked here.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeSetSelectionRange(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    row: jint,
    column: jint,
    length: jint,
) -> jboolean {
    let Some(mut frames) = frames_lock(handle) else {
        return 0;
    };
    if row < 0 || column < 0 || length <= 0 {
        return 1;
    }
    frames.set_app_selection(Some((row as u16, column as u16, length as u16)));
    1
}

/// The frame's images: the pictures a program drew, and where they are.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeImages(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jbyteArray {
    // The terminal holds the pictures and the render state holds their
    // pixels, so both are needed. The terminal's lock is taken first, always:
    // the two are never taken the other way round.
    let Some(session) = session_handle(handle) else {
        return std::ptr::null_mut();
    };
    let _terminal = session.lock();
    let mut frames = session.frames_lock();
    let images = frames.images();
    if images.is_empty() {
        return std::ptr::null_mut();
    }
    new_byte_array(&mut env, &images)
}

/// The window title the program asked for (OSC 0/2), or the process name.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeTitle(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let Some(session) = session_lock(handle) else {
        return std::ptr::null_mut();
    };
    match session.title() {
        Some(title) => new_string(&mut env, &title),
        None => std::ptr::null_mut(),
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
    // total, offset, len — the whole scrollbar. The history above the live edge
    // is total - len, and offset is where the viewport sits in that row space.
    let values = [
        scrollbar.total as i32,
        scrollbar.offset as i32,
        scrollbar.len as i32,
    ];
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
        } else if tag == ghostty::GHOSTTY_SCROLL_VIEWPORT_DELTA_PROMPT as i32 {
            // The prompt delta: negative for the prompt above, positive for
            // the one below. The engine needs the shell's semantic prompt
            // markers, which a shell that emits them provides.
            behavior.value.delta_prompt = amount as isize;
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

/// The colours of a region of the grid, as `(foreground, background)` pairs.
///
/// The values are the frame's own packed colours — red in the low byte — so
/// the caller reads them with the same helpers the renderer uses.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeCellColors(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    first_row: jint,
    first_column: jint,
    rows: jint,
    columns: jint,
) -> jintArray {
    let Some(session) = session_handle(handle) else {
        return std::ptr::null_mut();
    };
    if rows <= 0 || columns <= 0 {
        return new_int_array(&mut env, &[]);
    }
    let colors = session.cell_colors(
        first_row as u16,
        first_column as u16,
        rows as u16,
        columns as u16,
    );
    let values: Vec<i32> = colors.into_iter().map(|c| c as i32).collect();
    new_int_array(&mut env, &values)
}

/// The session's text, as the engine holds it.
///
/// The engine's own text: the grid and the scrollback, formatted the way the
/// engine formats them. It does not go through the render state, so it can be
/// read while the render thread is drawing.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeTranscript(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let Some(session) = session_lock(handle) else {
        return new_string(&mut env, "");
    };
    let text = super::selection::transcript(&session).unwrap_or_default();
    new_string(&mut env, &text)
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

/// Take the block records the engine has accumulated since the last drain.
///
/// Records are newline-separated; every text field is hex-encoded UTF-8 so a
/// tab or a newline inside command output cannot split a record. The format is
/// defined in `crate::block` and parsed by `EngineBlockBridge` on the Kotlin
/// side.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeDrainBlockEvents(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let Some(mut session) = session_lock(handle) else {
        return new_string(&mut env, "");
    };
    let wire = session.drain_block_events();
    new_string(&mut env, &wire)
}

/// The exit status of the last finished command, or -1 when none was reported.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeExitStatus(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    let Some(session) = session_lock(handle) else {
        return -1;
    };
    session.block_exit_status()
}

/// True while the alternate screen is active: a TUI owns the surface, so the UI
/// falls back to the classic terminal rather than rendering command blocks.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeAltScreen(
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

/// The URI of the hyperlink the cell at a viewport position belongs to, or null.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeHyperlink(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    row: jint,
    column: jint,
) -> jstring {
    if row < 0 || column < 0 || column > 0xffff {
        return std::ptr::null_mut();
    }
    let Some(session) = session_lock(handle) else {
        return std::ptr::null_mut();
    };
    match super::selection::hyperlink_at(&session, row as u32, column as u16) {
        Some(uri) => new_string(&mut env, &uri),
        None => std::ptr::null_mut(),
    }
}

/// Silence an unused-import warning on a target without the JNI types.
#[allow(dead_code)]
fn _unused(_: JIntArray) {}
