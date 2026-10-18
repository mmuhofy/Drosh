//! A PTY wrapper that taps shell-integration marks off the output stream.
//!
//! `alacritty_terminal`'s event loop owns the read path: it polls the PTY and
//! feeds every chunk to the parser via `reader().read(…)`. The tap lives in
//! exactly that place — a wrapper [`TapPty`] whose reader scans each chunk with
//! a [`ShellMarkScanner`] before the parser sees it, and pushes completed marks
//! onto the session's event queue.
//!
//! The wrapper delegates everything else (poll registration, writes, resize,
//! child events) to the inner [`LocalPty`], so the event loop cannot tell the
//! difference.

use std::fs::File;
use std::io::{self, Read};
use std::os::unix::io::{AsRawFd, RawFd};
use std::sync::Arc;

use alacritty_terminal::event::{OnResize, WindowSize};
use alacritty_terminal::tty::{ChildEvent, EventedPty, EventedReadWrite};
use polling::{Event as PollEvent, PollMode, Poller};

use crate::bridge::events::EventQueue;
use crate::session::pty_factory::LocalPty;
use crate::bridge::events::ShellMark;
use crate::session::shell_mark::ShellMarkScanner;

pub struct TapPty {
    inner: LocalPty,
    reader: TapReader,
}

impl TapPty {
    /// Wrap `inner`; completed marks are pushed onto `events`.
    pub fn new(inner: LocalPty, events: Arc<EventQueue>) -> io::Result<Self> {
        // A duplicate descriptor shares the master's open file description, so
        // reads through it consume the same stream — while staying independent
        // of the inner `File`, which the event loop also uses for writes.
        let duplicated = unsafe { libc::dup(inner.as_raw_fd()) };
        if duplicated < 0 {
            return Err(io::Error::last_os_error());
        }

        Ok(Self {
            inner,
            // SAFETY: `dup` handed us a fresh descriptor that we now own.
            reader: TapReader { fd: duplicated, scanner: ShellMarkScanner::new(), events },
        })
    }
}

/// The master's read half, with the mark tap.
struct TapReader {
    /// A `dup` of the master; same open file description, same non-blocking
    /// flags.
    fd: RawFd,
    scanner: ShellMarkScanner,
    events: Arc<EventQueue>,
}

impl TapReader {
    fn push(&self, mark: ShellMark) {
        self.events.push(crate::bridge::events::SessionEvent::ShellMark(mark));
    }
}

impl Drop for TapReader {
    fn drop(&mut self) {
        // SAFETY: the descriptor came from `dup` and `TapReader` owns it.
        unsafe { libc::close(self.fd) };
    }
}

impl Read for TapReader {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        // The master is non-blocking; a partial mark simply stays in the
        // scanner until the next chunk arrives.
        let n = loop {
            // SAFETY: `fd` is live for as long as the session is.
            let n = unsafe { libc::read(self.fd, buf.as_mut_ptr().cast(), buf.len()) };
            if n >= 0 {
                break n as usize;
            }
            let error = io::Error::last_os_error();
            if error.kind() != io::ErrorKind::Interrupted {
                return Err(error);
            }
        };

        if n > 0 {
            for mark in self.scanner.scan(&buf[..n]) {
                self.push(mark);
            }
        }
        Ok(n)
    }
}

impl EventedReadWrite for TapPty {
    type Reader = TapReader;
    type Writer = File;

    unsafe fn register(
        &mut self,
        poll: &Arc<Poller>,
        interest: PollEvent,
        mode: PollMode,
    ) -> io::Result<()> {
        unsafe { self.inner.register(poll, interest, mode) }
    }

    fn reregister(
        &mut self,
        poll: &Arc<Poller>,
        interest: PollEvent,
        mode: PollMode,
    ) -> io::Result<()> {
        self.inner.reregister(poll, interest, mode)
    }

    fn deregister(&mut self, poll: &Arc<Poller>) -> io::Result<()> {
        self.inner.deregister(poll)
    }

    fn reader(&mut self) -> &mut Self::Reader {
        &mut self.reader
    }

    fn writer(&mut self) -> &mut Self::Writer {
        self.inner.writer()
    }
}

impl EventedPty for TapPty {
    fn next_child_event(&mut self) -> Option<ChildEvent> {
        self.inner.next_child_event()
    }
}

impl OnResize for TapPty {
    fn on_resize(&mut self, window_size: WindowSize) {
        self.inner.on_resize(window_size);
    }
}
