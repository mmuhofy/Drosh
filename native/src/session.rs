//! A session: the terminal, its render state, its key encoder, and the shell
//! tap.
//!
//! One handle owns all of it. The Kotlin side asks for a frame and gets bytes;
//! it does not hold a terminal, a render state and a row iterator separately,
//! because a frame assembled from three calls is a frame whose parts can come
//! from three different moments.

use std::ffi::c_void;

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

    /// `` into a raw pointer for the JNI layer, which passes it as a `jlong`.
    pub fn into_raw(self) -> *mut Session {
        Box::into_raw(Box::new(self))
    }

    /// Reclaim a handle. Null is a no-op.
    ///
    /// # Safety
    /// `raw` must come from [`Session::into_raw`] and not be used again.
    pub unsafe fn from_raw<'a>(raw: *mut Session) -> Option<&'a mut Session> {
        if raw.is_null() {
            None
        } else {
            Some(&mut *raw)
        }
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
    pub fn active_screen(&self) -> u32 {
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
            if !ghostty::ok(ghostty::ghostty_key_encoder_new(std::ptr::null(), &mut encoder)) {
                return Vec::new();
            }
            unsafe { ghostty::ghostty_key_encoder_setopt_from_terminal(encoder, self.terminal) };
            self.keys = Some(encoder);
        }
        let encoder = self.keys.unwrap();
        unsafe { ghostty::ghostty_key_encoder_setopt_from_terminal(encoder, self.terminal) };

        let mut event = std::ptr::null_mut();
        if !ghostty::ok(ghostty::ghostty_key_event_new(std::ptr::null(), &mut event)) {
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