//! `nativeAmbientTint` — the average background colour of the visible grid,
//! served from Rust so the view can tint its chrome to match the terminal.
//!
//! Computing it here, over the cells the renderer already iterates, avoids
//! copying the grid to Kotlin just to average a few colours.

use jni::objects::{JClass, JIntArray};
use jni::sys::{jint, jlong};
use jni::JNIEnv;

use crate::ffi::{fail, session_or, write_int_array, ErrorCode};

/// Fill `out` (length 3) with the ambient tint `[r, g, b]` and return
/// [`ErrorCode::Ok`], or a negative code when the session is unknown.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_engine_core_NativeCore_nativeAmbientTint<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: jlong,
    out: JIntArray<'local>,
) -> jint {
    let result = (|| -> Result<(), ErrorCode> {
        let session = session_or(id)?;
        // The app's neutral fallback matches `Palette.DEFAULT_BACKGROUND`.
        let tint = session.ambient_tint().unwrap_or([0x18, 0x18, 0x18]);
        write_int_array(&env, &out, &[tint[0] as i32, tint[1] as i32, tint[2] as i32])?;
        Ok(())
    })();

    match result {
        Ok(()) => ErrorCode::Ok.as_jint(),
        Err(code) => fail(code).as_jint(),
    }
}
