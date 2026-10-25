//! PTY creation and the child process on the other end (decision D8).
//!
//! `portable-pty` does not compile for `target_os = "android"` (its `termios`
//! dependency is not `cfg`'d for Android), so the kernel's PTY syscalls are
//! called through `libc` directly. The sequence is the one Termi uses on
//! device:
//!
//! 1. [`pty`] opens the master/slave pair.
//! 2. [`child`] starts the program on the slave side — as a login tty when the
//!    kernel allows it, with the slave as plain stdio when it does not.
//! 3. This module owns the two together and implements `alacritty_terminal`'s
//!    `EventedPty`/`OnResize` so the engine's own event loop can poll them.
//!
//! The split is not decoration: the pair is syscalls and the child is
//! `std::process`, and neither needs the other's imports.

mod child;
mod pty;

use std::fs::File;
use std::io::{self, ErrorKind, Read};
use std::os::unix::io::{AsRawFd, RawFd};
use std::os::unix::net::UnixStream;
use std::process::Child;
use std::sync::Arc;
use std::time::{Duration, Instant};

use alacritty_terminal::event::{OnResize, WindowSize};
use alacritty_terminal::tty::{ChildEvent, EventedPty, EventedReadWrite};
use polling::{Event as PollEvent, PollMode, Poller};
use signal_hook::SigId;

use pty::TIOCSWINSZ;

/// The master must not block the event loop's poll.
fn set_nonblocking(fd: RawFd) -> io::Result<()> {
    // SAFETY: `fd` is the open master and `F_GETFL`/`F_SETFL` only read and
    // write its status flags.
    let flags = unsafe { libc::fcntl(fd, libc::F_GETFL) };
    if flags < 0 {
        return Err(io::Error::last_os_error());
    }
    // SAFETY: as above.
    let result = unsafe { libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) };
    if result < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

/// Event keys of `alacritty_terminal`'s event loop.
///
/// They are private in the engine, so our `EventedPty` implementation has to
/// mirror them: the loop compares `event.key` against its own constants (0 for
/// PTY read/write readiness, 1 for child events) and would ignore everything
/// otherwise. Re-verify on every engine upgrade — recorded in `TODO.md`.
const PTY_READ_WRITE_TOKEN: usize = 0;
const PTY_CHILD_EVENT_TOKEN: usize = 1;

/// How the child process is started and how big the PTY starts out.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PtyConfig {
    /// Program to run, e.g. `/system/bin/sh` or a chroot-ng path.
    pub program: String,
    /// Arguments (without `argv[0]`).
    pub args: Vec<String>,
    /// Extra environment variables; they win over the defaults below.
    pub env: Vec<(String, String)>,
    /// Working directory for the child.
    pub cwd: Option<String>,
    pub columns: u16,
    pub rows: u16,
    pub cell_width: u16,
    pub cell_height: u16,
}

/// Minimal PATH for the Android side. The app can override it through
/// [`PtyConfig::env`].
#[cfg(target_os = "android")]
const DEFAULT_ANDROID_PATH: &str = "/product/bin:/apex/com.android.runtime/bin:/system_ext/bin:/system/bin:/system/xbin:/vendor/bin";

/// A PTY master plus the child process on the slave side.
pub struct LocalPty {
    master: File,
    child: Child,
    signals: UnixStream,
    sig_id: SigId,
}

impl LocalPty {
    /// Open a PTY and start the child on the slave side.
    pub fn spawn(config: &PtyConfig) -> io::Result<Self> {
        let (master, slave) =
            pty::open_pair(config.columns, config.rows, config.cell_width, config.cell_height)?;

        // Install the SIGCHLD self-pipe before the child exists, so even a
        // child that exits immediately is still seen by the event loop.
        let (signals, sig_id) = register_sigchld()?;

        let child = match child::spawn_login_tty(config, slave.as_raw_fd()) {
            Ok(child) => child,
            Err(err) => {
                log::warn!("drosh-core: login-tty spawn failed ({err}), retrying with slave stdio");
                child::spawn_slave_stdio(config, &slave)?
            }
        };

        // The child owns the slave side from here on.
        drop(slave);
        set_nonblocking(master.as_raw_fd())?;

        Ok(Self { master, child, signals, sig_id })
    }

    /// Process id of the child.
    pub fn pid(&self) -> u32 {
        self.child.id()
    }
}

impl Drop for LocalPty {
    fn drop(&mut self) {
        // Hang up: closing the master (below, after this) ends the terminal's
        // session, SIGHUP tells the shell to go away.
        let _ = unsafe { libc::kill(self.child.id() as i32, libc::SIGHUP) };

        // Do not block forever on a child that ignores SIGHUP.
        let deadline = Instant::now() + Duration::from_millis(200);
        while Instant::now() < deadline {
            match self.child.try_wait() {
                Ok(Some(_)) | Err(_) => break,
                Ok(None) => std::thread::sleep(Duration::from_millis(10)),
            }
        }

        if matches!(self.child.try_wait(), Ok(None)) {
            let _ = unsafe { libc::kill(self.child.id() as i32, libc::SIGKILL) };
            let _ = self.child.wait();
        }

        signal_hook::low_level::unregister(self.sig_id);
    }
}

impl AsRawFd for LocalPty {
    fn as_raw_fd(&self) -> RawFd {
        self.master.as_raw_fd()
    }
}

impl EventedReadWrite for LocalPty {
    type Reader = File;
    type Writer = File;

    unsafe fn register(
        &mut self,
        poll: &Arc<Poller>,
        mut interest: PollEvent,
        mode: PollMode,
    ) -> io::Result<()> {
        interest.key = PTY_READ_WRITE_TOKEN;
        unsafe {
            poll.add_with_mode(&self.master, interest, mode)?;
            poll.add_with_mode(
                &self.signals,
                PollEvent::readable(PTY_CHILD_EVENT_TOKEN),
                PollMode::Level,
            )?;
        }
        Ok(())
    }

    fn reregister(
        &mut self,
        poll: &Arc<Poller>,
        mut interest: PollEvent,
        mode: PollMode,
    ) -> io::Result<()> {
        interest.key = PTY_READ_WRITE_TOKEN;
        poll.modify_with_mode(&self.master, interest, mode)?;
        poll.modify_with_mode(
            &self.signals,
            PollEvent::readable(PTY_CHILD_EVENT_TOKEN),
            PollMode::Level,
        )?;
        Ok(())
    }

    fn deregister(&mut self, poll: &Arc<Poller>) -> io::Result<()> {
        poll.delete(&self.master)?;
        poll.delete(&self.signals)?;
        Ok(())
    }

    fn reader(&mut self) -> &mut Self::Reader {
        &mut self.master
    }

    fn writer(&mut self) -> &mut Self::Writer {
        &mut self.master
    }
}

impl EventedPty for LocalPty {
    fn next_child_event(&mut self) -> Option<ChildEvent> {
        // One byte on the pipe means "SIGCHLD"; drain it so the level-triggered
        // poller does not fire again.
        let mut buffer = [0u8; 1];
        match self.signals.read(&mut buffer) {
            Ok(_) => {}
            Err(err) if err.kind() == ErrorKind::WouldBlock => return None,
            Err(err) => {
                log::warn!("drosh-core: SIGCHLD pipe read failed: {err}");
                return None;
            }
        }

        match self.child.try_wait() {
            Ok(Some(status)) => Some(ChildEvent::Exited(Some(status))),
            Ok(None) => None,
            Err(err) => {
                log::warn!("drosh-core: child wait failed: {err}");
                None
            }
        }
    }
}

impl OnResize for LocalPty {
    fn on_resize(&mut self, window_size: WindowSize) {
        let winsize = libc::winsize {
            ws_row: window_size.num_lines,
            ws_col: window_size.num_cols,
            ws_xpixel: window_size.num_cols.saturating_mul(window_size.cell_width),
            ws_ypixel: window_size.num_lines.saturating_mul(window_size.cell_height),
        };

        // SAFETY: `TIOCSWINSZ` reads a `winsize` from the pointer given to it.
        let result = unsafe { libc::ioctl(self.master.as_raw_fd(), TIOCSWINSZ, &winsize) };
        if result != 0 {
            log::warn!("drosh-core: TIOCSWINSZ failed: {}", io::Error::last_os_error());
        }
    }
}

/// The SIGCHLD self-pipe: the child's exit reaches the poller as a readable
/// descriptor rather than as a signal to intercept.
fn register_sigchld() -> io::Result<(UnixStream, SigId)> {
    let (sender, receiver) = UnixStream::pair()?;
    let sig_id = signal_hook::low_level::pipe::register(signal_hook::consts::SIGCHLD, sender)
        .map_err(|err| io::Error::other(format!("SIGCHLD handler registration failed: {err}")))?;
    receiver.set_nonblocking(true)?;
    Ok((receiver, sig_id))
}
