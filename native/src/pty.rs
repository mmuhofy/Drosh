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

use std::ffi::CString;
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
    fn to_libc(self) -> libc::winsize {
        libc::winsize {
            ws_row: self.rows,
            ws_col: self.cols,
            ws_xpixel: self.xpixel,
            ws_ypixel: self.ypixel,
        }
    }
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

/// A pseudoterminal pair and the child process on the slave side.
pub struct Pty {
    /// The master side: the app reads and writes this.
    master: RawFd,
    /// The child's pid, or -1 once reaped.
    child: libc::pid_t,
    /// The size last set, so a resize to the same size is free.
    size: Winsize,
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
            child: pid,
            size,
        })
    }

    /// Read whatever is available.
    ///
    /// Zero means "nothing right now", which is the normal state between
    /// keystrokes and is not an error.
    pub fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        if self.master < 0 {
            return Err(io::Error::from_raw_os_error(libc::EBADF));
        }
        loop {
            let n = unsafe {
                libc::read(
                    self.master,
                    buffer.as_mut_ptr() as *mut libc::c_void,
                    buffer.len(),
                )
            };
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
                // The child is gone and the slave side closed: a clean end, not
                // a failure.
                Some(libc::EIO) => return Ok(0),
                Some(libc::EINTR) => continue,
                _ => return Err(error),
            }
        }
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
}

impl Drop for Pty {
    fn drop(&mut self) {
        if self.master >= 0 {
            unsafe { libc::close(self.master) };
            self.master = -1;
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