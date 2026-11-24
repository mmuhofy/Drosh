//! The pseudoterminal and the child process on the far end of it.
//!
//! Taken from Ghostty's `src/pty.zig` (MIT), reduced to what Android needs. The
//! structure is Ghostty's on purpose: `forkpty` + `setsid` + `TIOCSCTTY` is the
//! part of a terminal that is easy to get subtly wrong — a missing `setsid`
//! leaves the shell in the app's process group, so a Ctrl+C kills the app
//! instead of the command, and a missing `TIOCSCTTY` leaves `^Z` doing nothing.
//!
//! `forkpty` does the openpty, the setsid and the TIOCSCTTY in one call, and
//! bionic has had it since API 23. What is left is the child's side of the fork:
//! only async-signal-safe calls, because the JVM may have had other threads
//! mid-allocation when it happened.
//!
//! The child also starts from a clean slate. The JVM ignores `SIGPIPE` and
//! installs handlers for the rest of the fatal signals, and both survive
//! `execve`: a shell that inherited the ignore cannot be killed by a closed
//! pipe, and a JVM handler would run inside the guest. So the child resets the
//! dispositions and clears the mask before it execs.

use std::ffi::{CStr, CString};
use std::io;
use std::os::fd::RawFd;

/// The window size, in cells and pixels.
///
/// The same layout as `struct winsize`, so it can be handed to `ioctl` directly.
/// The pixel sizes are what a program uses to place images and to decide whether
/// a mouse is precise enough for a TUI.
#[derive(Debug, Clone, Copy, Default)]
pub struct Winsize {
    pub rows: u16,
    pub cols: u16,
    pub xpixel: u16,
    pub ypixel: u16,
}

impl Winsize {
    /// The size a UI knows: a cell count and the pixel size of one cell.
    ///
    /// A zero cell size must not zero the pixel fields — a program uses them to
    /// place images and to decide whether a mouse is precise enough for a TUI —
    /// and the product must saturate rather than wrap, or a large screen would
    /// be reported as a small one.
    pub fn from_cells(columns: i32, rows: i32, cell_width: i32, cell_height: i32) -> Self {
        Self {
            rows: rows.clamp(1, u16::MAX as i32) as u16,
            cols: columns.clamp(1, u16::MAX as i32) as u16,
            xpixel: pixel_extent(columns, cell_width),
            ypixel: pixel_extent(rows, cell_height),
        }
    }

    /// The size the kernel has, in the same layout.
    fn from_libc(size: libc::winsize) -> Self {
        Self {
            rows: size.ws_row,
            cols: size.ws_col,
            xpixel: size.ws_xpixel,
            ypixel: size.ws_ypixel,
        }
    }

    fn to_libc(self) -> libc::winsize {
        libc::winsize {
            ws_row: self.rows,
            ws_col: self.cols,
            ws_xpixel: self.xpixel,
            ws_ypixel: self.ypixel,
        }
    }

    /// The size as the `[rows, cols, xpixel, ypixel]` array the JNI hands on.
    pub fn to_array(self) -> [i32; 4] {
        [
            self.rows as i32,
            self.cols as i32,
            self.xpixel as i32,
            self.ypixel as i32,
        ]
    }
}

/// The pixel size of `cells` cells that are `cell` pixels each, saturating at
/// what a `winsize` can carry.
fn pixel_extent(cells: i32, cell: i32) -> u16 {
    let extent = cells.max(1).saturating_mul(cell.max(1));
    extent.min(u16::MAX as i32) as u16
}

/// Why a PTY call failed.
#[derive(Debug)]
pub enum PtyError {
    /// `forkpty` refused.
    ForkFailed(io::Error),
    /// The child could not be given its working directory or program.
    ExecFailed,
    /// `ioctl(TIOCSWINSZ)` refused.
    ResizeFailed(io::Error),
    /// The master is closed.
    NotOpen,
}

/// The most one read can move.
///
/// A frame's worth of output is usually a few kilobytes; 64 KB covers a flood
/// and is small enough to stay resident.
pub(crate) const READ_CHUNK: usize = 64 * 1024;

/// One non-blocking read from the master side.
///
/// Split out from `Pty::read` so the cached read can use the same loop without
/// borrowing the `Pty` twice.
fn read_fd(fd: RawFd, buffer: &mut [u8]) -> io::Result<usize> {
    if fd < 0 {
        return Err(io::Error::from_raw_os_error(libc::EBADF));
    }
    loop {
        let n = unsafe { libc::read(fd, buffer.as_mut_ptr() as *mut libc::c_void, buffer.len()) };
        if n > 0 {
            return Ok(n as usize);
        }
        if n == 0 {
            return Ok(0);
        }
        let error = io::Error::last_os_error();
        match error.raw_os_error() {
            // No data right now.
            Some(libc::EAGAIN) => return Ok(0),
            // The child is gone and the slave side closed: a clean end, not a
            // failure.
            Some(libc::EIO) => return Ok(0),
            Some(libc::EINTR) => continue,
            _ => return Err(error),
        }
    }
}

/// The signals the child must not inherit from the JVM.
///
/// The list is Ghostty's `childPreExec`: every signal whose disposition the
/// JVM sets and whose handler would then be inherited by the guest.
const INHERITED_SIGNALS: [libc::c_int; 13] = [
    libc::SIGABRT,
    libc::SIGALRM,
    libc::SIGBUS,
    libc::SIGCHLD,
    libc::SIGFPE,
    libc::SIGHUP,
    libc::SIGILL,
    libc::SIGINT,
    libc::SIGPIPE,
    libc::SIGSEGV,
    libc::SIGTERM,
    libc::SIGQUIT,
    libc::SIGTRAP,
];

/// Put the child back in the state a freshly started process expects.
///
/// Runs between `fork` and `exec`, so only async-signal-safe calls: the JVM may
/// have had another thread inside the allocator when the fork happened, and
/// anything that takes a libc lock can deadlock the child forever. `sigaction`,
/// `sigemptyset` and `sigprocmask` are on POSIX's safe list and write only the
/// child's own tables.
fn child_pre_exec() {
    unsafe {
        let mut action: libc::sigaction = std::mem::zeroed();
        action.sa_sigaction = libc::SIG_DFL;
        action.sa_flags = 0;
        libc::sigemptyset(&mut action.sa_mask);
        for signal in INHERITED_SIGNALS {
            libc::sigaction(signal, &action, std::ptr::null_mut());
        }

        // A blocked signal stays blocked across `execve`. The JVM blocks a few
        // of its own for the threads it starts, and the shell would inherit
        // them still blocked.
        let mut mask: libc::sigset_t = std::mem::zeroed();
        libc::sigemptyset(&mut mask);
        libc::sigprocmask(libc::SIG_SETMASK, &mask, std::ptr::null_mut());
    }
}

/// The master's own settings, after `forkpty` has made it.
///
/// Best effort: a master that refuses either of these still reads and writes,
/// so a failure is logged and the spawn continues.
fn configure_master(fd: RawFd) {
    unsafe {
        // Only the slave belongs to the child. `forkpty` closes the master in
        // the child already; this keeps it out of any later `exec` as well.
        let flags = libc::fcntl(fd, libc::F_GETFD);
        if flags < 0 || libc::fcntl(fd, libc::F_SETFD, flags | libc::FD_CLOEXEC) < 0 {
            log::debug!(
                "drosh-native: the pty master could not be marked close-on-exec: {}",
                io::Error::last_os_error()
            );
        }

        // UTF-8 input. Without `IUTF8` the line discipline counts bytes rather
        // than characters, so a backspace inside a multi-byte character erases
        // one byte of it and leaves the rest on the line.
        let mut attrs: libc::termios = std::mem::zeroed();
        if libc::tcgetattr(fd, &mut attrs) != 0 {
            log::debug!(
                "drosh-native: the pty termios could not be read: {}",
                io::Error::last_os_error()
            );
        } else {
            attrs.c_iflag |= libc::IUTF8;
            if libc::tcsetattr(fd, libc::TCSANOW, &attrs) != 0 {
                log::debug!(
                    "drosh-native: the pty could not be put in UTF-8 mode: {}",
                    io::Error::last_os_error()
                );
            }
        }
    }
}

/// The two local-mode flags the UI mirrors, read from a raw `c_lflag`.
///
/// A password prompt clears echo; a full-screen program clears canonical mode.
/// The answer is `(canonical, echo)`.
fn mode_from_lflag(lflag: libc::tcflag_t) -> (bool, bool) {
    (lflag & libc::ICANON != 0, lflag & libc::ECHO != 0)
}

/// A pipe the writer pokes to wake the reader's `poll`.
///
/// The reader blocks inside `poll`, so a write queued from another thread has no
/// way to cut that wait short without a descriptor it can make readable. This
/// pipe is that descriptor: one byte means "there is work", and the reader
/// drains it on the next wakeup. Both ends are non-blocking and close-on-exec,
/// so neither leaks into the child and a drain never blocks.
///
/// Returns `(-1, -1)` when the pipe cannot be made; the reader then falls back
/// to its poll timeout.
fn open_wake_pipe() -> (RawFd, RawFd) {
    let mut fds = [0 as RawFd; 2];
    if unsafe { libc::pipe(fds.as_mut_ptr()) } != 0 {
        log::debug!(
            "drosh-native: the pty wake pipe could not be made: {}",
            io::Error::last_os_error()
        );
        return (-1, -1);
    }
    for fd in fds {
        unsafe {
            let flags = libc::fcntl(fd, libc::F_GETFL);
            if flags >= 0 {
                libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK);
            }
            let descriptor = libc::fcntl(fd, libc::F_GETFD);
            if descriptor >= 0 {
                libc::fcntl(fd, libc::F_SETFD, descriptor | libc::FD_CLOEXEC);
            }
        }
    }
    (fds[0], fds[1])
}

/// A pseudoterminal pair and the child process on the slave side.
///
/// The JNI layer keeps one of these behind a mutex: the reader thread reads
/// while the main thread writes and resizes, and those calls must take turns.
pub struct Pty {
    /// The master side: the app reads and writes this.
    master: RawFd,
    /// The read end of the wake pipe: `poll` watches it so a queued write can
    /// cut the reader's wait short. -1 when the pipe could not be made.
    wake_read: RawFd,
    /// The write end of the wake pipe. -1 when the pipe could not be made.
    wake_write: RawFd,
    /// The child's pid, or -1 once reaped.
    child: libc::pid_t,
    /// The size last set, so a resize to the same size is free.
    size: Winsize,
    /// The read scratch, reused from read to read.
    ///
    /// A read runs every few milliseconds while a program runs and moves up to
    /// [READ_CHUNK] bytes: a buffer allocated per call is tens of megabytes of
    /// allocator churn a second, and that is latency in the middle of a frame.
    scratch: Vec<u8>,
}

impl Pty {
    /// Open a pseudoterminal and fork the child into it.
    pub fn spawn(
        argv: &[String],
        envp: &[String],
        cwd: Option<&str>,
        size: Winsize,
    ) -> Result<Self, PtyError> {
        if argv.is_empty() {
            return Err(PtyError::ExecFailed);
        }

        // The vectors `execve` wants. Built before the fork so the child touches
        // nothing but `execve` and the two calls before it.
        let argv_c: Vec<CString> = argv
            .iter()
            .map(|arg| CString::new(arg.as_str()).unwrap_or_default())
            .collect();
        let envp_c: Vec<CString> = envp
            .iter()
            .map(|entry| CString::new(entry.as_str()).unwrap_or_default())
            .collect();
        let cwd_c = cwd.and_then(|dir| CString::new(dir).ok());

        let mut argv_ptr: Vec<*const libc::c_char> = argv_c.iter().map(|s| s.as_ptr()).collect();
        argv_ptr.push(std::ptr::null());
        let mut envp_ptr: Vec<*const libc::c_char> = envp_c.iter().map(|s| s.as_ptr()).collect();
        envp_ptr.push(std::ptr::null());

        let mut winsize = size.to_libc();
        let mut master: libc::c_int = -1;

        // `forkpty` returns 0 in the child, the pid in the parent, -1 on
        // failure. It does the openpty, the setsid and the TIOCSCTTY.
        let pid = unsafe {
            libc::forkpty(&mut master, std::ptr::null_mut(), std::ptr::null(), &mut winsize)
        };

        if pid < 0 {
            return Err(PtyError::ForkFailed(io::Error::last_os_error()));
        }

        if pid == 0 {
            // --- child ---------------------------------------------------
            //
            // Only async-signal-safe calls from here to exec. The JVM may have
            // had other threads mid-allocation when this forked, so anything
            // that takes a libc lock can deadlock the child forever.
            child_pre_exec();
            unsafe {
                if let Some(dir) = cwd_c.as_ref() {
                    libc::chdir(dir.as_ptr());
                }
                libc::execve(argv_ptr[0], argv_ptr.as_ptr(), envp_ptr.as_ptr());
                // execve only returns on failure.
                libc::_exit(127);
            }
        }

        // --- parent ------------------------------------------------------
        if master < 0 {
            unsafe { libc::kill(pid, libc::SIGKILL) };
            return Err(PtyError::ForkFailed(io::Error::last_os_error()));
        }

        configure_master(master);

        // The pipe the writer pokes to wake the reader's `poll`. Made in the
        // parent, after the fork, so the child never sees it; close-on-exec and
        // non-blocking on both ends. A failure degrades to the poll timeout
        // rather than to a broken session.
        let (wake_read, wake_write) = open_wake_pipe();

        // Non-blocking: the caller polls. A blocking read here would freeze
        // whichever thread called it, and that thread is the render loop.
        unsafe {
            let flags = libc::fcntl(master, libc::F_GETFL);
            if flags >= 0 {
                libc::fcntl(master, libc::F_SETFL, flags | libc::O_NONBLOCK);
            }
        }

        Ok(Self {
            master,
            wake_read,
            wake_write,
            child: pid,
            size,
            scratch: Vec::with_capacity(READ_CHUNK),
        })
    }

    /// Read whatever is available.
    ///
    /// Zero means "nothing right now", which is the normal state between
    /// keystrokes and is not an error.
    pub fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        read_fd(self.master, buffer)
    }

    /// Read into the PTY's own scratch and hand back the bytes.
    ///
    /// The reader loop calls this; the alternative of handing it a fresh buffer
    /// puts an allocation on every read, and reads are the most frequent call in
    /// the process.
    pub fn read_cached(&mut self, wanted: usize) -> io::Result<&[u8]> {
        let len = wanted.min(READ_CHUNK);
        self.scratch.resize(len, 0);
        if len == 0 {
            return Ok(&self.scratch);
        }
        let read = read_fd(self.master, &mut self.scratch)?;
        self.scratch.truncate(read);
        Ok(&self.scratch)
    }

    /// Write all of `bytes`, or fail.
    ///
    /// A partial write is retried rather than truncated: a truncated escape
    /// sequence leaves the parser mid-state and the next byte is read as its
    /// continuation.
    pub fn write(&mut self, bytes: &[u8]) -> io::Result<()> {
        if self.master < 0 {
            return Err(io::Error::from_raw_os_error(libc::EBADF));
        }
        let mut offset = 0;
        while offset < bytes.len() {
            let n = unsafe {
                libc::write(
                    self.master,
                    bytes[offset..].as_ptr() as *const libc::c_void,
                    bytes.len() - offset,
                )
            };
            if n > 0 {
                offset += n as usize;
                continue;
            }
            if n < 0 {
                let error = io::Error::last_os_error();
                match error.raw_os_error() {
                    Some(libc::EAGAIN) | Some(libc::EINTR) => continue,
                    _ => return Err(error),
                }
            }
            return Ok(());
        }
        Ok(())
    }

    /// Tell the kernel the terminal changed size.
    ///
    /// The kernel turns this into `SIGWINCH` for the foreground process group,
    /// which is how a shell learns to redraw its prompt and a TUI re-lays itself
    /// out.
    pub fn resize(&mut self, size: Winsize) -> Result<(), PtyError> {
        if self.master < 0 {
            return Err(PtyError::NotOpen);
        }
        if size.rows == self.size.rows
            && size.cols == self.size.cols
            && size.xpixel == self.size.xpixel
            && size.ypixel == self.size.ypixel
        {
            return Ok(());
        }

        let winsize = size.to_libc();
        let result =
            unsafe { libc::ioctl(self.master, libc::TIOCSWINSZ, &winsize as *const libc::winsize) };
        if result < 0 {
            return Err(PtyError::ResizeFailed(io::Error::last_os_error()));
        }
        self.size = size;
        Ok(())
    }

    /// The child's exit status, if it has exited. Null while it runs.
    pub fn try_wait(&mut self) -> Option<i32> {
        if self.child <= 0 {
            return None;
        }
        let mut status: libc::c_int = 0;
        let result = unsafe { libc::waitpid(self.child, &mut status, libc::WNOHANG) };
        if result <= 0 {
            return None;
        }
        self.child = -1;

        if libc::WIFEXITED(status) {
            Some(libc::WEXITSTATUS(status))
        } else if libc::WIFSIGNALED(status) {
            Some(128 + libc::WTERMSIG(status))
        } else {
            Some(0)
        }
    }

    /// The child's pid, or -1 when there is none or it has been reaped.
    ///
    /// The host reads `/proc/<pid>/comm` with it: the session's default name
    /// is the process's own name, and only the kernel knows which process that
    /// is after the launcher has exec'd into the shell.
    pub fn pid(&self) -> libc::pid_t {
        self.child
    }

    /// The master side's descriptor.
    ///
    /// Set once in [`Pty::spawn`] and closed only in `Drop`, so it is stable for
    /// the PTY's whole life. The JNI readiness wait copies it and polls
    /// *outside* the lock: a blocking wait must not hold the lock a write needs.
    pub fn master(&self) -> RawFd {
        self.master
    }

    /// The read end of the wake pipe, or -1 when there is none.
    ///
    /// The JNI poll watches it alongside the master, so a queued write wakes
    /// the reader instead of waiting out the timeout.
    pub fn wake_read(&self) -> RawFd {
        self.wake_read
    }

    /// Pokes the reader awake. True when a byte was written.
    ///
    /// One byte is enough however many writes are queued: the reader drains the
    /// pipe and then drains the work. A full pipe means the reader is already
    /// about to wake, so a failed write is not a lost wakeup.
    pub fn wake(&mut self) -> bool {
        if self.wake_write < 0 {
            return false;
        }
        let byte = [1u8];
        let written = unsafe {
            libc::write(self.wake_write, byte.as_ptr() as *const libc::c_void, 1)
        };
        written == 1
    }

    /// Empties the wake pipe, so the next `poll` blocks again.
    pub fn drain_wake(&mut self) {
        if self.wake_read < 0 {
            return;
        }
        let mut scratch = [0u8; 64];
        loop {
            let read = unsafe {
                libc::read(
                    self.wake_read,
                    scratch.as_mut_ptr() as *mut libc::c_void,
                    scratch.len(),
                )
            };
            if read <= 0 {
                break;
            }
        }
    }

    /// The id of the process group in the terminal's foreground, if any.
    ///
    /// This is what Ctrl+C would signal, and what the UI should name when it
    /// says which program is running. Linux's `tcgetpgrp` answers for the
    /// master side with the foreground process *group*, which for a shell is
    /// the job that currently owns the terminal.
    pub fn foreground_pid(&self) -> Option<libc::pid_t> {
        if self.master < 0 {
            return None;
        }
        let pgrp = unsafe { libc::tcgetpgrp(self.master) };
        if pgrp < 0 {
            None
        } else {
            Some(pgrp)
        }
    }

    /// The slave side's path, e.g. `/dev/pts/3`.
    ///
    /// A program that reports where it writes, or a host that wants to open the
    /// terminal's own device, needs this name, and only the master can ask the
    /// kernel for it.
    pub fn tty_name(&self) -> Option<CString> {
        if self.master < 0 {
            return None;
        }
        // `TTY_NAME_MAX` on Linux is 64; 128 leaves room for a longer prefix
        // without trusting a host-specific limit.
        let mut buffer = [0u8; 128];
        let result =
            unsafe { libc::ptsname_r(self.master, buffer.as_mut_ptr().cast(), buffer.len()) };
        if result != 0 {
            return None;
        }
        // `ptsname_r` NUL-terminates on success, but the name is built from a
        // buffer: a name without its terminator is not one to hand on.
        CStr::from_bytes_until_nul(&buffer).ok().map(CStr::to_owned)
    }

    /// Whether the terminal is in canonical (line) mode and whether it echoes.
    ///
    /// The two modes a UI mirrors: a password prompt turns echo off, and a
    /// full-screen program turns canonical mode off. `(canonical, echo)`.
    pub fn get_mode(&self) -> Option<(bool, bool)> {
        if self.master < 0 {
            return None;
        }
        let mut attrs: libc::termios = unsafe { std::mem::zeroed() };
        if unsafe { libc::tcgetattr(self.master, &mut attrs) } != 0 {
            return None;
        }
        Some(mode_from_lflag(attrs.c_lflag))
    }

    /// The size the kernel currently has.
    ///
    /// Not the size this `Pty` last set: the kernel is the record, and it is
    /// what the child's own `TIOCGWINSZ` would answer.
    pub fn get_size(&self) -> Option<Winsize> {
        if self.master < 0 {
            return None;
        }
        let mut winsize: libc::winsize = unsafe { std::mem::zeroed() };
        if unsafe { libc::ioctl(self.master, libc::TIOCGWINSZ, &mut winsize) } < 0 {
            return None;
        }
        Some(Winsize::from_libc(winsize))
    }
}

impl Drop for Pty {
    fn drop(&mut self) {
        if self.master >= 0 {
            unsafe { libc::close(self.master) };
            self.master = -1;
        }
        if self.wake_read >= 0 {
            unsafe { libc::close(self.wake_read) };
            self.wake_read = -1;
        }
        if self.wake_write >= 0 {
            unsafe { libc::close(self.wake_write) };
            self.wake_write = -1;
        }
        if self.child > 0 {
            // Reaped without blocking: a caller may drop while the child is
            // still shutting down, and a blocking wait there is a hang.
            let mut status: libc::c_int = 0;
            unsafe { libc::waitpid(self.child, &mut status, libc::WNOHANG) };
            self.child = -1;
        }
    }
}

// These are the arithmetic and the decoding, not the kernel: no fork, no PTY.
// The module is gated on the `engine` feature, so `cargo test
// --no-default-features` (what CI runs) does not compile them; they run with
// the engine present.
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_size_survives_the_libc_layout() {
        let size = Winsize {
            rows: 40,
            cols: 132,
            xpixel: 1188,
            ypixel: 720,
        };
        let round_trip = Winsize::from_libc(size.to_libc());
        assert_eq!(round_trip.rows, size.rows);
        assert_eq!(round_trip.cols, size.cols);
        assert_eq!(round_trip.xpixel, size.xpixel);
        assert_eq!(round_trip.ypixel, size.ypixel);
    }

    #[test]
    fn a_cell_count_becomes_pixels_without_wrapping() {
        let size = Winsize::from_cells(4000, 20, 20, 40);
        assert_eq!(size.cols, 4000);
        assert_eq!(size.xpixel, u16::MAX);
        assert_eq!(size.ypixel, 800);
    }

    #[test]
    fn a_zero_cell_size_still_has_pixels() {
        let size = Winsize::from_cells(80, 24, 0, 0);
        assert_eq!(size.xpixel, 80);
        assert_eq!(size.ypixel, 24);
    }

    #[test]
    fn the_mode_bits_follow_the_flags() {
        assert_eq!(mode_from_lflag(0), (false, false));
        assert_eq!(mode_from_lflag(libc::ICANON), (true, false));
        assert_eq!(mode_from_lflag(libc::ECHO), (false, true));
        assert_eq!(mode_from_lflag(libc::ICANON | libc::ECHO), (true, true));
    }
}
