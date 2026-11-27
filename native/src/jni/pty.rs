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

use super::{jboolean, jint, jintArray, jlong, jstring};
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

/// Write `bytes` to the PTY behind a handle, taking its lock.
///
/// The handle is a `Mutex<Pty>`, not a `Pty`: casting it to a `Pty` and
/// writing through it reads the mutex's own header as the terminal's fields.
/// The master descriptor then comes from the lock's word instead of the PTY,
/// and the bytes go to whatever descriptor that happens to be — which is why a
/// key path that took that shortcut looked like a keyboard that had stopped
/// delivering Enter.
///
/// Returns false when the handle is not a PTY or the write failed. The caller
/// is told nothing else: a key that cannot be delivered is not an error a
/// terminal can show the user.
pub(crate) fn write_locked(handle: jlong, bytes: &[u8]) -> bool {
    match pty_lock(handle) {
        Some(mut pty) => match pty.write(bytes) {
            Ok(()) => true,
            Err(error) => {
                log::debug!("drosh-native: the pty write failed: {error}");
                false
            }
        },
        None => false,
    }
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

/// Write as much of a slice as the kernel takes without blocking.
///
/// Returns the bytes written, 0 when the terminal's input buffer is full, or a
/// negative code on error. The caller keeps the rest and retries when the fd
/// becomes writable.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativePty_nativeWriteSome(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    data: JByteArray,
    offset: jint,
    length: jint,
) -> jint {
    if offset < 0 || length < 0 {
        return -1;
    }
    if length == 0 {
        return 0;
    }
    let Some(mut pty) = pty_lock(handle) else {
        return -1;
    };
    // Only the slice the caller named is fetched, so a retry after a full
    // buffer does not copy the whole paste again.
    let mut bytes = vec![0 as jni::sys::jbyte; length as usize];
    if env.get_byte_array_region(&data, offset, &mut bytes).is_err() {
        return -2;
    }
    let slice = unsafe { std::slice::from_raw_parts(bytes.as_ptr() as *const u8, bytes.len()) };
    match pty.write_some(slice) {
        Ok(written) => written as jint,
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

/// Wait until the master is readable or closed, a queued write wakes the
/// reader, or the timeout elapses.
///
/// The lock is taken only to copy the fds and released before the wait: a
/// blocking `poll` under the lock would make a write from the main thread wait
/// out the whole timeout. The fds are stable for the PTY's life — set once and
/// closed only when the handle is dropped, after the reader has stopped — so
/// polling copies of them is safe.
///
/// Returns 1 when the master is ready (data or a hangup on the child's side),
/// 0 on timeout or when only the wake pipe fired, and -1 on a bad handle or a
/// failed call. The caller treats 0 as "nothing to read, but my queue may have
/// work".
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativePty_nativePoll(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    timeout_ms: jint,
    writable: jboolean,
) -> jint {
    // The guard is dropped at the end of this match, before the wait.
    let (master, wake) = match pty_lock(handle) {
        Some(pty) => (pty.master(), pty.wake_read()),
        None => return -1,
    };

    // A pending write means the wait must also report when the input buffer
    // has drained, or a full paste would sit until unrelated output arrives.
    let master_events = if writable != 0 {
        libc::POLLIN | libc::POLLOUT
    } else {
        libc::POLLIN
    };

    // A negative fd is ignored by `poll`, so a missing wake pipe needs no
    // separate branch: the array just carries an entry nothing watches.
    let mut descriptors = [
        libc::pollfd {
            fd: master,
            events: master_events,
            revents: 0,
        },
        libc::pollfd {
            fd: wake,
            events: libc::POLLIN,
            revents: 0,
        },
    ];
    let timeout = timeout_ms.max(0);
    let ready = unsafe {
        libc::poll(
            descriptors.as_mut_ptr(),
            descriptors.len() as libc::nfds_t,
            timeout,
        )
    };
    if ready < 0 {
        log::debug!(
            "drosh-native: the pty wait failed: {}",
            std::io::Error::last_os_error()
        );
        return -1;
    }

    // The wake pipe fires once per poke; empty it here so the next wait blocks
    // instead of returning on the leftover byte.
    if wake >= 0 && descriptors[1].revents != 0 {
        if let Some(mut pty) = pty_lock(handle) {
            pty.drain_wake();
        }
    }

    if descriptors[0].revents != 0 {
        1
    } else {
        0
    }
}

/// Poke the reader awake. False when there is no wake pipe.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativePty_nativeWake(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    let Some(mut pty) = pty_lock(handle) else {
        return 0;
    };
    if pty.wake() {
        1
    } else {
        0
    }
}

/// Whether the pty has a wake pipe, or false when it does not.
///
/// Without one a queued write would wait out the reader's poll, so the caller
/// writes straight through instead of queueing.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativePty_nativeHasWake(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    let Some(pty) = pty_lock(handle) else {
        return 0;
    };
    if pty.wake_read() >= 0 {
        1
    } else {
        0
    }
}
