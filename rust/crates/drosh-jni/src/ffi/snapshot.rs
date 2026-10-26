//! `native*` functions for the snapshot path.
//!
//! Kotlin owns both buffers (decision D5): a direct `ByteBuffer` of
//! `rows * columns` cell records and an `IntArray` of `1 + 3 * rows` ints. Rust
//! only writes into them, so a frame costs one copy of the damaged cells and no
//! allocation on either side.

use std::slice;

use jni::objects::{JByteBuffer, JClass, JIntArray};
use jni::sys::{jint, jlong};
use jni::JNIEnv;

use crate::ffi::{finish, int_array_len, session_or, write_int_array, ErrorCode, FfiResult};
use drosh_bridge::snapshot::STATE_LEN;

/// Copy the damaged cells into `cells` and the damage list into `damage_out`.
///
/// `damage_out[0]` ends up as `-1` (the whole viewport was copied) or the
/// number of damaged lines, each followed by `row`, `left`, `right`. The return
/// value is an [`ErrorCode`].
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_core_NativeCore_nativeSnapshot<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: jlong,
    cells: JByteBuffer<'local>,
    damage_out: JIntArray<'local>,
) -> jint {
    let result = (|| -> FfiResult<()> {
        let session = session_or(id)?;

        let capacity =
            env.get_direct_buffer_capacity(&cells).map_err(|_| ErrorCode::InvalidArgument)?;
        let address =
            env.get_direct_buffer_address(&cells).map_err(|_| ErrorCode::InvalidArgument)?;
        let damage_capacity = int_array_len(&env, &damage_out)?;

        // SAFETY: `address`/`capacity` describe a direct `ByteBuffer` that
        // Kotlin keeps alive for the duration of this call; we hold a local
        // reference to it while writing.
        let buffer = unsafe { slice::from_raw_parts_mut(address, capacity) };

        session.take_snapshot(buffer).map_err(ErrorCode::from)?;

        session.with_damage(|damage| {
            if damage.len() > damage_capacity {
                return Err(ErrorCode::BufferTooSmall);
            }
            write_int_array(&env, &damage_out, damage)
        })
    })();

    finish(result)
}

/// Copy the damaged cells **and** the renderer state in one lock hold.
///
/// The frame needs both halves of one viewport, and taking the term's lock
/// twice let a scroll land between them — the cells of one position drawn with
/// the offset of the next, which is the one-frame jolt a drag showed. See
/// `Session::take_snapshot_with_state`.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_core_NativeCore_nativeSnapshotAndState<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: jlong,
    cells: JByteBuffer<'local>,
    damage_out: JIntArray<'local>,
    state_out: JIntArray<'local>,
) -> jint {
    let result = (|| -> FfiResult<()> {
        let session = session_or(id)?;

        let capacity =
            env.get_direct_buffer_capacity(&cells).map_err(|_| ErrorCode::InvalidArgument)?;
        let address =
            env.get_direct_buffer_address(&cells).map_err(|_| ErrorCode::InvalidArgument)?;
        let damage_capacity = int_array_len(&env, &damage_out)?;

        if int_array_len(&env, &state_out)? < STATE_LEN {
            return Err(ErrorCode::BufferTooSmall);
        }

        // SAFETY: `address`/`capacity` describe a direct `ByteBuffer` that
        // Kotlin keeps alive for the duration of this call; we hold a local
        // reference to it while writing.
        let buffer = unsafe { slice::from_raw_parts_mut(address, capacity) };

        let mut state = [0i32; STATE_LEN];
        session.take_snapshot_with_state(buffer, &mut state).map_err(ErrorCode::from)?;

        session.with_damage(|damage| {
            if damage.len() > damage_capacity {
                return Err(ErrorCode::BufferTooSmall);
            }
            write_int_array(&env, &damage_out, damage)
        })?;
        write_int_array(&env, &state_out, &state)
    })();

    finish(result)
}

/// Fill `state_out` with the renderer state that is not part of the cell
/// buffer: size, cursor, display offset and mode bits (see
/// [`drosh_bridge::snapshot::state_index`]).
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_core_NativeCore_nativeState<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: jlong,
    state_out: JIntArray<'local>,
) -> jint {
    let result = (|| -> FfiResult<()> {
        let session = session_or(id)?;

        if int_array_len(&env, &state_out)? < STATE_LEN {
            return Err(ErrorCode::BufferTooSmall);
        }

        let state = session.state();
        write_int_array(&env, &state_out, &state)
    })();

    finish(result)
}
