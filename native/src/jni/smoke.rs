//! The self-test: does the released `libghostty-vt.so` actually work here?
//!
//! This is the gate. Before a renderer or a session is written against the
//! engine, it has to prove it can be created, fed a byte stream, and read back
//! as cells — on the device, with the ABI we ship.
//!
//! The test returns a report rather than a bool, because a failure has to say
//! *which* step failed on a device whose log may be the only channel out.

use jni::objects::{JClass, JString};
use jni::JNIEnv;

use super::{jstring};
use super::new_string;
use crate::ghostty;

macro_rules! render_get {
    ($state:expr, $field:expr, $out:expr) => {
        ghostty::ghostty_render_state_get(
            $state,
            $field,
            $out as *mut _ as *mut std::ffi::c_void,
        )
    };
}


/// Run the whole pipeline and return a report.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSmoke_nativeSelfTest(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let report = run();
    new_string(&mut env, &report)
}

fn run() -> String {
    let mut report = String::new();

    // --- 1. the library answers -----------------------------------------
    let mut version = ghostty::GhosttyString {
        ptr: std::ptr::null(),
        len: 0,
    };
    let result = unsafe {
        ghostty::ghostty_build_info(
            ghostty::GHOSTTY_BUILD_INFO_VERSION_STRING,
            &mut version as *mut _ as *mut std::ffi::c_void,
        )
    };
    if !ghostty::ok(result) {
        return format!("FAIL: ghostty_build_info -> {result}\n");
    }
    report.push_str(&format!("ghostty-vt: {}\n", span(version)));

    // --- 2. a terminal can be created ------------------------------------
    let mut terminal = std::ptr::null_mut();
    let created = unsafe {
        ghostty::ghostty_terminal_new(std::ptr::null(), &mut terminal, 20, 4)
    };
    if !ghostty::ok(created) {
        return format!("{report}FAIL: ghostty_terminal_new -> {created}\n");
    }
    report.push_str("terminal: created 20x4\n");

    // --- 3. it parses a VT stream ----------------------------------------
    let text = b"hi";
    unsafe { ghostty::ghostty_terminal_vt_write(terminal, text.as_ptr(), text.len()) };

    // --- 4. a render state can be updated from it ------------------------
    let mut state = std::ptr::null_mut();
    let result = unsafe { ghostty::ghostty_render_state_new(std::ptr::null(), &mut state) };
    if !ghostty::ok(result) {
        unsafe { ghostty::ghostty_terminal_free(terminal) };
        return format!("{report}FAIL: render_state_new -> {result}\n");
    }

    let result = unsafe { ghostty::ghostty_render_state_update(state, terminal) };
    if !ghostty::ok(result) {
        unsafe {
            ghostty::ghostty_render_state_free(state);
            ghostty::ghostty_terminal_free(terminal);
        }
        return format!("{report}FAIL: render_state_update -> {result}\n");
    }

    let mut cols: u16 = 0;
    let mut rows: u16 = 0;
    unsafe {
        render_get!(state, ghostty::GHOSTTY_RENDER_STATE_DATA_COLS, &mut cols);
        render_get!(state, ghostty::GHOSTTY_RENDER_STATE_DATA_ROWS, &mut rows);
    }
    report.push_str(&format!("grid: {cols}x{rows}\n"));

    // --- 5. the cells can be read back -----------------------------------
    let mut iterator = std::ptr::null_mut();
    let mut cells = std::ptr::null_mut();
    unsafe {
        ghostty::ghostty_render_state_row_iterator_new(std::ptr::null(), &mut iterator);
        ghostty::ghostty_render_state_row_cells_new(std::ptr::null(), &mut cells);
        ghostty::ghostty_render_state_get(
            state,
            ghostty::GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR,
            &mut iterator as *mut _ as *mut std::ffi::c_void,
        );
    }

    let mut first = 0u32;
    let mut second = 0u32;
    unsafe {
        if ghostty::ghostty_render_state_row_iterator_next(iterator)
            && ghostty::ok(ghostty::ghostty_render_state_row_get(
                iterator,
                ghostty::GHOSTTY_RENDER_STATE_ROW_DATA_CELLS,
                &mut cells as *mut _ as *mut std::ffi::c_void,
            ))
        {
            let mut codepoints = [0u32; 8];
            if ghostty::ok(ghostty::ghostty_render_state_row_cells_select(cells, 0)) {
                ghostty::ghostty_render_state_row_cells_get(
                    cells,
                    ghostty::GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF,
                    codepoints.as_mut_ptr() as *mut std::ffi::c_void,
                );
                first = codepoints[0];
            }
            if ghostty::ok(ghostty::ghostty_render_state_row_cells_select(cells, 1)) {
                ghostty::ghostty_render_state_row_cells_get(
                    cells,
                    ghostty::GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF,
                    codepoints.as_mut_ptr() as *mut std::ffi::c_void,
                );
                second = codepoints[0];
            }
        }

        ghostty::ghostty_render_state_row_cells_free(cells);
        ghostty::ghostty_render_state_row_iterator_free(iterator);
        ghostty::ghostty_render_state_free(state);
        ghostty::ghostty_terminal_free(terminal);
    }

    report.push_str(&format!(
        "cells: '{}' '{}'\n",
        char::from_u32(first).unwrap_or('?'),
        char::from_u32(second).unwrap_or('?')
    ));
    if first == 'h' as u32 && second == 'i' as u32 {
        report.push_str("PASS\n");
    } else {
        report.push_str("FAIL: expected 'h','i'\n");
    }
    report
}

fn span(value: ghostty::GhosttyString) -> String {
    if value.ptr.is_null() || value.len == 0 {
        return String::new();
    }
    unsafe { String::from_utf8_lossy(std::slice::from_raw_parts(value.ptr, value.len)).into_owned() }
}


#[allow(dead_code)]
fn _unused(_: JString) {}

/// What the engine says about the cursor and about colours.
///
/// A report rather than a bool, because the two questions have many answers
/// and only the engine knows which one it gave. The host cannot tell whether
/// the cursor is missing because the engine did not report one, because the
/// renderer drew it in the wrong place, or because the colour it reported was
/// black on black.
///
/// It writes a few cells with known properties — a plain one, one with a
/// background colour, one with reverse video — and reports what came back for
/// each, next to the cursor's own numbers.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeSmoke_nativeProbe(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let report = probe();
    new_string(&mut env, &report)
}

fn probe() -> String {
    let mut out = String::new();

    let mut terminal = std::ptr::null_mut();
    let created = unsafe { ghostty::ghostty_terminal_new(std::ptr::null(), &mut terminal, 20, 4) };
    if !ghostty::ok(created) {
        return format!("FAIL: terminal_new -> {created}\n");
    }

    // Row 0: a plain cell. Row 1: red background (SGR 41). Row 2: reverse
    // video (SGR 7). Row 3: a green foreground (SGR 32).
    let script: &[u8] = b"a\x1b[41mb\x1b[0m\r\n\x1b[7mc\x1b[0m\r\n\x1b[32md\x1b[0m\r\n";
    unsafe { ghostty::ghostty_terminal_vt_write(terminal, script.as_ptr(), script.len()) };

    // The cursor's own numbers, from the terminal itself.
    let mut cx: u16 = 0;
    let mut cy: u16 = 0;
    let mut visible = false;
    let _ = unsafe {
        ghostty::ghostty_terminal_get(
            terminal,
            ghostty::GHOSTTY_TERMINAL_DATA_CURSOR_X,
            &mut cx as *mut _ as *mut std::ffi::c_void,
        )
    };
    let _ = unsafe {
        ghostty::ghostty_terminal_get(
            terminal,
            ghostty::GHOSTTY_TERMINAL_DATA_CURSOR_Y,
            &mut cy as *mut _ as *mut std::ffi::c_void,
        )
    };
    let _ = unsafe {
        ghostty::ghostty_terminal_get(
            terminal,
            ghostty::GHOSTTY_TERMINAL_DATA_CURSOR_VISIBLE,
            &mut visible as *mut _ as *mut std::ffi::c_void,
        )
    };
    out.push_str(&format!("terminal cursor: x={cx} y={cy} visible={visible}\n"));

    // The effective default colours.
    let mut def_fg = ghostty::GhosttyColorRgb { r: 0, g: 0, b: 0 };
    let mut def_bg = ghostty::GhosttyColorRgb { r: 0, g: 0, b: 0 };
    let _ = unsafe {
        ghostty::ghostty_terminal_get(
            terminal,
            ghostty::GHOSTTY_TERMINAL_DATA_COLOR_FOREGROUND,
            &mut def_fg as *mut _ as *mut std::ffi::c_void,
        )
    };
    let _ = unsafe {
        ghostty::ghostty_terminal_get(
            terminal,
            ghostty::GHOSTTY_TERMINAL_DATA_COLOR_BACKGROUND,
            &mut def_bg as *mut _ as *mut std::ffi::c_void,
        )
    };
    out.push_str(&format!(
        "default fg: {},{},{}  bg: {},{},{}\n",
        def_fg.r, def_fg.g, def_fg.b, def_bg.r, def_bg.g, def_bg.b
    ));

    // The render state's own cursor, which is the one the renderer draws.
    let mut state = std::ptr::null_mut();
    if !ghostty::ok(unsafe { ghostty::ghostty_render_state_new(std::ptr::null(), &mut state) }) {
        unsafe { ghostty::ghostty_terminal_free(terminal) };
        return format!("{out}FAIL: render_state_new\n");
    }
    if !ghostty::ok(unsafe { ghostty::ghostty_render_state_update(state, terminal) }) {
        unsafe {
            ghostty::ghostty_render_state_free(state);
            ghostty::ghostty_terminal_free(terminal);
        }
        return format!("{out}FAIL: render_state_update\n");
    }

    let mut cursor = ghostty::GhosttyRenderStateCursor = unsafe { std::mem::zeroed() };
    cursor.size = std::mem::size_of::<ghostty::GhosttyRenderStateCursor>();
    let cursor_result = render_get!(
        state,
        ghostty::GHOSTTY_RENDER_STATE_DATA_CURSOR,
        &mut cursor
    );
    out.push_str(&format!(
        "render cursor: result={cursor_result} has_value={} x={} y={} visible={} style={}\n",
        cursor.viewport_has_value,
        cursor.viewport_x,
        cursor.viewport_y,
        cursor.visible,
        cursor.visual_style as u32
    ));

    // The cells: what came back for a plain one, a coloured one and an inverse
    // one. `cols` is the width of the grid.
    let mut cols: u16 = 0;
    let mut rows: u16 = 0;
    render_get!(state, ghostty::GHOSTTY_RENDER_STATE_DATA_COLS, &mut cols);
    render_get!(state, ghostty::GHOSTTY_RENDER_STATE_DATA_ROWS, &mut rows);
    out.push_str(&format!("grid: {cols}x{rows}\n"));

    let mut iterator = std::ptr::null_mut();
    let mut cells = std::ptr::null_mut();
    unsafe {
        ghostty::ghostty_render_state_row_iterator_new(std::ptr::null(), &mut iterator);
        ghostty::ghostty_render_state_row_cells_new(std::ptr::null(), &mut cells);
        render_get!(
            state,
            ghostty::GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR,
            &mut iterator
        );
    }

    let mut row = 0usize;
    while row < rows as usize {
        if !unsafe { ghostty::ghostty_render_state_row_iterator_next(iterator) } {
            break;
        }
        if !ghostty::ok(unsafe {
            render_get!(
                iterator,
                ghostty::GHOSTTY_RENDER_STATE_ROW_DATA_CELLS,
                &mut cells
            )
        }) {
            continue;
        }

        let mut style = ghostty::GhosttyStyle = unsafe { std::mem::zeroed() };
        style.size = std::mem::size_of::<ghostty::GhosttyStyle>();
        let mut fg = ghostty::GhosttyColorRgb { r: 0, g: 0, b: 0 };
        let mut bg = ghostty::GhosttyColorRgb { r: 0, g: 0, b: 0 };
        let mut cp = [0u32; 8];

        for column in 0..cols.min(4) {
            if !ghostty::ok(unsafe {
                ghostty::ghostty_render_state_row_cells_select(cells, column)
            }) {
                continue;
            }
            unsafe {
                ghostty::ghostty_render_state_row_cells_get(
                    cells,
                    ghostty::GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF,
                    cp.as_mut_ptr() as *mut std::ffi::c_void,
                );
                ghostty::ghostty_render_state_row_cells_get(
                    cells,
                    ghostty::GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR,
                    &mut fg as *mut _ as *mut std::ffi::c_void,
                );
                ghostty::ghostty_render_state_row_cells_get(
                    cells,
                    ghostty::GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR,
                    &mut bg as *mut _ as *mut std::ffi::c_void,
                );
                ghostty::ghostty_render_state_row_cells_get(
                    cells,
                    ghostty::GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE,
                    &mut style as *mut _ as *mut std::ffi::c_void,
                );
            }
            let ch = char::from_u32(cp[0]).unwrap_or('?');
            out.push_str(&format!(
                "  r{row} c{column} '{ch}' fg={},{},{} bg={},{},{} inverse={} bold={}\n",
                fg.r, fg.g, fg.b, bg.r, bg.g, bg.b, style.inverse, style.bold
            ));
        }
        row += 1;
    }

    unsafe {
        ghostty::ghostty_render_state_row_cells_free(cells);
        ghostty::ghostty_render_state_row_iterator_free(iterator);
        ghostty::ghostty_render_state_free(state);
        ghostty::ghostty_terminal_free(terminal);
    }
    out
}
