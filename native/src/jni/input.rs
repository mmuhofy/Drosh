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
use jni::JNIEnv;

use super::{jint, jlong};
use super::{read_string, session_lock};
use crate::ghostty;

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
    let Some(mut session) = session_lock(handle) else {
        return -1;
    };

    let text = read_string(&mut env, &utf8.into_raw());
    let unshifted = text.chars().next().map(|c| c as u32).unwrap_or(0);

    let key = key_for(key_code).unwrap_or(ghostty::GHOSTTY_KEY_UNIDENTIFIED);
    let mods = mods_for(meta_state);
    let key_action = match action {
        1 => ghostty::GHOSTTY_KEY_ACTION_RELEASE,
        2 => ghostty::GHOSTTY_KEY_ACTION_REPEAT,
        _ => ghostty::GHOSTTY_KEY_ACTION_PRESS,
    };

    let bytes = session.encode_key(key, mods, key_action, &text, unshifted);
    if bytes.is_empty() {
        return 0;
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