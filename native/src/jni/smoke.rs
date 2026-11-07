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
