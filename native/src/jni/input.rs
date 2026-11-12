//! Keyboard input: Android's key codes become the engine's, and the engine
//! becomes escape sequences.
//!
//! ## Why the engine encodes
//!
//! A terminal's escape sequences depend on state the terminal owns: whether the
//! application asked for cursor keys in `DECCKM` form (`ESC O A` rather than
//! `ESC [ A`), whether the kitty keyboard protocol is on, whether a modifier is
//! reported as a parameter or as a prefix. Reimplementing those rules on this
//! side would be a copy, and a copy is a place to be wrong.
//!
//! `libghostty-vt` owns the rules. This file translates Android's vocabulary
//! into the engine's and lets the engine decide the bytes.

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::jfloat;
use jni::JNIEnv;

use super::{jboolean, jint, jlong};
use super::{read_string, session_lock};
use crate::ghostty;
use crate::pty::Pty;

/// The largest escape sequence one key event can produce, as the stack buffer
/// every encode here is sized against. The engine answers `OUT_OF_SPACE` with
/// the size it needed rather than writing a partial sequence, so an undersized
/// buffer loses the key whole; 256 is the ceiling the previous code used for
/// the buffer that crossed the boundary, and no sequence produced by a key
/// comes near it.
const KEY_SEQUENCE_MAX: usize = 256;

/// The engine's name for an Android key code, or null when the engine has no
/// name for it — a character key, which travels as text instead.
fn key_for(android: i32) -> Option<ghostty::GhosttyKey> {
    // Android's vocabulary to the engine's. A table rather than a chain of
    // `if`s: a key missing from a chain silently does nothing, and a table can
    // be read.
    let key = match android {
        19 => ghostty::GHOSTTY_KEY_ARROW_UP,
        20 => ghostty::GHOSTTY_KEY_ARROW_DOWN,
        21 => ghostty::GHOSTTY_KEY_ARROW_LEFT,
        22 => ghostty::GHOSTTY_KEY_ARROW_RIGHT,
        23 | 66 => ghostty::GHOSTTY_KEY_ENTER,
        67 => ghostty::GHOSTTY_KEY_BACKSPACE,
        4 | 111 => ghostty::GHOSTTY_KEY_ESCAPE,
        61 => ghostty::GHOSTTY_KEY_TAB,
        122 => ghostty::GHOSTTY_KEY_HOME,
        123 => ghostty::GHOSTTY_KEY_END,
        92 => ghostty::GHOSTTY_KEY_PAGE_UP,
        93 => ghostty::GHOSTTY_KEY_PAGE_DOWN,
        124 => ghostty::GHOSTTY_KEY_INSERT,
        112 => ghostty::GHOSTTY_KEY_DELETE,
        131 => ghostty::GHOSTTY_KEY_F1,
        132 => ghostty::GHOSTTY_KEY_F2,
        133 => ghostty::GHOSTTY_KEY_F3,
        134 => ghostty::GHOSTTY_KEY_F4,
        135 => ghostty::GHOSTTY_KEY_F5,
        136 => ghostty::GHOSTTY_KEY_F6,
        137 => ghostty::GHOSTTY_KEY_F7,
        138 => ghostty::GHOSTTY_KEY_F8,
        139 => ghostty::GHOSTTY_KEY_F9,
        140 => ghostty::GHOSTTY_KEY_F10,
        141 => ghostty::GHOSTTY_KEY_F11,
        142 => ghostty::GHOSTTY_KEY_F12,
        _ => return None,
    };
    Some(key)
}

/// Android's modifier bits, as the engine's.
///
/// Android reports a side for every modifier; the engine's own bits are separate
/// fields, so both are set. The right Alt is distinguished because it is AltGr
/// on a European layout — a compose key, not an escape prefix.
fn mods_for(meta: i32) -> ghostty::GhosttyMods {
    let mut mods: ghostty::GhosttyMods = 0;
    // META_SHIFT_ON = 0x1, META_ALT_ON = 0x2, META_CTRL_ON = 0x1000,
    // META_META_ON = 0x10000.
    // The masks are `#define`s, so bindgen types them as `c_uint`; the modifier
    // field is whatever the header typedef'd it as. Casting the mask, not the
    // accumulated value, is what makes the widths agree.
    if meta & 0x1 != 0 {
        mods |= ghostty::GHOSTTY_MODS_SHIFT as ghostty::GhosttyMods;
    }
    if meta & 0x2 != 0 {
        mods |= ghostty::GHOSTTY_MODS_ALT as ghostty::GhosttyMods;
    }
    if meta & 0x1000 != 0 {
        mods |= ghostty::GHOSTTY_MODS_CTRL as ghostty::GhosttyMods;
    }
    if meta & 0x10000 != 0 {
        mods |= ghostty::GHOSTTY_MODS_SUPER as ghostty::GhosttyMods;
    }
    // META_ALT_RIGHT_ON = 0x20, META_SHIFT_RIGHT_ON = 0x40,
    // META_CTRL_RIGHT_ON = 0x2000, META_META_RIGHT_ON = 0x20000.
    if meta & 0x20 != 0 {
        mods |= ghostty::GHOSTTY_MODS_ALT_SIDE as ghostty::GhosttyMods;
    }
    if meta & 0x40 != 0 {
        mods |= ghostty::GHOSTTY_MODS_SHIFT_SIDE as ghostty::GhosttyMods;
    }
    if meta & 0x2000 != 0 {
        mods |= ghostty::GHOSTTY_MODS_CTRL_SIDE as ghostty::GhosttyMods;
    }
    if meta & 0x20000 != 0 {
        mods |= ghostty::GHOSTTY_MODS_SUPER_SIDE as ghostty::GhosttyMods;
    }
    mods
}

/// Encode one key event into `out`; returns the byte count.
///
/// `action` is 0 press, 1 release, 2 repeat. `utf8` is the character the key
/// produced, when it produced one.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeInput_nativeEncodeKey(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    key_code: jint,
    meta_state: jint,
    action: jint,
    utf8: JString,
    out: JByteArray,
) -> jint {
    // The string is read before the lock is taken. It is a JNI call that
    // touches nothing of the terminal's state, and everything done under the
    // lock competes with the reader and the render thread for the session.
    let text = read_string(&mut env, &utf8.into_raw());

    // Encoded into a stack buffer: the owned `Vec` this used was an
    // allocation per key, and its bytes were copied out to `out` anyway.
    let mut scratch = [0u8; KEY_SEQUENCE_MAX];
    let written = {
        let Some(mut session) = session_lock(handle) else {
            return -1;
        };

        let key = key_for(key_code).unwrap_or(ghostty::GHOSTTY_KEY_UNIDENTIFIED);
        let mods = mods_for(meta_state);
        let key_action = match action {
            1 => ghostty::GHOSTTY_KEY_ACTION_RELEASE,
            2 => ghostty::GHOSTTY_KEY_ACTION_REPEAT,
            _ => ghostty::GHOSTTY_KEY_ACTION_PRESS,
        };

        session.encode_key_into(key, mods, key_action, &text, unshifted_of(&text))
        // The guard is dropped here, before the array below is filled: the
        // copy into JVM memory does not need the terminal's state.
    };
    if written == 0 {
        return 0;
    }

    if env
        .set_byte_array_region(
            &out,
            0,
            unsafe { std::slice::from_raw_parts(scratch.as_ptr() as *const i8, written) },
        )
        .is_err()
    {
        return -2;
    }
    written as jint
}

/// The codepoint a key's text starts with, as the encoder's unshifted value.
///
/// The text crossing this boundary is always a single character — the
/// keyboard's answer for one key — so its first codepoint is the whole of it.
fn unshifted_of(text: &str) -> u32 {
    text.chars().next().map(|c| c as u32).unwrap_or(0)
}

/// Encode one key event and write the bytes to the child, in one crossing.
///
/// The hot path — a keystroke from the keyboard — used to make two JNI
/// crossings per key: one to encode into a Java array, one (`nativeWrite`) to
/// copy that array back across the boundary and into the PTY. The two
/// together spent a heap allocation on every side — the `keyBuffer.copyOf` on
/// the Java side and the `Vec` `convert_byte_array` made on this side, both
/// for a sequence of a few bytes — and copied the same bytes three times
/// before the syscall saw them. This does the whole trip in one call: encode
/// into a stack buffer under the lock, drop the lock, write from the stack
/// buffer — the shape the engine's own encoder header recommends
/// (`static buf` straight into `write(pty_fd, ...)`).
///
/// `pty` may be zero for a session that has no child of its own: the bytes
/// are then encoded and counted but not written anywhere, which is what the
/// old two-call path effectively did with them.
///
/// `unicode` is the character the key produced (Android's
/// `KeyEvent.unicodeChar`), or zero when it produced none. It stands in for
/// the UTF-8 string the two-call path carried, so a character key crosses no
/// `String` either. An unpaired surrogate is not a character and carries no
/// text — the old path's replacement character for one was an artefact of the
/// string crossing, not a sequence any program asked for.
///
/// Returns the number of bytes written, 0 when the key produced nothing, or a
/// negative code.
// UNTESTED — verify before use (mirrors nativeWrite's raw-pointer borrow and
// nativeEncodeKey's encode path; nothing here was compiled or run)
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeInput_nativeEncodeWriteKey(
    _env: JNIEnv,
    _class: JClass,
    session_handle: jlong,
    pty_handle: jlong,
    key_code: jint,
    meta_state: jint,
    action: jint,
    unicode: jint,
) -> jint {
    // The character as UTF-8, on the stack: one key produces at most four
    // bytes, and a `String` for them was an allocation per keystroke.
    let mut utf8 = [0u8; 4];
    let text: &str = char::from_u32(unicode as u32)
        .map(|c| c.encode_utf8(&mut utf8))
        .unwrap_or("");

    let mut scratch = [0u8; KEY_SEQUENCE_MAX];
    let written = {
        let Some(mut session) = session_lock(session_handle) else {
            return -1;
        };

        let key = key_for(key_code).unwrap_or(ghostty::GHOSTTY_KEY_UNIDENTIFIED);
        let mods = mods_for(meta_state);
        let key_action = match action {
            1 => ghostty::GHOSTTY_KEY_ACTION_RELEASE,
            2 => ghostty::GHOSTTY_KEY_ACTION_REPEAT,
            _ => ghostty::GHOSTTY_KEY_ACTION_PRESS,
        };

        session.encode_key_into(key, mods, key_action, text, unshifted_of(text))
        // The guard is dropped before the write below: a PTY whose buffer is
        // full retries from here without holding the render thread hostage.
    };
    if written == 0 {
        return 0;
    }

    if pty_handle > 0 {
        // The same raw-pointer borrow the PTY entry points use: a `jlong`
        // handle is a `Pty` behind a box, and the write is a short syscall
        // away from the lock that was just dropped.
        let pty = unsafe { &mut *(pty_handle as usize as *mut Pty) };
        if let Err(error) = pty.write(&scratch[..written]) {
            log::debug!("drosh-native: the pty write failed: {error}");
            return -3;
        }
    }

    written as jint
}

/// Encode one mouse event into `out`; returns the byte count.
///
/// The engine decides whether the event produces bytes at all: an action the
/// program's tracking mode does not report — a motion without a button in
/// normal mode, say — encodes to nothing, and zero is that answer rather than
/// an error. The host writes only what came back.
///
/// `x`/`y` are surface-space pixels; `width`/`height` the view's size and
/// `cell_width`/`cell_height` the cell's, the geometry the engine converts
/// through. `button_pressed` is whether any button is held *right now*, which
/// is what the encoder needs to know to report motion in the normal tracking
/// mode — the host is tracking the finger, the engine cannot see it.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeInput_nativeEncodeMouse(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    action: jint,
    button: jint,
    x: jfloat,
    y: jfloat,
    width: jint,
    height: jint,
    cell_width: jint,
    cell_height: jint,
    button_pressed: jboolean,
    out: JByteArray,
) -> jint {
    let Some(mut session) = session_lock(handle) else {
        return -1;
    };
    if width < 0 || height < 0 || cell_width <= 0 || cell_height <= 0 {
        return -1;
    }

    let bytes = session.encode_mouse(
        action.max(0) as u32,
        button.max(0) as u32,
        x,
        y,
        width as u32,
        height as u32,
        cell_width as u32,
        cell_height as u32,
        button_pressed != 0,
    );
    if bytes.is_empty() {
        return 0;
    }

    // The caller sized the buffer; a sequence that will not fit is refused
    // rather than truncated, because half an escape sequence is worse than
    // none.
    let capacity = match env.get_array_length(&out) {
        Ok(length) => length as usize,
        Err(_) => return -2,
    };
    if bytes.len() > capacity {
        return -2;
    }

    if env
        .set_byte_array_region(
            &out,
            0,
            unsafe { std::slice::from_raw_parts(bytes.as_ptr() as *const i8, bytes.len()) },
        )
        .is_err()
    {
        return -2;
    }
    bytes.len() as jint
}