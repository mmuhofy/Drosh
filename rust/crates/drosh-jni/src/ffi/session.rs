//! `native*` functions for the session lifecycle.

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jfloat, jint, jlong};
use jni::JNIEnv;

use crate::ffi::{
    fail, finish, optional_string, required_string, session_or, ErrorCode, FfiResult,
};
use drosh_session::manager;
use drosh_session::{GridSize, SessionConfig};

/// Start a session: PTY + engine + event loop.
///
/// `args` carries one argument per line, `env` one `KEY=VALUE` per line; that
/// keeps the JNI signature small and avoids Java array marshalling. Neither can
/// contain a NUL, and a newline inside a value is therefore impossible.
///
/// Returns the session handle (> 0) or a negative [`ErrorCode`].
///
/// The JNI signature is fixed by `NativeCore.kt`; the argument count is a
/// consequence of that, not of a design choice here.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_dev_drosh_terminal_engine_core_NativeCore_nativeCreateSession<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    columns: jint,
    rows: jint,
    cell_width: jint,
    cell_height: jint,
    program: JString<'local>,
    args: JString<'local>,
    env_vars: JString<'local>,
    cwd: JString<'local>,
    cursor_shape: jint,
) -> jlong {
    let result = (|| -> FfiResult<u64> {
        let size = grid_size(columns, rows)?;
        let cell_width = u16::try_from(cell_width).map_err(|_| ErrorCode::InvalidArgument)?;
        let cell_height = u16::try_from(cell_height).map_err(|_| ErrorCode::InvalidArgument)?;

        let config = SessionConfig {
            size,
            cell_width,
            cell_height,
            program: required_string(&mut env, &program)?,
            args: decode_lines(&required_string(&mut env, &args)?),
            env: decode_env(&required_string(&mut env, &env_vars)?)?,
            cursor_shape: decode_cursor_shape(cursor_shape),
            cwd: optional_string(&mut env, &cwd)?,
        };

        manager::spawn(&config).map_err(ErrorCode::from)
    })();

    match result {
        Ok(handle) => handle as jlong,
        Err(code) => fail(code).as_jint() as jlong,
    }
}

/// Start a bridged session: an engine with no PTY, fed through `nativeFeed`.
///
/// The host owns the transport (SSH, a debug socket, …); it routes user input
/// itself and hands the output here.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_core_NativeCore_nativeCreateBridgedSession(
    _env: JNIEnv,
    _class: JClass,
    columns: jint,
    rows: jint,
    cell_width: jint,
    cell_height: jint,
) -> jlong {
    let result = (|| -> FfiResult<u64> {
        let size = grid_size(columns, rows)?;
        let cell_width = u16::try_from(cell_width).map_err(|_| ErrorCode::InvalidArgument)?;
        let cell_height = u16::try_from(cell_height).map_err(|_| ErrorCode::InvalidArgument)?;

        manager::spawn_bridged(size, cell_width, cell_height).map_err(ErrorCode::from)
    })();

    match result {
        Ok(handle) => handle as jlong,
        Err(code) => fail(code).as_jint() as jlong,
    }
}

/// Feed output bytes into a bridged session (no PTY on the other side).
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_core_NativeCore_nativeFeed(
    env: JNIEnv,
    _class: JClass,
    id: jlong,
    data: JByteArray,
) -> jint {
    let result = (|| -> FfiResult<()> {
        let session = session_or(id)?;
        let bytes = env.convert_byte_array(&data).map_err(|_| ErrorCode::InvalidArgument)?;
        session.feed(&bytes);
        Ok(())
    })();

    finish(result)
}

/// Stop a session and tear down its child process.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_core_NativeCore_nativeCloseSession(
    _env: JNIEnv,
    _class: JClass,
    id: jlong,
) -> jint {
    let result = (|| -> FfiResult<()> {
        let handle = u64::try_from(id).map_err(|_| ErrorCode::InvalidHandle)?;
        manager::close(handle).map_err(ErrorCode::from)
    })();

    finish(result)
}

/// Send input to the PTY.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_core_NativeCore_nativeWrite<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: jlong,
    data: JByteArray<'local>,
) -> jint {
    let result = (|| -> FfiResult<()> {
        let session = session_or(id)?;
        let bytes = env.convert_byte_array(&data).map_err(|_| ErrorCode::InvalidArgument)?;
        session.write(&bytes).map_err(ErrorCode::from)
    })();

    finish(result)
}

/// Change the cursor's shape of a running session.
///
/// alacritty's own fallback: the terminal's `DECSCUSR` wins, and this is
/// what it falls back on. The term's configuration is fixed when the session
/// starts, but alacritty's term can be reconfigured while it runs, so the
/// setting takes effect without a new session.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_core_NativeCore_nativeSetCursorShape(
    _env: JNIEnv,
    _class: JClass,
    id: jlong,
    shape: jint,
) -> jint {
    let result = (|| -> FfiResult<()> {
        let session = session_or(id)?;
        session.set_cursor_shape(decode_cursor_shape(shape));
        Ok(())
    })();

    finish(result)
}

/// Resize grid, PTY and renderer state.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_core_NativeCore_nativeResize(
    _env: JNIEnv,
    _class: JClass,
    id: jlong,
    columns: jint,
    rows: jint,
    cell_width: jint,
    cell_height: jint,
) -> jint {
    let result = (|| -> FfiResult<()> {
        let session = session_or(id)?;
        let size = grid_size(columns, rows)?;
        let cell_width = u16::try_from(cell_width).map_err(|_| ErrorCode::InvalidArgument)?;
        let cell_height = u16::try_from(cell_height).map_err(|_| ErrorCode::InvalidArgument)?;

        session.resize(size, cell_width, cell_height).map_err(ErrorCode::from)
    })();

    finish(result)
}

/// Scroll the viewport (positive = into the scrollback history).
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_core_NativeCore_nativeScroll(
    _env: JNIEnv,
    _class: JClass,
    id: jlong,
    delta: jint,
) -> jint {
    let result = (|| -> FfiResult<()> {
        let session = session_or(id)?;
        session.scroll(delta).map_err(ErrorCode::from)
    })();

    finish(result)
}

/// Scroll by a finger distance, keeping the sub-line remainder.
///
/// `delta` is positive when the content should move down — the user dragging
/// back into the history. `cell_height` is a row's height in the same pixels.
/// Returns the number of whole rows the viewport moved (negative when it moved
/// toward the live edge), or an error code.
///
/// This is the path a pan and a fling take; `nativeScroll` is the whole-row one
/// a keyboard and a wheel use.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_core_NativeCore_nativeScrollByPixels(
    _env: JNIEnv,
    _class: JClass,
    id: jlong,
    delta: jfloat,
    cell_height: jint,
) -> jint {
    let result = (|| -> FfiResult<i32> {
        let session = session_or(id)?;
        let cell_height = u16::try_from(cell_height).map_err(|_| ErrorCode::InvalidArgument)?;
        let before = session.rows_above_edge();
        let moved = session.scroll_by_pixels(delta, cell_height);
        if !moved {
            return Ok(0);
        }
        Ok(session.rows_above_edge() as i32 - before as i32)
    })();

    match result {
        Ok(rows) => rows,
        Err(code) => code.as_jint(),
    }
}

fn grid_size(columns: jint, rows: jint) -> FfiResult<GridSize> {    let columns = u16::try_from(columns).map_err(|_| ErrorCode::InvalidArgument)?;
    let rows = u16::try_from(rows).map_err(|_| ErrorCode::InvalidArgument)?;
    let size = GridSize::new(columns, rows);

    if size.is_valid() {
        Ok(size)
    } else {
        Err(ErrorCode::InvalidArgument)
    }
}

/// The cursor's shape, as the application named it.
///
/// alacritty's shape set (`vte::ansi::CursorShape`): block, underline, beam,
/// hollow block and hidden. An unknown number is a block, which is what the
/// terminal falls back on anyway.
fn decode_cursor_shape(shape: jint) -> alacritty_terminal::vte::ansi::CursorShape {
    use alacritty_terminal::vte::ansi::CursorShape;
    match shape {
        1 => CursorShape::Underline,
        2 => CursorShape::Beam,
        3 => CursorShape::HollowBlock,
        4 => CursorShape::Hidden,
        _ => CursorShape::Block,
    }
}

/// One entry per line; a missing/empty string means "none", a trailing newline
/// is ignored, an empty line in the middle is a real (empty) entry.
fn decode_lines(text: &str) -> Vec<String> {
    if text.is_empty() {
        return Vec::new();
    }

    let mut lines: Vec<String> = text.split('\n').map(str::to_owned).collect();
    if lines.last().is_some_and(|line| line.is_empty()) {
        lines.pop();
    }
    lines
}

fn decode_env(text: &str) -> FfiResult<Vec<(String, String)>> {
    let mut entries = Vec::new();

    for line in decode_lines(text) {
        let (key, value) = line.split_once('=').ok_or(ErrorCode::InvalidArgument)?;
        if key.is_empty() {
            return Err(ErrorCode::InvalidArgument);
        }
        entries.push((key.to_owned(), value.to_owned()));
    }

    Ok(entries)
}

/// The last lines the session printed, oldest first.
///
/// The engine keeps a bounded copy of the output as it arrives, because the
/// scrollback inside the grid cannot be read out of it. The lines come back
/// with the escape sequences stripped, so a session log is text a person — or a
/// model — can read.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_core_NativeCore_nativeTranscript<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: jlong,
) -> JString<'local> {
    let text = match session_or(id) {
        Ok(session) => session.transcript().join("\n"),
        Err(code) => {
            fail(code);
            String::new()
        }
    };

    env.new_string(text).unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_text_means_no_entries() {
        assert!(decode_lines("").is_empty());
        assert!(decode_env("").unwrap().is_empty());
    }

    #[test]
    fn trailing_newline_is_ignored_but_empty_middle_entries_are_kept() {
        assert_eq!(decode_lines("a\n"), vec!["a".to_owned()]);
        assert_eq!(decode_lines("a\n\nb"), vec!["a".to_owned(), String::new(), "b".to_owned()]);
    }

    #[test]
    fn env_parses_key_value_pairs_on_the_first_equals() {
        let entries = decode_env("PATH=/bin:/usr/bin\nA=b=c\n").unwrap();
        let expected = vec![
            ("PATH".to_owned(), "/bin:/usr/bin".to_owned()),
            ("A".to_owned(), "b=c".to_owned()),
        ];

        assert_eq!(entries, expected);
    }

    #[test]
    fn env_rejects_entries_without_a_key() {
        assert_eq!(decode_env("=value").unwrap_err(), ErrorCode::InvalidArgument);
        assert_eq!(decode_env("novalue").unwrap_err(), ErrorCode::InvalidArgument);
    }

    #[test]
    fn grid_size_rejects_out_of_range_dimensions() {
        assert!(matches!(grid_size(0, 24), Err(ErrorCode::InvalidArgument)));
        assert!(matches!(grid_size(80, 0), Err(ErrorCode::InvalidArgument)));
        assert!(matches!(grid_size(-1, 24), Err(ErrorCode::InvalidArgument)));
        assert_eq!(grid_size(80, 24).unwrap(), GridSize::new(80, 24));
    }
}
