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
use std::sync::Mutex;

/// The engine palette's length.
const PALETTE_LEN: usize = 256;

/// How many bytes of kitty graphics a terminal may hold.
///
/// The engine's own default is zero, which is "the protocol is off": it parses
/// a program's image and throws it away. A phone's budget for pictures behind
/// a terminal is a few screenshots, not a gigabyte.
const KITTY_STORAGE_BYTES: u64 = 128 * 1024 * 1024;

/// How many lines of history a terminal keeps until the settings say otherwise.
///
/// Ghostty's own default is expressed in bytes — ten thousand of them — which
/// a busy prompt fills in one `ls`. Ten thousand *lines* is a day of output on
/// a phone and a few megabytes of pages; the settings can lower it to nothing
/// for someone who would rather have the memory.
const DEFAULT_SCROLLBACK_LINES: u64 = 10_000;
/// The mouse encoder's output buffer. An SGR 1006 sequence is under twenty
/// bytes; sixty-four covers every format the engine can produce with room to
/// spare, and a short answer is refused by the caller rather than truncated.
pub(crate) const MOUSE_BUFFER_LEN: usize = 64;
use std::sync::{Mutex, MutexGuard};

use crate::block::BlockRecorder;
use crate::renderer::{FrameError, FrameSource};
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
/// Where a selection sits on the screen.
///
/// The engine's selection is made of grid references, which address cells in
/// the scrollback and not in the viewport. The chrome, though, draws in view
/// pixels, so every answer here is resolved through the engine's own
/// grid-to-viewport conversion. A cell off the screen is `-1` rather than a
/// guessed row: a handle drawn for a cell the user cannot see would sit on a
/// line that has nothing to do with the selection.
///
/// `first`/`last` are the visible bounding box, which is what the menu hangs
/// on. `top_left`/`bottom_right` are the selection's ordered endpoints, which
/// is what the two grips sit on. They are separate because a selection can
/// run off the screen at either end: the box stays visible while the endpoint
/// that left the screen has no grip.
#[derive(Clone, Copy)]
pub struct SelectionViewport {
    pub first_row: i32,
    pub first_column: i32,
    pub last_row: i32,
    pub last_column: i32,
    pub top_left_row: i32,
    pub top_left_column: i32,
    pub bottom_right_row: i32,
    pub bottom_right_column: i32,
}

impl SelectionViewport {
    /// No selected cell is on the screen.
    pub fn empty() -> Self {
        Self {
            first_row: -1,
            first_column: -1,
            last_row: -1,
            last_column: -1,
            top_left_row: -1,
            top_left_column: -1,
            bottom_right_row: -1,
            bottom_right_column: -1,
        }
    }

    pub fn visible(&self) -> bool {
        self.first_row >= 0 && self.last_row >= self.first_row
    }

    pub fn to_array(self) -> [i32; 8] {
        [
            self.first_row,
            self.first_column,
            self.last_row,
            self.last_column,
            self.top_left_row,
            self.top_left_column,
            self.bottom_right_row,
            self.bottom_right_column,
        ]
    }
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

/// Where a program's icon name comes from: `OSC 1 ; name ST`.
///
/// The engine's own title (OSC 0/2) is read back through the terminal's data
/// API, but the icon name is not part of that API — the terminal keeps the two
/// apart, and only the title is exported. So the sequence is read here, from
/// the same bytes the parser gets, and kept next to the terminal.
///
/// The scan has to survive a sequence split across two reads, which is why it
/// carries its state instead of searching each chunk on its own.
pub struct IconScanner {
    state: IconState,
    /// The bytes between the command number and its `;`.
    code: Vec<u8>,
    /// The name itself, while it is arriving.
    payload: Vec<u8>,
}

#[derive(Clone, Copy, PartialEq)]
enum IconState {
    /// Ordinary output.
    Ground,
    /// An ESC, waiting to see whether it opens an OSC.
    Escape,
    /// An OSC's parameter bytes, up to the `;`.
    Osc,
    /// `OSC 1`: the icon name, until BEL or ST.
    Icon,
    /// An ESC inside the icon name: `\\` closes it, anything else is text.
    IconEscape,
    /// A sequence that is not the icon name: skip to its terminator.
    Skip,
    /// An ESC inside a skipped sequence.
    SkipEscape,
}

impl Default for IconScanner {
    fn default() -> Self {
        Self {
            state: IconState::Ground,
            code: Vec::new(),
            payload: Vec::new(),
        }
    }
}

impl IconScanner {
    /// A name this long is not an icon name; stop collecting it.
    const MAX: usize = 8 * 1024;

    /// Feed one read's bytes; the completed name, when this read finished one.
    pub fn push(&mut self, bytes: &[u8]) -> Option<String> {
        let mut found = None;
        for &byte in bytes {
            match self.state {
                IconState::Ground => {
                    if byte == 0x1b {
                        self.state = IconState::Escape;
                    }
                }
                IconState::Escape => {
                    if byte == b']' {
                        self.code.clear();
                        self.state = IconState::Osc;
                    } else if byte != 0x1b {
                        self.state = IconState::Ground;
                    }
                }
                IconState::Osc => {
                    if byte == b';' {
                        // OSC 1 is the icon name. OSC 0 and 2 are the title,
                        // which the terminal answers on its own.
                        self.state = if self.code == b"1" {
                            self.payload.clear();
                            IconState::Icon
                        } else {
                            IconState::Skip
                        };
                    } else if byte.is_ascii_digit() {
                        // Long enough to be nonsense, short enough to bound.
                        if self.code.len() < 8 {
                            self.code.push(byte);
                        }
                    } else if byte == 0x1b {
                        self.state = IconState::Escape;
                    } else {
                        self.state = IconState::Skip;
                    }
                }
                IconState::Icon => {
                    if byte == 0x07 {
                        found = self.finish();
                    } else if byte == 0x1b {
                        self.state = IconState::IconEscape;
                    } else if self.payload.len() < Self::MAX {
                        self.payload.push(byte);
                    }
                }
                IconState::IconEscape => {
                    if byte == b'\\' {
                        found = self.finish();
                    } else {
                        // A lone ESC in the middle of a name is text: keep it
                        // and the byte after it, and carry on.
                        if self.payload.len() < Self::MAX {
                            self.payload.push(0x1b);
                            self.payload.push(byte);
                        }
                        self.state = IconState::Icon;
                    }
                }
                IconState::Skip => {
                    if byte == 0x07 {
                        self.state = IconState::Ground;
                    } else if byte == 0x1b {
                        self.state = IconState::SkipEscape;
                    }
                }
                IconState::SkipEscape => {
                    self.state = if byte == b'\\' {
                        IconState::Ground
                    } else {
                        IconState::Skip
                    };
                }
            }
        }
        found
    }

    fn finish(&mut self) -> Option<String> {
        self.state = IconState::Ground;
        let name = String::from_utf8_lossy(&self.payload).trim().to_owned();
        self.payload.clear();
        (!name.is_empty()).then_some(name)
    }
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
    keys: Option<ghostty::GhosttyKeyEncoder>,
    mouse: Option<ghostty::GhosttyMouseEncoder>,
    shell: Scanner,
    /// The command blocks recorded from the same `OSC 133` marks the shell
    /// scanner reads. Separate from it: the block recorder needs byte offsets,
    /// and the phase scanner's contract must not shift with it.
    block: BlockRecorder,
    /// The engine's own search, bound to this terminal.
    ///
    /// Created lazily: a terminal that never opens the find bar never pays
    /// for one, and closing the bar frees it again. The engine keeps the
    /// search in sync with the terminal across feeds.
    search: Option<ghostty::GhosttySearch>,
    /// The icon name the program set with `OSC 1`, if it set one.
    icon_name: Option<String>,
    icon: IconScanner,
}

/// One rendered preview, keyed by the frame it was drawn from.
struct PreviewCache {
    /// The hash of the frame bytes and the width they were drawn at.
    key: u64,
    png: Vec<u8>,
}

/// A cheap hash of a frame and the width it would be drawn at.
///
/// FNV-1a over the frame's bytes: a terminal frame is tens of kilobytes and
/// this is a few microseconds, which is the price of not re-rasterizing and
/// re-encoding a screen that has not changed.
fn preview_key(frame: &[u8], max_width: u32) -> u64 {
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    for byte in frame {
        hash ^= u64::from(*byte);
        hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
    }
    hash ^ (u64::from(max_width) << 32)
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

        // The kitty PNG decoder is a process-global sys callback. It must be
        // in place before a program can send a PNG, and every session gets it.
        crate::png::install();

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
            // The kitty graphics storage.
            //
            // A limit of zero is the library's default and it disables the
            // protocol: the engine parses a program's image, finds nowhere to
            // keep it, and the renderer is handed nothing. That is why a
            // kitty image never appeared. 128 MiB is a phone's share — a few
            // screenshots' worth — and small enough that a program cannot
            // fill the heap with pictures.
            let kitty_limit: u64 = KITTY_STORAGE_BYTES;
            let set_result = ghostty::ghostty_terminal_set(
                terminal,
                ghostty::GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_STORAGE_LIMIT,
                &kitty_limit as *const u64 as *const c_void,
            );
            // Read the limit back: the set either took or the library was
            // built without the feature, and the two look identical from the
            // renderer — no images, ever. This line is the difference.
            let mut kitty_read_back: u64 = 0;
            let read_result = ghostty::ghostty_terminal_get(
                terminal,
                ghostty::GHOSTTY_TERMINAL_DATA_KITTY_IMAGE_STORAGE_LIMIT,
                &mut kitty_read_back as *mut u64 as *mut c_void,
            );
            log::info!(
                "drosh-native: kitty storage set={set_result} read={read_result} limit={kitty_read_back}"
            );
        };

        // The history the terminal keeps.
        //
        // The library's default is ten thousand bytes — not lines — which is
        // a handful of screens: a `cat` of a log file scrolls the thing the
        // user wanted to read straight out of existence. The app sets the
        // limit in *lines*, which is the unit a person thinks in, and asks for
        // this default until the settings say otherwise. `set_scrollback_lines`
        // is what the Kotlin side calls when the setting changes.
        let scrollback_lines: u64 = DEFAULT_SCROLLBACK_LINES;
        let _ = unsafe {
            ghostty::ghostty_terminal_set(
                terminal,
                ghostty::GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_LINES,
                &scrollback_lines as *const u64 as *const c_void,
            )
        };

        Ok(Self {
            terminal,
            gesture: None,
            keys: None,
            mouse: None,
            shell: Scanner::new(),
            block: BlockRecorder::new(),
            search: None,
            icon_name: None,
            icon: IconScanner::default(),
        })
    }

    /// The render state for this session's terminal.
    ///
    /// The render state is *not* part of the session: it is the snapshot the
    /// renderer reads, and it has its own lock so the terminal's lock can be
    /// released before the frame is built. See [SessionHandle].
    pub fn frame_source(&self) -> Result<FrameSource, SessionError> {
        FrameSource::new(self.terminal).map_err(SessionError::Frames)
    }

    pub fn terminal(&self) -> ghostty::GhosttyTerminal {
        self.terminal
    }

    /// Feed a byte stream to the parser.
    ///
    /// The shell's marks are scanned first: the tap observes the bytes and never
    /// changes them, so the parser sees exactly what the child wrote. The block
    /// recorder then sees the same bytes, after the parser, so it can ask the
    /// terminal whether a TUI took the alternate screen in this very read.
    pub fn write(&mut self, bytes: &[u8]) {
        let _ = self.shell.scan(bytes);
        if let Some(name) = self.icon.push(bytes) {
            self.icon_name = Some(name);
        }
        unsafe { ghostty::ghostty_terminal_vt_write(self.terminal, bytes.as_ptr(), bytes.len()) };
        let alternate = self.active_screen() == ghostty::GHOSTTY_TERMINAL_SCREEN_ALTERNATE;
        let cwd = self.shell.directory();
        self.block.observe(bytes, alternate, cwd);
    }

    /// Take the wire records the block recorder has produced since the last drain.
    pub fn drain_block_events(&mut self) -> String {
        self.block.drain()
    }

    /// The exit status the last `D` mark carried, or -1 when the shell sent none.
    pub fn block_exit_status(&self) -> i32 {
        self.block.last_exit_status()
    }

    pub fn resize(&mut self, columns: u16, rows: u16, cell_width: u32, cell_height: u32) -> bool {
        let result = unsafe {
            ghostty::ghostty_terminal_resize(self.terminal, columns, rows, cell_width, cell_height)
        };
        ghostty::ok(result)
    }

    pub fn shell_phase(&self) -> Phase {
        self.shell.phase()
    }

    pub fn working_directory(&self) -> Option<&str> {
        self.shell.directory()
    }

    /// Make sure the engine's search exists for this terminal, creating it
    /// on first use. Returns the handle, or None when the engine refused.
    pub fn ensure_search(&mut self) -> Option<ghostty::GhosttySearch> {
        if self.search.is_none() {
            let mut search = std::ptr::null_mut();
            let result = unsafe {
                ghostty::ghostty_search_new(std::ptr::null(), &mut search, self.terminal)
            };
            if !ghostty::ok(result) {
                return None;
            }
            self.search = Some(search);
        }
        self.search
    }

    /// Free the engine's search, if one exists. Touches the terminal, so the
    /// caller must hold the session lock.
    pub fn free_search(&mut self) {
        if let Some(search) = self.search.take() {
            unsafe { ghostty::ghostty_search_free(search) };
        }
    }

    /// Set the needle. An empty query clears it and returns the search to idle.
    pub fn search_set_needle(&mut self, query: &str) -> bool {
        let Some(search) = self.ensure_search() else {
            return false;
        };
        if query.is_empty() {
            return ghostty::ok(unsafe {
                ghostty::ghostty_search_set(search, ghostty::GHOSTTY_SEARCH_OPT_NEEDLE, std::ptr::null())
            });
        }
        let needle = ghostty::GhosttyString {
            ptr: query.as_ptr(),
            len: query.len(),
        };
        ghostty::ok(unsafe {
            ghostty::ghostty_search_set(
                search,
                ghostty::GHOSTTY_SEARCH_OPT_NEEDLE,
                &needle as *const _ as *const std::ffi::c_void,
            )
        })
    }

    /// Feed the terminal's state into the search. Bounded work.
    pub fn search_feed(&mut self) -> bool {
        let Some(search) = self.search else {
            return false;
        };
        ghostty::ok(unsafe { ghostty::ghostty_search_feed(search) })
    }

    /// Make a bounded amount of progress. Returns the status.
    pub fn search_tick(&mut self) -> i32 {
        let Some(search) = self.search else {
            return 2; // COMPLETE when there is nothing to do
        };
        let mut status = ghostty::GHOSTTY_SEARCH_STATUS_COMPLETE;
        let result = unsafe { ghostty::ghostty_search_tick(search, &mut status) };
        if !ghostty::ok(result) {
            return 2;
        }
        status as i32
    }

    /// Select the next match (toward older content). Returns true when one was selected.
    pub fn search_select_next(&mut self) -> bool {
        let Some(search) = self.search else {
            return false;
        };
        let result = unsafe {
            ghostty::ghostty_search_set(search, ghostty::GHOSTTY_SEARCH_OPT_SELECT_NEXT, std::ptr::null())
        };
        result == ghostty::SUCCESS
    }

    /// Select the previous match (toward newer content).
    pub fn search_select_prev(&mut self) -> bool {
        let Some(search) = self.search else {
            return false;
        };
        let result = unsafe {
            ghostty::ghostty_search_set(search, ghostty::GHOSTTY_SEARCH_OPT_SELECT_PREV, std::ptr::null())
        };
        result == ghostty::SUCCESS
    }

    /// Set the scroll policy for select operations.
    pub fn search_set_select_scroll(&mut self, policy: i32) -> bool {
        let Some(search) = self.search else {
            return false;
        };
        let value = if policy == 0 {
            ghostty::GHOSTTY_SEARCH_SCROLL_IF_NEEDED
        } else {
            ghostty::GHOSTTY_SEARCH_SCROLL_NONE
        };
        ghostty::ok(unsafe {
            ghostty::ghostty_search_set(
                search,
                ghostty::GHOSTTY_SEARCH_OPT_SELECT_SCROLL,
                &value as *const _ as *const std::ffi::c_void,
            )
        })
    }

    /// Total matches on the active screen.
    pub fn search_total_matches(&self) -> usize {
        let Some(search) = self.search else {
            return 0;
        };
        let mut total: usize = 0;
        let result = unsafe {
            ghostty::ghostty_search_get(
                search,
                ghostty::GHOSTTY_SEARCH_DATA_TOTAL_MATCHES,
                &mut total as *mut usize as *mut std::ffi::c_void,
            )
        };
        if ghostty::ok(result) {
            total
        } else {
            0
        }
    }

    /// Index of the selected match, or None.
    pub fn search_selected_index(&self) -> Option<usize> {
        let search = self.search?;
        let mut index: usize = 0;
        let result = unsafe {
            ghostty::ghostty_search_get(
                search,
                ghostty::GHOSTTY_SEARCH_DATA_SELECTED_INDEX,
                &mut index as *mut usize as *mut std::ffi::c_void,
            )
        };
        if result == ghostty::NO_VALUE {
            None
        } else if ghostty::ok(result) {
            Some(index)
        } else {
            None
        }
    }

    /// Viewport row/column/length of the selected match, if it is on screen.
    pub fn search_selected_match_viewport(&self) -> Option<(i32, i32, i32)> {
        let search = self.search?;
        let mut selection: ghostty::GhosttySelection = unsafe { std::mem::zeroed() };
        let result = unsafe {
            ghostty::ghostty_search_get(
                search,
                ghostty::GHOSTTY_SEARCH_DATA_SELECTED_MATCH,
                &mut selection as *mut _ as *mut std::ffi::c_void,
            )
        };
        if result == ghostty::NO_VALUE {
            return None;
        }
        if !ghostty::ok(result) {
            return None;
        }
        let columns = self.columns();
        let rows = self.rows();
        viewport_bounds(self, selection, columns, rows)
    }

    /// The matches on the screen, in viewport coordinates.
    ///
    /// The engine's own list of the matches on the pages that cover the
    /// viewport — the ones the user can see, or nearly. A match that cannot
    /// be placed in the viewport (it is in the scrollback, above the screen)
    /// is left out: the viewport's rows are what the drawing uses.
    ///
    /// A match that runs over a line break covers a row on each side, and
    /// each of those rows is a range of its own.
    pub fn search_viewport_matches(&self) -> Vec<(i32, i32, i32)> {
        let Some(search) = self.search else {
            return Vec::new();
        };

        // The engine's list is a buffer, and the buffer's size is not known
        // until the engine is asked: the first call says how many entries
        // there are, the second fills them.
        let mut query: ghostty::GhosttySelectionBuffer = unsafe { std::mem::zeroed() };
        let result = unsafe {
            ghostty::ghostty_search_get(
                search,
                ghostty::GHOSTTY_SEARCH_DATA_VIEWPORT_MATCHES,
                &mut query as *mut _ as *mut std::ffi::c_void,
            )
        };
        if result == ghostty::NO_VALUE {
            return Vec::new();
        }
        let cap = query.len;
        if cap == 0 {
            return Vec::new();
        }
        let mut selections: Vec<ghostty::GhosttySelection> =
            vec![unsafe { std::mem::zeroed() }; cap];
        query.ptr = selections.as_mut_ptr();
        query.cap = cap;
        let result = unsafe {
            ghostty::ghostty_search_get(
                search,
                ghostty::GHOSTTY_SEARCH_DATA_VIEWPORT_MATCHES,
                &mut query as *mut _ as *mut std::ffi::c_void,
            )
        };
        if !ghostty::ok(result) {
            return Vec::new();
        }

        let columns = self.columns();
        let rows = self.rows();
        let mut ranges = Vec::with_capacity(query.len);
        for selection in selections.iter().take(query.len) {
            ranges.extend(self.match_ranges(*selection, columns, rows));
        }
        ranges
    }

    /// The rows a match covers on the screen.
    ///
    /// A match is two grid references — where it starts and where it ends —
    /// and the engine converts them to the viewport's rows. A match in the
    /// scrollback has no viewport row, and the engine says so: there is
    /// nothing to draw there.
    fn match_ranges(
        &self,
        selection: ghostty::GhosttySelection,
        columns: u16,
        rows: u16,
    ) -> Vec<(i32, i32, i32)> {
        let Some((start_row, start_column)) = self.viewport_point(&selection.start) else {
            return Vec::new();
        };
        let Some((end_row, end_column)) = self.viewport_point(&selection.end) else {
            return Vec::new();
        };
        if start_row < 0 || start_row >= i32::from(rows) {
            return Vec::new();
        }

        let last_row = end_row.min(i32::from(rows) - 1);
        let mut ranges = Vec::new();
        for row in start_row..=last_row {
            // The match's own row starts where the match starts and ends
            // where it ends; the rows in between are covered whole.
            let from = if row == start_row { start_column } else { 0 };
            let to = if row == end_row {
                end_column
            } else {
                i32::from(columns) - 1
            };
            if to >= from {
                ranges.push((row, from, to - from + 1));
            }
        }
        ranges
    }

    /// A grid reference's place in the viewport.
    ///
    /// The engine's own conversion: a cell that is not on the screen has no
    /// viewport row, and the engine answers that it has none.
    fn viewport_point(&self, reference: &ghostty::GhosttyGridRef) -> Option<(i32, i32)> {
        let mut coordinate: ghostty::GhosttyPointCoordinate = unsafe { std::mem::zeroed() };
        let result = unsafe {
            ghostty::ghostty_terminal_point_from_grid_ref(
                self.terminal,
                reference,
                ghostty::GHOSTTY_POINT_TAG_VIEWPORT,
                &mut coordinate,
            )
        };
        if !ghostty::ok(result) {
            return None;
        }
        Some((coordinate.y as i32, coordinate.x as i32))
    }

    /// Text of the selected match, or None.
    pub fn search_selected_match_text(&self) -> Option<String> {
        let search = self.search?;
        let mut selection: ghostty::GhosttySelection = unsafe { std::mem::zeroed() };
        let result = unsafe {
            ghostty::ghostty_search_get(
                search,
                ghostty::GHOSTTY_SEARCH_DATA_SELECTED_MATCH,
                &mut selection as *mut _ as *mut std::ffi::c_void,
            )
        };
        if result == ghostty::NO_VALUE || !ghostty::ok(result) {
            return None;
        }
        // Format the selection into a string.
        let mut options: ghostty::GhosttyTerminalSelectionFormatOptions =
            unsafe { std::mem::zeroed() };
        options.size = std::mem::size_of::<ghostty::GhosttyTerminalSelectionFormatOptions>();
        options.selection = &selection;

        let mut out_ptr: *mut u8 = std::ptr::null_mut();
        let mut out_len: usize = 0;
        let result = unsafe {
            ghostty::ghostty_terminal_selection_format_alloc(
                self.terminal,
                std::ptr::null(),
                options,
                &mut out_ptr,
                &mut out_len,
            )
        };
        if !ghostty::ok(result) || out_ptr.is_null() || out_len == 0 {
            return None;
        }
        let text = unsafe {
            String::from_utf8_lossy(std::slice::from_raw_parts(out_ptr, out_len)).into_owned()
        };
        unsafe { ghostty::ghostty_free(std::ptr::null(), out_ptr, out_len) };
        Some(text)
    }

    /// The theme's colours, handed to the engine.
    ///
    /// The engine paints a cell that carries no colour of its own in *its* two
    /// defaults, and resolves palette indices through *its* palette. If they
    /// are never set they are the library's own — a black background and a
    /// white foreground — so a light theme drew white text on the library's
    /// black no matter what the app's palette said, and SGR 7 in a light theme
    /// produced white-on-white.
    ///
    /// The history the terminal keeps, in lines; zero turns scrollback off
    /// and erases what is already there.
    ///
    /// Lowering the limit prunes immediately, which is why this is safe to call
    /// whenever the setting changes rather than only at session start.
    pub fn set_scrollback_lines(&mut self, lines: u64) {
        unsafe {
            let _ = ghostty::ghostty_terminal_set(
                self.terminal,
                ghostty::GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_LINES,
                &lines as *const u64 as *const c_void,
            );
        }
    }

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

    /// The icon name the program asked for with `OSC 1`.
    ///
    /// Kept apart from the title on purpose: the title is what the program
    /// wants a person to read, and this is the hint it leaves for a window
    /// manager to choose a picture with. A session card uses it to pick its
    /// glyph, and falls back to the title when a program sets none.
    pub fn icon_name(&self) -> Option<&str> {
        self.icon_name.as_deref()
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
    ) -> Option<SelectionViewport> {
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

        if ghostty::ok(result) {
            // The gesture hands back a *snapshot*. The header is explicit that
            // it is not installed: the embedder decides what to do with it.
            // Installing it is what puts CELL_FLAG_SELECTED in the next frame —
            // without this a long press cannot be seen, and the text cannot be
            // copied either, because both read the terminal's selection.
            unsafe {
                ghostty::ghostty_terminal_set(
                    self.terminal,
                    ghostty::GHOSTTY_TERMINAL_OPT_SELECTION,
                    &selection as *const _ as *const c_void,
                );
            }
            return self.selection_viewport();
        }

        // The gesture ended. Report the selection that is installed now so the
        // host's handles and menu follow the final answer.
        self.selection_viewport()
    }

    /// The selection the terminal holds now, resolved to viewport cells.
    ///
    /// The endpoint order is normalised through the engine first: a selection
    /// dragged upward has its start below its end, and a handle placed from
    /// the raw direction would put the top grip on the bottom corner. Ordering
    /// asks the engine, which is the only side that knows the terminal's row
    /// order across page boundaries.
    pub fn selection_viewport(&self) -> Option<SelectionViewport> {
        let selection = self.active_selection()?;
        let columns = self.columns();
        let rows = self.rows();
        if columns == 0 || rows == 0 {
            return Some(SelectionViewport::empty());
        }

        let mut ordered: ghostty::GhosttySelection = unsafe { std::mem::zeroed() };
        ordered.size = std::mem::size_of::<ghostty::GhosttySelection>();
        let ordered_result = unsafe {
            ghostty::ghostty_terminal_selection_ordered(
                self.terminal,
                &selection,
                ghostty::GHOSTTY_SELECTION_ORDER_FORWARD,
                &mut ordered,
            )
        };
        if !ghostty::ok(ordered_result) {
            // Without an ordering there is no honest way to say which endpoint
            // is the top-left of the range on a terminal whose rows cross page
            // boundaries. Reporting nothing is better than a handle on the
            // wrong corner.
            return None;
        }

        let top_left = self.viewport_cell(&ordered.start, columns, rows);
        let bottom_right = self.viewport_cell(&ordered.end, columns, rows);

        // With an endpoint on screen it is the edge of the box by definition:
        // nothing of the selection can be above the top-left endpoint of a
        // linear selection. When the endpoint has scrolled away, the selection
        // either fills that side of the viewport or is not visible at all, and
        // the engine's containment test answers which.
        let top = match top_left {
            Some((row, column)) => Some((row, column)),
            None => {
                if self.contains_viewport(&selection, 0, 0) {
                    Some((0, 0))
                } else {
                    None
                }
            }
        };
        let bottom = match bottom_right {
            Some((row, column)) => Some((row, column)),
            None => {
                if self.contains_viewport(&selection, rows - 1, columns - 1) {
                    Some((i32::from(rows) - 1, i32::from(columns) - 1))
                } else {
                    None
                }
            }
        };

        let (Some((first_row, first_column)), Some((last_row, last_column))) = (top, bottom) else {
            return Some(SelectionViewport::empty());
        };

        Some(SelectionViewport {
            first_row,
            first_column,
            last_row,
            last_column,
            top_left_row: top_left.map(|cell| cell.0).unwrap_or(-1),
            top_left_column: top_left.map(|cell| cell.1).unwrap_or(-1),
            bottom_right_row: bottom_right.map(|cell| cell.0).unwrap_or(-1),
            bottom_right_column: bottom_right.map(|cell| cell.1).unwrap_or(-1),
        })
    }

    /// Move the selection's top-left or bottom-right endpoint to a cell.
    ///
    /// The engine's gesture is not used here: the two grips change a selection
    /// that already exists, and starting a new gesture would count a click and
    /// change a word selection into a different one. Ordering the selection
    /// first makes "top-left" a real corner even for a selection the user made
    /// upward, and the result is installed forward — direction is not part of
    /// the text or the highlight, and a forward range is what the handles
    /// describe.
    pub fn selection_move_boundary(
        &mut self,
        top_left: bool,
        row: u16,
        column: u16,
    ) -> Option<SelectionViewport> {
        let selection = self.active_selection()?;
        let mut ordered: ghostty::GhosttySelection = unsafe { std::mem::zeroed() };
        ordered.size = std::mem::size_of::<ghostty::GhosttySelection>();
        if !ghostty::ok(unsafe {
            ghostty::ghostty_terminal_selection_ordered(
                self.terminal,
                &selection,
                ghostty::GHOSTTY_SELECTION_ORDER_FORWARD,
                &mut ordered,
            )
        }) {
            return None;
        }

        let reference = self.viewport_reference(row, column)?;
        if top_left {
            ordered.start = reference;
        } else {
            ordered.end = reference;
        }
        ordered.size = std::mem::size_of::<ghostty::GhosttySelection>();
        ordered.rectangle = false;
        install_selection(self.terminal, &ordered);
        self.selection_viewport()
    }

    /// Drop the terminal's selection, so the next frame draws no selected cell.
    pub fn clear_selection(&mut self) {
        unsafe {
            ghostty::ghostty_terminal_set(
                self.terminal,
                ghostty::GHOSTTY_TERMINAL_OPT_SELECTION,
                std::ptr::null(),
            );
        }
    }

    /// The active selection, or None when the terminal has none.
    fn active_selection(&self) -> Option<ghostty::GhosttySelection> {
        let mut selection: ghostty::GhosttySelection = unsafe { std::mem::zeroed() };
        selection.size = std::mem::size_of::<ghostty::GhosttySelection>();
        let result = unsafe {
            ghostty::ghostty_terminal_get(
                self.terminal,
                ghostty::GHOSTTY_TERMINAL_DATA_SELECTION,
                &mut selection as *mut _ as *mut c_void,
            )
        };
        if ghostty::ok(result) {
            Some(selection)
        } else {
            None
        }
    }

    /// A selection endpoint's viewport cell, or None when it is off screen.
    fn viewport_cell(
        &self,
        reference: &ghostty::GhosttyGridRef,
        columns: u16,
        rows: u16,
    ) -> Option<(i32, i32)> {
        let (row, column) = self.viewport_point(reference)?;
        if row < 0 || row >= i32::from(rows) || column < 0 || column >= i32::from(columns) {
            return None;
        }
        Some((row, column))
    }

    /// Whether a selection covers a viewport cell.
    fn contains_viewport(
        &self,
        selection: &ghostty::GhosttySelection,
        row: u16,
        column: u16,
    ) -> bool {
        let mut point: ghostty::GhosttyPoint = unsafe { std::mem::zeroed() };
        unsafe {
            crate::jni::set_enum(&mut point.tag, ghostty::GHOSTTY_POINT_TAG_VIEWPORT as u32);
            point.value.coordinate.x = column;
            point.value.coordinate.y = u32::from(row);
        }
        let mut contained = false;
        let result = unsafe {
            ghostty::ghostty_terminal_selection_contains(
                self.terminal,
                selection,
                point,
                &mut contained,
            )
        };
        ghostty::ok(result) && contained
    }

    /// The grid reference of a viewport cell.
    fn viewport_reference(
        &self,
        row: u16,
        column: u16,
    ) -> Option<ghostty::GhosttyGridRef> {
        let mut point: ghostty::GhosttyPoint = unsafe { std::mem::zeroed() };
        unsafe {
            crate::jni::set_enum(&mut point.tag, ghostty::GHOSTTY_POINT_TAG_VIEWPORT as u32);
            point.value.coordinate.x = column;
            point.value.coordinate.y = u32::from(row);
        }
        let mut reference: ghostty::GhosttyGridRef = unsafe { std::mem::zeroed() };
        reference.size = std::mem::size_of::<ghostty::GhosttyGridRef>();
        if !ghostty::ok(unsafe {
            ghostty::ghostty_terminal_grid_ref(self.terminal, point, &mut reference)
        }) {
            return None;
        }
        Some(reference)
    }

    /// The text of the terminal's current selection, or none.
    pub fn selection_text(&self) -> Option<String> {
        let mut options: ghostty::GhosttyTerminalSelectionFormatOptions =
            unsafe { std::mem::zeroed() };
        options.size = std::mem::size_of::<ghostty::GhosttyTerminalSelectionFormatOptions>();
        // The clipboard's own semantics, as the engine documents them: a
        // copied paragraph is one logical line per source line, without the
        // empty cells that pad every row. No selection pointer: the terminal's
        // own selection is what is asked for, and the gesture installed it.
        options.emit = ghostty::GHOSTTY_FORMATTER_FORMAT_PLAIN;
        options.unwrap = true;
        options.trim = true;

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
        // The measuring call is *not* a success: the header documents a null
        // buffer as GHOSTTY_OUT_OF_SPACE with the required size in `needed`.
        // Rejecting that answer rejected every copy before the text was ever
        // written, which is why Kopyala appeared to do nothing.
        if result != ghostty::OUT_OF_SPACE && !ghostty::ok(result) {
            return None;
        }
        if needed == 0 {
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

/// Install a selection snapshot as the terminal's active selection.
fn install_selection(
    terminal: ghostty::GhosttyTerminal,
    selection: &ghostty::GhosttySelection,
) {
    unsafe {
        ghostty::ghostty_terminal_set(
            terminal,
            ghostty::GHOSTTY_TERMINAL_OPT_SELECTION,
            selection as *const _ as *const c_void,
        );
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
            if let Some(search) = self.search.take() {
                ghostty::ghostty_search_free(search);
            }
            ghostty::ghostty_terminal_free(self.terminal);
        }
    }
}

/// Compute the viewport row/column/length of a match selection.
///
/// The engine exposes a match as a *selection* predicate, not a rectangle, so
/// its extent is found by asking which viewport cells it contains. Returns
/// None when the match is not on the visible viewport.
pub fn viewport_bounds(
    session: &Session,
    selection: ghostty::GhosttySelection,
    columns: u16,
    rows: u16,
) -> Option<(i32, i32, i32)> {
    let mut first_row: i32 = -1;
    let mut first_column: i32 = -1;
    let mut last_column: i32 = -1;

    for row in 0..rows {
        for column in 0..columns {
            let mut point: ghostty::GhosttyPoint = unsafe { std::mem::zeroed() };
            unsafe {
                crate::jni::set_enum(&mut point.tag, ghostty::GHOSTTY_POINT_TAG_VIEWPORT as u32);
                point.value.coordinate.x = column;
                point.value.coordinate.y = u32::from(row);
            }

            let mut contained = false;
            let result = unsafe {
                ghostty::ghostty_terminal_selection_contains(
                    session.terminal(),
                    &selection,
                    point,
                    &mut contained,
                )
            };
            if !ghostty::ok(result) || !contained {
                continue;
            }

            if first_row < 0 {
                first_row = i32::from(row);
                first_column = i32::from(column);
            }
            last_column = i32::from(column);
        }
    }

    if first_row < 0 {
        return None;
    }
    Some((first_row, first_column, (last_column - first_column + 1).max(0)))
}

/// A session as the JNI layer holds it: the terminal and its render state.
///
/// ## Two locks, two jobs
///
/// The lock on the session is not an optimisation. Two threads reach a terminal
/// — the PTY reader that feeds it and the render thread that draws it — and
/// the engine's API is single-threaded. Without this the two interleave
/// inside the parser, which produces frames that are half one screen and half
/// another.
///
/// The render state has its own lock because it is not the terminal. The
/// engine's render state is updated in two phases: the begin phase reads the
/// terminal, and the end phase finishes the work on the render state's own
/// memory. So the terminal's lock is held for the begin phase and released
/// before the frame is built — which is what keeps the keyboard responsive
/// while a program floods the screen.
///
/// ## The order
///
/// The session lock is always taken before the render lock, never the other
/// way round. The two are never held at the same time except in the begin
/// phase, where both are needed.
pub struct SessionHandle {
    /// The render state comes first so that it is dropped first.
    ///
    /// The render state holds tracked state inside the terminal, so it has to
    /// be released before the terminal is. Rust drops a struct's fields in the
    /// order they are declared, and this is the one place that order matters.
    frames: Mutex<FrameSource>,
    session: Mutex<Session>,
    /// The last preview rendered for the session grid, and the frame it came
    /// from. A session that is not changing must not be re-rasterized and
    /// re-encoded on every poll: the grid asks every second or so, and a still
    /// screen would pay for a new PNG each time.
    preview: Mutex<Option<PreviewCache>>,
}

impl SessionHandle {
    /// Box a session and hand back the raw pointer the JNI layer stores.
    pub fn into_raw(session: Session, frames: FrameSource) -> *mut SessionHandle {
        Box::into_raw(Box::new(Self {
            session: Mutex::new(session),
            frames: Mutex::new(frames),
            preview: Mutex::new(None),
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

    /// Take the render state's lock.
    ///
    /// The render state is the snapshot the renderer reads. It has its own lock
    /// because the terminal's lock must be free while the frame is built.
    pub fn frames_lock(&self) -> MutexGuard<'_, FrameSource> {
        self.frames.lock().unwrap_or_else(|poisoned| {
            log::warn!("drosh-native: a render state lock was poisoned; recovering");
            poisoned.into_inner()
        })
    }

    /// Produce the next frame.
    ///
    /// The terminal's lock is held for the begin phase only. The end phase and
    /// the row iteration that follows it work on the render state's own
    /// memory, and the terminal is free to be written to while they run.
    pub fn render_frame(&self) -> Option<Vec<u8>> {
        self.with_frame(|bytes| bytes.to_vec())
    }

    /// The last screenful as a PNG, for the session grid's card.
    ///
    /// The picture is the renderer's own cells, drawn at a size a card can
    /// show, so a session in the background — with no Surface, no pane and no
    /// frame being drawn — still has a truthful preview. Nothing here needs the
    /// session to be on screen, which is what the old PixelCopy path did and
    /// why every background card was a stale screenshot.
    ///
    /// The encoding is cached against the frame it was drawn from, so a still
    /// session costs a hash and a clone rather than a rasterization.
    ///
    /// Blocking: this is a frame iteration plus, when the screen changed, a
    /// rasterization. Callers off the main thread only.
    pub fn snapshot_png(&self, max_width: u32) -> Option<Vec<u8>> {
        self.with_frame(|frame| {
            let key = preview_key(frame, max_width);
            {
                let cache = self
                    .preview
                    .lock()
                    .unwrap_or_else(|poisoned| poisoned.into_inner());
                if let Some(cached) = cache.as_ref() {
                    if cached.key == key && !cached.png.is_empty() {
                        return Some(cached.png.clone());
                    }
                }
            }
            let snapshot = crate::renderer::snapshot::render(frame, max_width)?;
            let png = crate::png::encode_rgba(snapshot.width, snapshot.height, &snapshot.rgba)?;
            let mut cache = self
                .preview
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            *cache = Some(PreviewCache {
                key,
                png: png.clone(),
            });
            Some(png)
        })?
    }

    /// Produce the next frame and hand it to [f].
    ///
    /// The frame is only valid while the render state's lock is held, so the
    /// bytes are given to a function rather than returned: a caller that
    /// wanted to keep them would have to copy them, and the copy is the one
    /// thing this arrangement exists to avoid.
    ///
    /// The terminal's lock is held for the begin phase only. The end phase and
    /// the row iteration that follows it work on the render state's own
    /// memory, and the terminal is free to be written to while they run.
    pub fn with_frame<R>(&self, f: impl FnOnce(&[u8]) -> R) -> Option<R> {
        let session = self.lock();
        let mut frames = self.frames_lock();
        if frames.begin_update().is_err() {
            return None;
        }
        // The terminal is free again. Everything below is the renderer's own
        // work and does not need it.
        drop(session);
        match frames.end_update() {
            Ok(bytes) => Some(f(bytes)),
            Err(error) => {
                log::debug!("drosh-native: no frame: {error:?}");
                None
            }
        }
    }

    /// The colours of a region of the grid, as `(foreground, background)` pairs.
    ///
    /// The frame has to exist before its cells can be read: the colours are
    /// the frame's, and the frame is what the engine produced last.
    pub fn cell_colors(
        &self,
        first_row: u16,
        first_column: u16,
        rows: u16,
        columns: u16,
    ) -> Vec<u32> {
        let session = self.lock();
        let mut frames = self.frames_lock();
        if frames.begin_update().is_err() {
            return Vec::new();
        }
        drop(session);
        if frames.end_update().is_err() {
            return Vec::new();
        }
        frames.cell_colors(first_row, first_column, rows, columns)
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
