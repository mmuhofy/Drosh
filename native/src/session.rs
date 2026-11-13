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

/// The engine palette's length.
const PALETTE_LEN: usize = 256;
/// The mouse encoder's output buffer. An SGR 1006 sequence is under twenty
/// bytes; sixty-four covers every format the engine can produce with room to
/// spare, and a short answer is refused by the caller rather than truncated.
pub(crate) const MOUSE_BUFFER_LEN: usize = 64;
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
/// The cells a selection covers.
///
/// The engine's own answer: the start and the end of the range, in the
/// viewport's rows and columns. The chrome hangs its menu on these, and the
/// engine marks the cells in the frame, so the two cannot disagree about where
/// the selection is.
#[derive(Clone, Copy)]
pub struct SelectionCells {
    pub start_row: u16,
    pub start_column: u16,
    pub end_row: u16,
    pub end_column: u16,
}

/// The geometry the engine reads a selection drag event with.
///
/// The grid's shape in the surface: how many columns, how wide a cell is, and
/// how tall the surface is. The engine needs it to turn a drag into a
/// cell-granular selection.
#[derive(Clone, Copy)]
pub struct SelectionGeometry {
    pub columns: u32,
    pub cell_width: u32,
    pub padding_left: u32,
    pub screen_height: u32,
}

pub struct Session {
    terminal: ghostty::GhosttyTerminal,
    /// The selection gesture, while one is running.
    ///
    /// The gesture is the engine's own: it counts the clicks, so the first
    /// press selects a cell, the second a word and the third a line, and it
    /// installs the selection in the terminal — which is what makes the
    /// selected cells come back marked in the frame.
    gesture: Option<ghostty::GhosttySelectionGesture>,
    frames: FrameSource,
    keys: Option<ghostty::GhosttyKeyEncoder>,
    mouse: Option<ghostty::GhosttyMouseEncoder>,
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

        // The clipboard write effect: OSC 52, iTerm2's OSC 1337 Copy, and the
        // kitty clipboard protocol (OSC 5522). The engine hands each request to
        // the callback while the bytes are being parsed — which is here, on the
        // thread that feeds the terminal. The callback is the bridge to
        // Android's clipboard; whether a write is *allowed* is the Kotlin
        // sink's decision, not the engine's, so the effect is installed on every
        // terminal and the sink refuses by default.
        let _ = unsafe {
            ghostty::ghostty_terminal_set(
                terminal,
                ghostty::GHOSTTY_TERMINAL_OPT_CLIPBOARD_WRITE,
                crate::clipboard::on_clipboard_write as *const c_void,
            );
            // The bell: a program that rings it is answered by the app — a
            // haptic, a flash, and a notification when the app is not on
            // screen.
            let _ = ghostty::ghostty_terminal_set(
                terminal,
                ghostty::GHOSTTY_TERMINAL_OPT_BELL,
                crate::bell::on_bell as *const c_void,
            );
            // The shell's report of what it is doing: the steps of a command
            // and, at the end, the exit status.
            let _ = ghostty::ghostty_terminal_set(
                terminal,
                ghostty::GHOSTTY_TERMINAL_OPT_SEMANTIC_PROMPT,
                crate::bell::on_semantic_prompt as *const c_void,
            );
            // A program that asks for a notification — OSC 9, OSC 777 — gets
            // one, with the app's own notification.
            let _ = ghostty::ghostty_terminal_set(
                terminal,
                ghostty::GHOSTTY_TERMINAL_OPT_DESKTOP_NOTIFICATION,
                crate::bell::on_desktop_notification as *const c_void,
            );
        };

        let frames = FrameSource::new(terminal).map_err(SessionError::Frames)?;
        Ok(Self {
            terminal,
            gesture: None,
            frames,
            keys: None,
            mouse: None,
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

    /// The colours of a region of the grid, as `(foreground, background)` pairs.
    ///
    /// The frame has to exist before its cells can be read: the colours are
    /// the frame's, and the frame is what the engine produced last.
    pub fn cell_colors(
        &mut self,
        first_row: u16,
        first_column: u16,
        rows: u16,
        columns: u16,
    ) -> Vec<u32> {
        if self.render().is_err() {
            return Vec::new();
        }
        self.frames.cell_colors(first_row, first_column, rows, columns)
    }

    pub fn shell_phase(&self) -> Phase {
        self.shell.phase()
    }

    pub fn working_directory(&self) -> Option<&str> {
        self.shell.directory()
    }

    /// The theme's colours, handed to the engine.
    ///
    /// The engine paints a cell that carries no colour of its own in *its* two
    /// defaults, and resolves palette indices through *its* palette. If they
    /// are never set they are the library's own — a black background and a
    /// white foreground — so a light theme drew white text on the library's
    /// black no matter what the app's palette said, and SGR 7 in a light theme
    /// produced white-on-white.
    pub fn set_colors(
        &mut self,
        foreground: ghostty::GhosttyColorRgb,
        background: ghostty::GhosttyColorRgb,
        palette: &[ghostty::GhosttyColorRgb],
    ) {
        unsafe {
            let _ = ghostty::ghostty_terminal_set(
                self.terminal,
                ghostty::GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND,
                &foreground as *const _ as *const c_void,
            );
            let _ = ghostty::ghostty_terminal_set(
                self.terminal,
                ghostty::GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND,
                &background as *const _ as *const c_void,
            );
            if palette.len() == PALETTE_LEN {
                let _ = ghostty::ghostty_terminal_set(
                    self.terminal,
                    ghostty::GHOSTTY_TERMINAL_OPT_COLOR_PALETTE,
                    palette.as_ptr() as *const c_void,
                );
            }
        }
    }

    /// The default cursor's shape and whether it blinks.
    ///
    /// A program can still change its own cursor with DECSCUSR; these are what
    /// the terminal falls back to, which is the setting the user chose.
    pub fn set_cursor(&mut self, style: u32, blink: bool) {
        unsafe {
            let value = style as i32;
            let _ = ghostty::ghostty_terminal_set(
                self.terminal,
                ghostty::GHOSTTY_TERMINAL_OPT_DEFAULT_CURSOR_STYLE,
                &value as *const _ as *const c_void,
            );
            let _ = ghostty::ghostty_terminal_set(
                self.terminal,
                ghostty::GHOSTTY_TERMINAL_OPT_DEFAULT_CURSOR_BLINK,
                &blink as *const _ as *const c_void,
            );
        }
    }

    /// The window title the program asked for with OSC 0 or OSC 2.
    ///
    /// The engine hands back a borrowed string: it stays valid until the next
    /// call that changes the terminal, so it is copied out here rather than
    /// kept.
    pub fn title(&self) -> Option<String> {
        let mut string = ghostty::GhosttyString {
            ptr: std::ptr::null(),
            len: 0,
        };
        let result = unsafe {
            ghostty::ghostty_terminal_get(
                self.terminal,
                ghostty::GHOSTTY_TERMINAL_DATA_TITLE,
                &mut string as *mut _ as *mut c_void,
            )
        };
        if !ghostty::ok(result) || string.ptr.is_null() || string.len == 0 {
            return None;
        }
        let bytes = unsafe { std::slice::from_raw_parts(string.ptr, string.len) };
        String::from_utf8(bytes.to_vec()).ok()
    }

    /// Feed the selection gesture an event.
    ///
    /// `kind` is one of the engine's event types: press, release or drag. The
    /// point is in viewport coordinates. A drag also needs the geometry, and
    /// without it the engine refuses the event.
    ///
    /// The gesture is the engine's own, so the click count is the engine's
    /// too: the first press selects a cell, the second a word, the third a
    /// line, and a drag extends whatever the press selected.
    pub fn selection_event(
        &mut self,
        kind: u32,
        row: u16,
        column: u16,
        geometry: Option<SelectionGeometry>,
        behavior: Option<u32>,
    ) -> Option<SelectionCells> {
        let gesture = match self.gesture {
            Some(gesture) => gesture,
            None => {
                let mut gesture = std::ptr::null_mut();
                if !ghostty::ok(unsafe {
                    ghostty::ghostty_selection_gesture_new(std::ptr::null(), &mut gesture)
                }) {
                    return None;
                }
                self.gesture = Some(gesture);
                gesture
            }
        };

        // The cell under the finger.
        let mut point: ghostty::GhosttyPoint = unsafe { std::mem::zeroed() };
        unsafe {
            crate::jni::set_enum(&mut point.tag, ghostty::GHOSTTY_POINT_TAG_VIEWPORT as u32);
            point.value.coordinate.x = column;
            point.value.coordinate.y = row as u32;
        }
        let mut reference: ghostty::GhosttyGridRef = unsafe { std::mem::zeroed() };
        reference.size = std::mem::size_of::<ghostty::GhosttyGridRef>();
        if !ghostty::ok(unsafe {
            ghostty::ghostty_terminal_grid_ref(self.terminal, point, &mut reference)
        }) {
            return None;
        }

        let mut event = std::ptr::null_mut();
        if !ghostty::ok(unsafe {
            ghostty::ghostty_selection_gesture_event_new(std::ptr::null(), &mut event, kind)
        }) {
            return None;
        }

        unsafe {
            ghostty::ghostty_selection_gesture_event_set(
                event,
                ghostty::GHOSTTY_SELECTION_GESTURE_EVENT_OPT_REF,
                &reference as *const _ as *const c_void,
            );
            // What a click means.
            //
            // The engine counts the clicks and has a table of what each count
            // selects. The host knows better what the user meant — a double tap
            // is a word *here* — so the host sets the table: every count selects
            // what this gesture means.
            if let Some(behavior) = behavior {
                let behaviors = ghostty::GhosttySelectionGestureBehaviors {
                    single_click: behavior,
                    double_click: behavior,
                    triple_click: behavior,
                };
                ghostty::ghostty_selection_gesture_event_set(
                    event,
                    ghostty::GHOSTTY_SELECTION_GESTURE_EVENT_OPT_BEHAVIORS,
                    &behaviors as *const _ as *const c_void,
                );
            }
            if let Some(geometry) = geometry {
                let mut value: ghostty::GhosttySelectionGestureGeometry =
                    std::mem::zeroed();
                value.columns = geometry.columns.max(1);
                value.cell_width = geometry.cell_width.max(1);
                value.padding_left = geometry.padding_left;
                value.screen_height = geometry.screen_height.max(1);
                ghostty::ghostty_selection_gesture_event_set(
                    event,
                    ghostty::GHOSTTY_SELECTION_GESTURE_EVENT_OPT_GEOMETRY,
                    &value as *const _ as *const c_void,
                );
            }
        }

        let mut selection: ghostty::GhosttySelection = unsafe { std::mem::zeroed() };
        let result = unsafe {
            ghostty::ghostty_selection_gesture_event(
                gesture,
                self.terminal,
                event,
                &mut selection,
            )
        };
        unsafe {
            ghostty::ghostty_selection_gesture_event_free(event);
        }

        // A release produces no selection of its own — it ends the gesture —
        // and the engine says so with "no value", which is not a failure.
        if !ghostty::ok(result) && result != ghostty::NO_VALUE {
            return None;
        }
        if !ghostty::ok(result) {
            return None;
        }

        // The cells the selection covers, so the chrome knows where to hang
        // its menu and which cells to mark.
        Some(SelectionCells {
            start_row: selection.start.y,
            start_column: selection.start.x,
            end_row: selection.end.y,
            end_column: selection.end.x,
        })
    }

    /// The text of the terminal's current selection, or none.
    pub fn selection_text(&self) -> Option<String> {
        let mut options: ghostty::GhosttyTerminalSelectionFormatOptions =
            unsafe { std::mem::zeroed() };
        options.size = std::mem::size_of::<ghostty::GhosttyTerminalSelectionFormatOptions>();
        // No selection pointer: the terminal's own selection is what is asked
        // for. The gesture is what installs it.

        let mut needed: usize = 0;
        let result = unsafe {
            ghostty::ghostty_terminal_selection_format_buf(
                self.terminal,
                options,
                std::ptr::null_mut(),
                0,
                &mut needed,
            )
        };
        if !ghostty::ok(result) || needed == 0 {
            return None;
        }

        let mut buffer = vec![0u8; needed];
        let mut written: usize = 0;
        let result = unsafe {
            ghostty::ghostty_terminal_selection_format_buf(
                self.terminal,
                options,
                buffer.as_mut_ptr(),
                buffer.len(),
                &mut written,
            )
        };
        if !ghostty::ok(result) || written == 0 {
            return None;
        }
        buffer.truncate(written);
        Some(String::from_utf8_lossy(&buffer).into_owned())
    }

    /// Highlight the range the search matched.
    ///
    /// The range is in viewport rows: the find bar scrolls the viewport to the
    /// match, and a range in the scrollback's coordinates would be a different
    /// place by the time it is drawn.
    pub fn set_highlight(&mut self, row: u16, column: u16, length: u16) {
        self.frames.set_highlight(Some((row, column, length)));
    }

    /// Draw no highlight.
    pub fn clear_highlight(&mut self) {
        self.frames.set_highlight(None);
    }

    /// The cells the user's selection covers, or none.
    pub fn set_selection_range(&mut self, row: u16, column: u16, length: u16) {
        self.frames.set_app_selection(Some((row, column, length)));
    }

    /// The frame's images, as one buffer.
    ///
    /// A program that draws pictures puts them in the terminal; the renderer
    /// draws them. The pixels are read once per image generation and kept, so a
    /// frame that shows the same picture again carries only where it is.
    pub fn images(&mut self) -> Vec<u8> {
        self.frames.images()
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

    /// Encode one key event into `out`, and return the byte count.
    ///
    /// The engine owns the rules: `DECCKM`'s `ESC O A` versus `ESC [ A`, the
    /// kitty protocol's form, whether a modifier is a parameter or a prefix. The
    /// encoder is re-read from the terminal before every key, because a program
    /// can turn the kitty protocol on mid-session.
    ///
    /// The bytes land in the caller's buffer rather than in one made here. A
    /// keystroke is the terminal's hottest path, and the owned `Vec` this used
    /// to return was a 256-byte heap allocation and a memset on every key —
    /// work whose result was copied out and thrown away within one call. The
    /// engine's own encoder takes a caller-sized buffer (its header documents
    /// the static-buffer pattern), so a stack buffer on the JNI side crosses
    /// no allocation at all.
    ///
    /// Zero means the key produced nothing — an action the current mode does
    /// not report — or the sequence did not fit: the engine answers
    /// `OUT_OF_SPACE` with the size it needed rather than writing a partial
    /// escape sequence, and a partial sequence is never handed back.
    pub fn encode_key_into(
        &mut self,
        key: ghostty::GhosttyKey,
        mods: ghostty::GhosttyMods,
        action: ghostty::GhosttyKeyAction,
        utf8: &str,
        unshifted: u32,
        out: &mut [u8],
    ) -> usize {
        if self.keys.is_none() {
            let mut encoder = std::ptr::null_mut();
            if !ghostty::ok(unsafe {
                ghostty::ghostty_key_encoder_new(std::ptr::null(), &mut encoder)
            }) {
                return 0;
            }
            unsafe { ghostty::ghostty_key_encoder_setopt_from_terminal(encoder, self.terminal) };
            self.keys = Some(encoder);
        }
        let encoder = self.keys.unwrap();
        unsafe { ghostty::ghostty_key_encoder_setopt_from_terminal(encoder, self.terminal) };

        let mut event = std::ptr::null_mut();
        if !ghostty::ok(unsafe { ghostty::ghostty_key_event_new(std::ptr::null(), &mut event) }) {
            return 0;
        }

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
                out.as_mut_ptr().cast(),
                out.len(),
                &mut written,
            );
            ghostty::ghostty_key_event_free(event);

            if !ghostty::ok(result) || written == 0 {
                return 0;
            }
        }

        written
    }

    /// Encode one mouse event, and return the bytes.
    ///
    /// The engine owns the protocol: which tracking mode the program asked for
    /// (X10, normal, button-event, any-event) and which wire format it accepted
    /// (X10, UTF-8, SGR 1006, URxvt, SGR-pixels) are *its* state, read out of
    /// the VT stream it parsed. Re-deriving that here would be a copy of the
    /// parser's mode bookkeeping, and a copy is a place to be wrong.
    ///
    /// The encoder is re-synced from the terminal before every event, the way
    /// the key encoder is: a program can turn a different tracking mode or
    /// format on mid-session, and a stale option would encode for a program
    /// that is no longer listening.
    ///
    /// `x`/`y` are surface-space pixels. The encoder converts them into cell
    /// coordinates through the size context handed in below, so the geometry
    /// (view size, cell size, padding) is the host's answer, not a guess.
    ///
    /// Events the current tracking mode does not report come back empty: the
    /// engine answers "nothing to send" rather than making one up.
    ///
    /// # Why the encoder is kept
    ///
    /// `TRACK_LAST_CELL` makes the engine drop a motion report that lands in
    /// the cell the last one came from — the dedup the wire protocol itself is
    /// cell-grained about. That state lives in the encoder, so the encoder is
    /// created once and reused across events.
    #[allow(clippy::too_many_arguments)]
    /// Encodes a mouse event into the caller's buffer.
    ///
    /// The buffer belongs to the caller, so a touch does not allocate a vector
    /// for a sequence of a dozen bytes.
    pub fn encode_mouse_into(
        &mut self,
        action_code: u32,
        button_code: u32,
        x: f32,
        y: f32,
        width: u32,
        height: u32,
        cell_width: u32,
        cell_height: u32,
        button_pressed: bool,
        out: &mut [u8],
    ) -> usize {
        if self.mouse.is_none() {
            let mut encoder = std::ptr::null_mut();
            if !ghostty::ok(unsafe {
                ghostty::ghostty_mouse_encoder_new(std::ptr::null(), &mut encoder)
            }) {
                return 0;
            }
            self.mouse = Some(encoder);
        }
        let encoder = self.mouse.unwrap();

        // The rendered geometry. The grid is drawn edge to edge on this host —
        // no padding — so only the view's size and the cell's size matter.
        let mut size: ghostty::GhosttyMouseEncoderSize = unsafe { std::mem::zeroed() };
        size.size = std::mem::size_of::<ghostty::GhosttyMouseEncoderSize>();
        size.screen_width = width.max(1);
        size.screen_height = height.max(1);
        size.cell_width = cell_width.max(1);
        size.cell_height = cell_height.max(1);

        // Android's action vocabulary to the engine's, same shape as keys.
        let action = match action_code {
            1 => ghostty::GHOSTTY_MOUSE_ACTION_RELEASE,
            2 => ghostty::GHOSTTY_MOUSE_ACTION_MOTION,
            _ => ghostty::GHOSTTY_MOUSE_ACTION_PRESS,
        };
        let button = match button_code {
            1 => ghostty::GHOSTTY_MOUSE_BUTTON_LEFT,
            2 => ghostty::GHOSTTY_MOUSE_BUTTON_RIGHT,
            3 => ghostty::GHOSTTY_MOUSE_BUTTON_MIDDLE,
            4 => ghostty::GHOSTTY_MOUSE_BUTTON_FOUR,
            5 => ghostty::GHOSTTY_MOUSE_BUTTON_FIVE,
            6 => ghostty::GHOSTTY_MOUSE_BUTTON_SIX,
            7 => ghostty::GHOSTTY_MOUSE_BUTTON_SEVEN,
            8 => ghostty::GHOSTTY_MOUSE_BUTTON_EIGHT,
            9 => ghostty::GHOSTTY_MOUSE_BUTTON_NINE,
            _ => ghostty::GHOSTTY_MOUSE_BUTTON_UNKNOWN,
        };

        let mut event = std::ptr::null_mut();
        if !ghostty::ok(unsafe { ghostty::ghostty_mouse_event_new(std::ptr::null(), &mut event) }) {
            return 0;
        }

        let mut written: usize = 0;

        unsafe {
            // Tracking mode and wire format follow the terminal's own state.
            ghostty::ghostty_mouse_encoder_setopt_from_terminal(encoder, self.terminal);

            let track_last_cell: bool = true;
            ghostty::ghostty_mouse_encoder_setopt(
                encoder,
                ghostty::GHOSTTY_MOUSE_ENCODER_OPT_TRACK_LAST_CELL,
                &track_last_cell as *const bool as *const c_void,
            );
            // Whether a button is held *right now*. Motion reports in the
            // normal tracking mode are only meaningful with one down; the host
            // knows, the encoder does not.
            ghostty::ghostty_mouse_encoder_setopt(
                encoder,
                ghostty::GHOSTTY_MOUSE_ENCODER_OPT_ANY_BUTTON_PRESSED,
                &button_pressed as *const bool as *const c_void,
            );
            ghostty::ghostty_mouse_encoder_setopt(
                encoder,
                ghostty::GHOSTTY_MOUSE_ENCODER_OPT_SIZE,
                &size as *const _ as *const c_void,
            );

            ghostty::ghostty_mouse_event_set_action(event, action);
            if button_code != 0 {
                ghostty::ghostty_mouse_event_set_button(event, button);
            } else {
                ghostty::ghostty_mouse_event_clear_button(event);
            }
            ghostty::ghostty_mouse_event_set_position(
                event,
                ghostty::GhosttyMousePosition { x, y },
            );

            let result = ghostty::ghostty_mouse_encoder_encode(
                encoder,
                event,
                out.as_mut_ptr().cast(),
                out.len(),
                &mut written,
            );
            ghostty::ghostty_mouse_event_free(event);

            if !ghostty::ok(result) || written == 0 {
                return 0;
            }
        }

        written
    }
}

impl Drop for Session {
    fn drop(&mut self) {
        unsafe {
            if let Some(encoder) = self.keys.take() {
                ghostty::ghostty_key_encoder_free(encoder);
            }
            if let Some(encoder) = self.mouse.take() {
                ghostty::ghostty_mouse_encoder_free(encoder);
            }
            if let Some(gesture) = self.gesture.take() {
                ghostty::ghostty_selection_gesture_free(gesture, self.terminal);
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
