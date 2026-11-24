//! The PTY and the process on it, across the JNI boundary.
//!
//! A session is a terminal *and* a process; this file is the process half. It is
//! separate because the two fail for different reasons — a PTY that will not open
//! is a rootfs or a permissions problem, a grid that will not draw is a render
//! problem — and a diagnostic that names the wrong half is worse than none.
//!
//! ## One PTY, two threads
//!
//! The reader thread reads while the main thread writes and resizes. A handle is
//! therefore a `Box<Mutex<Pty>>`, not a `Box<Pty>`: every entry point takes the
//! lock, so no two calls reach the same `Pty` at once.

use std::sync::{Mutex, MutexGuard};

use jni::objects::{JByteArray, JClass, JString};
use jni::JNIEnv;

use super::{jint, jintArray, jlong, jstring};
use super::{new_int_array, new_string, read_string};
use crate::pty::{Pty, Winsize};

/// The mode bits `nativeMode` answers with: bit 0 canonical, bit 1 echo.
const MODE_CANONICAL: jint = 1;
const MODE_ECHO: jint = 2;

/// The locked PTY behind a handle, or None when the handle is not one.
///
/// The guard is held for the caller's whole body: `read_cached` hands back a
/// slice of the PTY's own scratch, so a read is not finished when it returns —
/// it is finished when the bytes have been copied into the Java array.
///
/// A poisoned lock is recovered rather than propagated: a panic in one entry
/// point must not leave the terminal permanently unusable, and the PTY's own
/// state is the kernel's.
fn pty_lock(handle: jlong) -> Option<MutexGuard<'static, Pty>> {
    if handle <= 0 {
        return None;
    }
    let pty = unsafe { &*(handle as usize as *const Mutex<Pty>) };
    Some(pty.lock().unwrap_or_else(|poisoned| {
        log::warn!("drosh-native: a pty lock was poisoned; recovering");
        poisoned.into_inner()
    }))
}

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

    let size = Winsize::from_cells(columns, rows, cell_width, cell_height);
    let cwd = if cwd_raw.is_empty() {
        None
    } else {
        Some(cwd_raw.as_str())
    };

    match Pty::spawn(&argv, &envp, cwd, size) {
        Ok(pty) => Box::into_raw(Box::new(Mutex::new(pty))) as usize as jlong,
        Err(error) => {
            log::warn!("drosh-native: the process could not be started: {error:?}");
            0
        }
    }
}

/// Close the PTY and reap the child. A zero handle is a no-op.
///
/// The caller must have joined its reader first: the handle is freed here, so a
/// read still in flight would touch freed memory.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativePty_nativeClose(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle <= 0 {
        return;
    }
    // Dropping the box closes the master and reaps the child; there is nothing
    // to lock, because nobody may be using the handle any more.
    drop(unsafe { Box::from_raw(handle as usize as *mut Mutex<Pty>) });
}

/// Read whatever the child has written.
///
/// Returns the number of bytes written into `out`, 0 when there is nothing to
/// read, or a negative code. Zero is the normal answer between keystrokes.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativePty_nativeRead(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    out: JByteArray,
    capacity: jint,
) -> jint {
    if capacity <= 0 {
        return -1;
    }
    let Some(mut pty) = pty_lock(handle) else {
        return -1;
    };

    // The scratch is the PTY's own and is reused from read to read: a read is
    // the most frequent call in the process, and a buffer per call is allocator
    // churn in the middle of a frame.
    match pty.read_cached(capacity as usize) {
        Ok(bytes) => {
            if bytes.is_empty() {
                return 0;
            }
            // The guard is still held: `bytes` borrows the PTY's scratch, so
            // the copy into the Java array must finish before the lock can be
            // released to a writer.
            if env.set_byte_array_region(&out, 0, unsafe {
                std::slice::from_raw_parts(bytes.as_ptr() as *const i8, bytes.len())
            })
            .is_err()
            {
                return -2;
            }
            bytes.len() as jint
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
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    data: JByteArray,
    length: jint,
) -> jint {
    if length < 0 {
        return -1;
    }

    // Copied before the lock is taken: the PTY is not needed to read Java's
    // array, and the reader thread should wait no longer than the write itself.
    let mut bytes = match env.convert_byte_array(&data) {
        Ok(bytes) => bytes,
        Err(_) => return -2,
    };
    bytes.truncate(length as usize);

    let Some(mut pty) = pty_lock(handle) else {
        return -1;
    };
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
    if columns <= 0 || rows <= 0 {
        return -1;
    }

    let size = Winsize::from_cells(columns, rows, cell_width, cell_height);
    let Some(mut pty) = pty_lock(handle) else {
        return -1;
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
    let Some(mut pty) = pty_lock(handle) else {
        return -1;
    };
    pty.try_wait().unwrap_or(-1)
}

/// The child's process id, or -1 when there is none.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativePty_nativePid(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    let Some(pty) = pty_lock(handle) else {
        return -1;
    };
    pty.pid()
}

/// The foreground process group's id, or -1 when the kernel has none.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativePty_nativeForegroundPid(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    let Some(pty) = pty_lock(handle) else {
        return -1;
    };
    pty.foreground_pid().unwrap_or(-1)
}

/// The slave side's path, e.g. `/dev/pts/3`, or null when it cannot be read.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativePty_nativeTtyName(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let Some(pty) = pty_lock(handle) else {
        return std::ptr::null_mut();
    };
    match pty.tty_name() {
        Some(name) => {
            let name = name.to_string_lossy();
            new_string(&mut env, &name)
        }
        None => std::ptr::null_mut(),
    }
}

/// The local modes as bits — 1 canonical, 2 echo — or -1 when unknown.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativePty_nativeMode(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    let Some(pty) = pty_lock(handle) else {
        return -1;
    };
    match pty.get_mode() {
        Some((canonical, echo)) => {
            let canonical = if canonical { MODE_CANONICAL } else { 0 };
            let echo = if echo { MODE_ECHO } else { 0 };
            canonical | echo
        }
        None => -1,
    }
}

/// The size the kernel has, as `[rows, cols, xpixel, ypixel]`, or null.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativePty_nativeSize(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jintArray {
    let Some(pty) = pty_lock(handle) else {
        return std::ptr::null_mut();
    };
    match pty.get_size() {
        Some(size) => new_int_array(&mut env, &size.to_array()),
        None => std::ptr::null_mut(),
    }
}
