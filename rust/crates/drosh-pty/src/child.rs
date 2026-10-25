//! Starting the program on the slave side of the PTY.
//!
//! Two ways, tried in order:
//!
//! 1. **Login tty** — `setsid` + `TIOCSCTTY` + `dup2` in `pre_exec`, so the
//!    child owns the controlling terminal the way a login session does.
//! 2. **Slave stdio** — the slave attached as the child's stdin/stdout/stderr,
//!    without a controlling terminal. Some OEM kernels refuse the first, and
//!    the Termi lesson is that the second still works there.

use std::ffi::CString;
use std::io;
use std::os::unix::io::RawFd;
use std::os::unix::process::CommandExt;
use std::process::{Child, Command, Stdio};

use super::pty::TIOCSCTTY;
use super::PtyConfig;

/// Start the child with a controlling terminal.
///
/// `spawn` does the exec itself. Doing it inside `pre_exec` — which an earlier
/// version did, through a pipe that reported why an exec failed — deadlocks:
/// `std::process::Command` writes its own handshake after `pre_exec` returns,
/// so a `pre_exec` that execs never returns and `spawn` waits for a handshake
/// that cannot come. The session started and then hung with the child running
/// and the caller still inside this call.
pub(crate) fn spawn_login_tty(config: &PtyConfig, slave_fd: RawFd) -> io::Result<Child> {
    let mut command = Command::new(&config.program);
    configure_command(&mut command, config);
    // Replaced by the slave in `pre_exec`; std sets these up first.
    command.stdin(Stdio::null());
    command.stdout(Stdio::null());
    command.stderr(Stdio::null());

    let cwd = cwd_c_string(config)?;

    // SAFETY: `slave_fd` stays open in the parent until the child has been
    // spawned, and the closure only calls async-signal-safe libc functions.
    unsafe {
        command.pre_exec(move || {
            if let Some(dir) = cwd.as_ref() {
                chdir(dir)?;
            }
            setup_child_tty(slave_fd)
        });
    }

    command.spawn()
}

/// Start the child with the slave as its stdio, without a controlling terminal.
pub(crate) fn spawn_slave_stdio(config: &PtyConfig, slave: &std::fs::File) -> io::Result<Child> {
    let mut command = Command::new(&config.program);
    configure_command(&mut command, config);
    command.stdin(Stdio::from(slave.try_clone()?));
    command.stdout(Stdio::from(slave.try_clone()?));
    command.stderr(Stdio::from(slave.try_clone()?));

    let cwd = cwd_c_string(config)?;

    // SAFETY: the closure only calls async-signal-safe libc functions.
    unsafe {
        command.pre_exec(move || match cwd.as_ref() {
            Some(dir) => chdir(dir),
            None => Ok(()),
        });
    }

    command.spawn()
}

/// The login-tty sequence, run between `fork` and `exec`.
///
/// Only async-signal-safe calls belong here: no logging, no allocation. A
/// failing `setsid`/`TIOCSCTTY` is survivable — the child still writes to a
/// usable PTY — a failing `dup2` is not.
fn setup_child_tty(slave_fd: RawFd) -> io::Result<()> {
    // SAFETY: both calls only affect this process, which is the forked child.
    unsafe {
        libc::setsid();
        libc::ioctl(slave_fd, TIOCSCTTY, 0);
    }

    for target in 0..3 {
        // SAFETY: `target` is 0, 1 or 2 and `slave_fd` is open in the child.
        if unsafe { libc::dup2(slave_fd, target) } < 0 {
            return Err(io::Error::last_os_error());
        }
    }
    Ok(())
}

/// The arguments and the environment both paths share.
fn configure_command(command: &mut Command, config: &PtyConfig) {
    command.args(&config.args);
    command.env_clear();
    #[cfg(target_os = "android")]
    command.env("PATH", super::DEFAULT_ANDROID_PATH);
    for (key, value) in &config.env {
        command.env(key, value);
    }
}

fn cwd_c_string(config: &PtyConfig) -> io::Result<Option<CString>> {
    match &config.cwd {
        Some(dir) => CString::new(dir.as_str())
            .map(Some)
            .map_err(|_| io::Error::other("the working directory contains a NUL byte")),
        None => Ok(None),
    }
}

fn chdir(dir: &CString) -> io::Result<()> {
    // SAFETY: `dir` is a valid NUL-terminated path.
    if unsafe { libc::chdir(dir.as_ptr()) } == 0 {
        Ok(())
    } else {
        Err(io::Error::last_os_error())
    }
}
