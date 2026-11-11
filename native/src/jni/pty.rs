//! The PTY and the process on it, across the JNI boundary.
//!
//! A session is a terminal *and* a process; this file is the process half. It is
//! separate because the two fail for different reasons — a PTY that will not open
//! is a rootfs or a permissions problem, a grid that will not draw is a render
//! problem — and a diagnostic that names the wrong half is worse than none.

use jni::objects::{JByteArray, JClass, JString};
use jni::JNIEnv;

use super::{jbyteArray, jint, jlong};
use super::{new_byte_array, read_string};
use crate::pty::{Pty, Winsize};

/// Maximum bytes one read can move. A frame's worth of output is usually a few
/// hundred; a `cat` of a large file is not.
const READ_CHUNK: usize = 64 * 1024;

/// Spawn a child on a new pseudoterminal.
///
/// `argv` and `environment` are newline-separated, the shape the host already
/// uses so it does not have to learn a second convention.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativePty_nativeSpawn(
    mut env: JNIEnv,
    _class: JClass,
    argv_text: JString,
    env_text: JString,
    cwd_text: JString,
    columns: jint,
    rows: jint,
    cell_width: jint,
    cell_height: jint,
) -> jlong {
    if columns <= 0 || rows <= 0 {
        return 0;
    }

    let argv_raw = read_string(&mut env, &argv_text.into_raw());
    let env_raw = read_string(&mut env, &env_text.into_raw());
    let cwd_raw = read_string(&mut env, &cwd_text.into_raw());

    let argv: Vec<String> = argv_raw
        .lines()
        .filter(|line| !line.is_empty())
        .map(|line| line.to_string())
        .collect();
    let envp: Vec<String> = env_raw
        .lines()
        .filter(|line| !line.is_empty())
        .map(|line| line.to_string())
        .collect();

    if argv.is_empty() {
        return 0;
    }

    let size = Winsize {
        rows: rows as u16,
        cols: columns as u16,
        xpixel: (columns.max(1).saturating_mul(cell_width.max(1))) as u16,
        ypixel: (rows.max(1).saturating_mul(cell_height.max(1))) as u16,
    };
    let cwd = if cwd_raw.is_empty() {
        None
    } else {
        Some(cwd_raw.as_str())
    };

    match Pty::spawn(&argv, &envp, cwd, size) {
        Ok(pty) => Box::into_raw(Box::new(pty)) as usize as jlong,
        Err(error) => {
            log::warn!("drosh-native: the process could not be started: {error:?}");
            0
        }
    }
}

/// Close the PTY and reap the child. A zero handle is a no-op.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativePty_nativeClose(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle <= 0 {
        return;
    }
    drop(unsafe { Box::from_raw(handle as usize as *mut Pty) });
}

/// Read whatever the child has written.
///
/// Returns the number of bytes written into `out`, 0 when there is nothing to
/// read, or a negative code. Zero is the normal answer between keystrokes.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativePty_nativeRead(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    out: JByteArray,
    capacity: jint,
) -> jint {
    if handle <= 0 || capacity <= 0 {
        return -1;
    }
    let pty = unsafe { &mut *(handle as usize as *mut Pty) };

    let wanted = (capacity as usize).min(READ_CHUNK);
    let mut buffer = vec![0u8; wanted];
    match pty.read(&mut buffer) {
        Ok(0) => 0,
        Ok(read) => {
            if env.set_byte_array_region(&out, 0, unsafe {
                std::slice::from_raw_parts(buffer.as_ptr() as *const i8, read)
            })
            .is_err()
            {
                return -2;
            }
            read as jint
        }
        Err(error) => {
            log::debug!("drosh-native: the pty read failed: {error}");
            -3
        }
    }
}

/// Write bytes to the child. Returns 0 on success.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativePty_nativeWrite(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    data: JByteArray,
    length: jint,
) -> jint {
    if handle <= 0 || length < 0 {
        return -1;
    }
    let pty = unsafe { &mut *(handle as usize as *mut Pty) };

    let mut bytes = match env.convert_byte_array(&data) {
        Ok(bytes) => bytes,
        Err(_) => return -2,
    };
    bytes.truncate(length as usize);

    match pty.write(&bytes) {
        Ok(()) => 0,
        Err(error) => {
            log::debug!("drosh-native: the pty write failed: {error}");
            -3
        }
    }
}

/// Tell the kernel the terminal changed size.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativePty_nativeResize(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    columns: jint,
    rows: jint,
    cell_width: jint,
    cell_height: jint,
) -> jint {
    if handle <= 0 || columns <= 0 || rows <= 0 {
        return -1;
    }
    let pty = unsafe { &mut *(handle as usize as *mut Pty) };

    let size = Winsize {
        rows: rows as u16,
        cols: columns as u16,
        xpixel: (columns.max(1).saturating_mul(cell_width.max(1))) as u16,
        ypixel: (rows.max(1).saturating_mul(cell_height.max(1))) as u16,
    };
    match pty.resize(size) {
        Ok(()) => 0,
        Err(error) => {
            log::debug!("drosh-native: the pty resize failed: {error:?}");
            -1
        }
    }
}

/// The child's exit status, or -1 while it runs.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativePty_nativeTryWait(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    if handle <= 0 {
        return -1;
    }
    let pty = unsafe { &mut *(handle as usize as *mut Pty) };
    pty.try_wait().unwrap_or(-1)
}

/// The child's process id, or -1 when there is none.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativePty_nativePid(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    if handle <= 0 {
        return -1;
    }
    // A read: the pid is not a lock-protected fact, so the guard is borrowed.
    let pty = unsafe { &*(handle as usize as *mut Pty) };
    pty.pid()
}

#[allow(dead_code)]
fn _unused(_: jbyteArray) {
    let _ = new_byte_array;
}