//! `nativeColors`: the engine's dynamic colour table.
//!
//! The default palette lives on the Kotlin side (D5); this exports only the
//! entries an application overrode with `OSC 4/10/11/12`, so the renderer and
//! the query replies agree with what the engine actually stores. Kotlin pulls
//! the table when the fingerprint in `state[STATE_COLORS]` changes.

use alacritty_terminal::term::color::COUNT as COLOR_COUNT;
use jni::objects::{JClass, JIntArray};
use jni::sys::{jint, jlong};
use jni::JNIEnv;

use crate::ffi::{finish, int_array_len, session_or, write_int_array, ErrorCode, FfiResult};
use drosh_bridge::snapshot::color_at;

/// `-1`: the application did not override this colour; use the default palette.
pub const UNSET: i32 = -1;

/// Fill `out` with `COUNT` entries: `-1` or `0xRRGGBB`.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_core_NativeCore_nativeColors<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: jlong,
    out: JIntArray<'local>,
) -> jint {
    let result = (|| -> FfiResult<()> {
        let session = session_or(id)?;

        if int_array_len(&env, &out)? < COLOR_COUNT {
            return Err(ErrorCode::BufferTooSmall);
        }

        let table = session.with_term(|term| {
            let mut table = [UNSET; COLOR_COUNT];
            for (index, slot) in table.iter_mut().enumerate() {
                *slot = color_at(term.colors(), index).map_or(UNSET, |rgb| rgb as i32);
            }
            table
        });

        write_int_array(&env, &out, &table)
    })();

    finish(result)
}
