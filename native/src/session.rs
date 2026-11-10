//! A session: the terminal, its render state, its key encoder, and the shell
//! tap.
//!
//! ## One handle, one lock
//!
//! A terminal is written by the PTY reader thread and read by the render
//! thread. Both used to hold `&mut Session` at the same time — a data race the
//! compiler could not see because the pointer crossed the JNI boundary as a
//! `jlong`. What that looked like on screen was a frame assembled from a grid
//! that was being rewritten underneath it: torn rows, half-drawn text, cells
//! that appeared one frame and vanished the next.
//!
//! [`SessionHandle`] is the fix. It is a `Mutex<Session>` behind the raw
//! pointer, and every entry point takes the lock for the whole of its work.
//! The render state and the terminal are then only ever mutated by one thread
//! at a time, which is the invariant the engine's API actually requires.
//!
//! A poisoned lock is recovered rather than propagated: a panic inside one
//! call must not turn the whole terminal into a dead handle. The state may be
//! inconsistent for one frame, and the next update repairs it.

use std::ffi::c_void;
use std::sync::{Mutex, MutexGuard};

use crate::frame::{FrameError, FrameSource};
use crate::ghostty;
use crate::shell::{Phase, Scanner};

/// Why a session could not be made.
#[derive(Debug)]
pub enum SessionError {
    /// The engine refused the terminal.
    TerminalFailed,
    /// The render state could not be built.
    Frames(FrameError),
}

/// A session: a terminal and everything needed to draw it.
pub struct Session {
    terminal: ghostty::GhosttyTerminal,
    frames: FrameSource,
    keys: Option<ghostty::GhosttyKeyEncoder>,
    shell: Scanner,
}

impl Session {
    pub fn new(columns: u16, rows: u16) -> Result<Self, SessionError> {
        let mut terminal = std::ptr::null_mut();
        let result = unsafe {
            ghostty::ghostty_terminal_new(std::ptr::null(), &mut terminal, columns, rows)
        };
        if !ghostty::ok(result) {
            return Err(SessionError::TerminalFailed);
        }

        let frames = FrameSource::new(terminal).map_err(SessionError::Frames)?;
        Ok(Self {
            terminal,
            frames,
            keys: None,
            shell: Scanner::new(),
        })
    }

    pub fn terminal(&self) -> ghostty::GhosttyTerminal {
        self.terminal
    }

    /// Feed a byte stream to the parser.
    ///
    /// The shell's marks are scanned first: the tap observes the bytes and never
    /// changes them, so the parser sees exactly what the child wrote.
    pub fn write(&mut self, bytes: &[u8]) {
        let _ = self.shell.scan(bytes);
        unsafe { ghostty::ghostty_terminal_vt_write(self.terminal, bytes.as_ptr(), bytes.len()) };
    }

    pub fn resize(&mut self, columns: u16, rows: u16, cell_width: u32, cell_height: u32) -> bool {
        let result = unsafe {
            ghostty::ghostty_terminal_resize(self.terminal, columns, rows, cell_width, cell_height)
        };
        ghostty::ok(result)
    }

    pub fn render(&mut self) -> Result<&[u8], FrameError> {
        self.frames.render()
    }

    pub fn shell_phase(&self) -> Phase {
        self.shell.phase()
    }

    pub fn working_directory(&self) -> Option<&str> {
        self.shell.directory()
    }

    /// The scrollbar: total rows and the viewport's offset.
    pub fn scrollbar(&self) -> Option<ghostty::GhosttyTerminalScrollbar> {
        let mut value: ghostty::GhosttyTerminalScrollbar = unsafe { std::mem::zeroed() };
        let result = unsafe {
            ghostty::ghostty_terminal_get(
                self.terminal,
                ghostty::GHOSTTY_TERMINAL_DATA_SCROLLBAR,
                &mut value as *mut _ as *mut c_void,
            )
        };
        if ghostty::ok(result) {
            Some(value)
        } else {
            None
        }
    }

    /// Which screen is active, as the engine's own tag.
    pub fn active_screen(&self) -> ghostty::GhosttyTerminalScreen {
        let mut value = ghostty::GHOSTTY_TERMINAL_SCREEN_PRIMARY;
        let _ = unsafe {
            ghostty::ghostty_terminal_get(
                self.terminal,
                ghostty::GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN,
                &mut value as *mut _ as *mut c_void,
            )
        };
        value
    }

    /// Whether the running program asked for mouse reports.
    pub fn mouse_tracking(&self) -> bool {
        let mut value = false;
        let _ = unsafe {
            ghostty::ghostty_terminal_get(
                self.terminal,
                ghostty::GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING,
                &mut value as *mut _ as *mut c_void,
            )
        };
        value
    }

    /// The kitty keyboard protocol's flags, or zero when it is off.
    pub fn kitty_flags(&self) -> u8 {
        let mut value: u8 = 0;
        let _ = unsafe {
            ghostty::ghostty_terminal_get(
                self.terminal,
                ghostty::GHOSTTY_TERMINAL_DATA_KITTY_KEYBOARD_FLAGS,
                &mut value as *mut _ as *mut c_void,
            )
        };
        value
    }

    pub fn columns(&self) -> u16 {
        let mut value: u16 = 0;
        let _ = unsafe {
            ghostty::ghostty_terminal_get(
                self.terminal,
                ghostty::GHOSTTY_TERMINAL_DATA_COLS,
                &mut value as *mut _ as *mut c_void,
            )
        };
        value
    }

    pub fn rows(&self) -> u16 {
        let mut value: u16 = 0;
        let _ = unsafe {
            ghostty::ghostty_terminal_get(
                self.terminal,
                ghostty::GHOSTTY_TERMINAL_DATA_ROWS,
                &mut value as *mut _ as *mut c_void,
            )
        };
        value
    }

    /// Encode one key event, and return the bytes.
    ///
    /// The engine owns the rules: `DECCKM`'s `ESC O A` versus `ESC [ A`, the
    /// kitty protocol's form, whether a modifier is a parameter or a prefix. The
    /// encoder is re-read from the terminal before every key, because a program
    /// can turn the kitty protocol on mid-session.
    pub fn encode_key(
        &mut self,
        key: ghostty::GhosttyKey,
        mods: ghostty::GhosttyMods,
        action: ghostty::GhosttyKeyAction,
        utf8: &str,
        unshifted: u32,
    ) -> Vec<u8> {
        if self.keys.is_none() {
            let mut encoder = std::ptr::null_mut();
            if !ghostty::ok(unsafe {
                ghostty::ghostty_key_encoder_new(std::ptr::null(), &mut encoder)
            }) {
                return Vec::new();
            }
            unsafe { ghostty::ghostty_key_encoder_setopt_from_terminal(encoder, self.terminal) };
            self.keys = Some(encoder);
        }
        let encoder = self.keys.unwrap();
        unsafe { ghostty::ghostty_key_encoder_setopt_from_terminal(encoder, self.terminal) };

        let mut event = std::ptr::null_mut();
        if !ghostty::ok(unsafe { ghostty::ghostty_key_event_new(std::ptr::null(), &mut event) }) {
            return Vec::new();
        }

        let mut buffer = vec![0u8; 256];
        let mut written: usize = 0;

        unsafe {
            ghostty::ghostty_key_event_set_key(event, key);
            ghostty::ghostty_key_event_set_mods(event, mods);
            ghostty::ghostty_key_event_set_action(event, action);
            if !utf8.is_empty() {
                ghostty::ghostty_key_event_set_utf8(event, utf8.as_ptr().cast(), utf8.len());
            }
            ghostty::ghostty_key_event_set_unshifted_codepoint(event, unshifted);

            let result = ghostty::ghostty_key_encoder_encode(
                encoder,
                event,
                buffer.as_mut_ptr().cast(),
                buffer.len(),
                &mut written,
            );
            ghostty::ghostty_key_event_free(event);

            if !ghostty::ok(result) || written == 0 {
                return Vec::new();
            }
        }

        buffer.truncate(written);
        buffer
    }
}

impl Drop for Session {
    fn drop(&mut self) {
        unsafe {
            if let Some(encoder) = self.keys.take() {
                ghostty::ghostty_key_encoder_free(encoder);
            }
            ghostty::ghostty_terminal_free(self.terminal);
        }
    }
}

/// A session as the JNI layer holds it: a lock around the session.
///
/// The lock is not an optimisation. Two threads reach a terminal — the PTY
/// reader that feeds it and the render thread that draws it — and the engine's
/// API is single-threaded. Without this the two interleave inside the parser,
/// which produces frames that are half one screen and half another.
pub struct SessionHandle {
    session: Mutex<Session>,
}

impl SessionHandle {
    /// Box a session and hand back the raw pointer the JNI layer stores.
    pub fn into_raw(session: Session) -> *mut SessionHandle {
        Box::into_raw(Box::new(Self {
            session: Mutex::new(session),
        }))
    }

    /// Borrow a handle from the pointer that crossed the boundary.
    ///
    /// # Safety
    /// `raw` must have come from [`SessionHandle::into_raw`] and must not have
    /// been freed.
    pub unsafe fn from_raw<'a>(raw: *mut SessionHandle) -> Option<&'a Self> {
        if raw.is_null() {
            None
        } else {
            Some(&*raw)
        }
    }

    /// Take the lock for the whole of one call.
    ///
    /// A poisoned lock is recovered: a panic in one entry point must not leave
    /// the terminal permanently unusable, and the engine's state is repaired by
    /// the next update.
    pub fn lock(&self) -> MutexGuard<'_, Session> {
        self.session.lock().unwrap_or_else(|poisoned| {
            log::warn!("drosh-native: a session lock was poisoned; recovering");
            poisoned.into_inner()
        })
    }

    /// Free a handle. Null is a no-op.
    ///
    /// # Safety
    /// `raw` must have come from [`SessionHandle::into_raw`] and must not be
    /// used again afterwards.
    pub unsafe fn destroy(raw: *mut SessionHandle) {
        if !raw.is_null() {
            drop(Box::from_raw(raw));
        }
    }
}
