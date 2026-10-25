//! A terminal session: the engine, its event loop thread, the PTY it drives,
//! and the buffers the JNI layer hands to Kotlin.
//!
//! The PTY itself is [`drosh_pty`] and the wire formats are [`drosh_bridge`];
//! this crate is the behaviour that ties them to `alacritty_terminal`.
//!
//! Lock order (never violated, here and in `drosh-core`'s `ffi`): `term` →
//! `damage_scratch` → `damage`. Taking them in any other order would deadlock
//! against a concurrent snapshot.

pub mod block;
pub mod cells;
pub mod grid;
pub mod manager;
pub mod search;
pub mod selection;
pub mod shell_mark;
pub mod tap;
pub mod text;
pub mod tint;
pub mod transcript;
pub mod url;

use std::sync::atomic::{AtomicU32, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::thread::{self, JoinHandle};

use alacritty_terminal::event::WindowSize;
use alacritty_terminal::event_loop::{EventLoop, EventLoopSender, Msg};
use alacritty_terminal::grid::{Dimensions, Scroll};
use alacritty_terminal::sync::FairMutex;
use alacritty_terminal::term::color::COUNT as COLOR_COUNT;
use alacritty_terminal::term::{Config, LineDamageBounds, Osc52, Term};
use alacritty_terminal::vte::ansi::{CursorShape, CursorStyle, Processor, Rgb, StdSyncHandler};

use crate::block::{BlockPhase, BlockTracker};
use crate::transcript::{OutputLog, TranscriptHit, DEFAULT_MAX_LINES};
use drosh_bridge::events::{
    EventProxy, EventQueue, SessionEvent, Terminator, DEFAULT_QUEUE_CAPACITY,
};
use drosh_bridge::snapshot;
use drosh_pty::{LocalPty, PtyConfig};

/// Grid dimensions of a session.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct GridSize {
    columns: u16,
    rows: u16,
}

impl GridSize {
    /// The engine does not work below these bounds.
    pub const MIN_COLUMNS: u16 = 2;
    pub const MIN_ROWS: u16 = 1;

    pub const fn new(columns: u16, rows: u16) -> Self {
        Self { columns, rows }
    }

    pub const fn cols(&self) -> usize {
        self.columns as usize
    }

    pub const fn rows(&self) -> usize {
        self.rows as usize
    }

    /// Whether the engine can be asked to use this size.
    pub fn is_valid(&self) -> bool {
        self.columns >= Self::MIN_COLUMNS && self.rows >= Self::MIN_ROWS
    }
}

impl Dimensions for GridSize {
    fn total_lines(&self) -> usize {
        self.rows()
    }

    fn screen_lines(&self) -> usize {
        self.rows()
    }

    fn columns(&self) -> usize {
        self.cols()
    }
}

/// Everything needed to start a session. The prototype has no config system
/// (D12): the caller passes exactly what it wants.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SessionConfig {
    pub size: GridSize,
    pub cell_width: u16,
    pub cell_height: u16,
    /// Program to run on the slave side (`/system/bin/sh`, `chroot-ng`, …).
    pub program: String,
    pub args: Vec<String>,
    /// Extra environment variables; `TERM`, `COLORTERM` and `LANG` have
    /// defaults, `PATH` only on Android.
    pub env: Vec<(String, String)>,
    pub cwd: Option<String>,
    /// The cursor's shape when the terminal has not asked for one.
    ///
    /// alacritty's own fallback (`TermConfig::default_cursor_style`): the
    /// terminal's `DECSCUSR` wins, and this is what it falls back on.
    pub cursor_shape: CursorShape,
}

/// What can go wrong around a session.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SessionError {
    InvalidArgument(String),
    InvalidHandle,
    LimitReached,
    SpawnFailed(String),
    Io(String),
    Internal(String),
}

impl std::fmt::Display for SessionError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::InvalidArgument(reason) => write!(formatter, "invalid argument: {reason}"),
            Self::InvalidHandle => write!(formatter, "unknown session"),
            Self::LimitReached => write!(formatter, "session limit reached"),
            Self::SpawnFailed(reason) => write!(formatter, "spawn failed: {reason}"),
            Self::Io(reason) => write!(formatter, "io error: {reason}"),
            Self::Internal(reason) => write!(formatter, "internal error: {reason}"),
        }
    }
}

impl std::error::Error for SessionError {}

/// A live terminal session.
pub struct Session {
    id: u64,
    term: Arc<FairMutex<Term<EventProxy>>>,
    events: Arc<EventQueue>,
    /// Event-loop sender; `None` for a bridged session, which has no PTY and
    /// no loop of its own.
    sender: Mutex<Option<EventLoopSender>>,
    size: Arc<Mutex<WindowSize>>,
    damage: Mutex<Vec<i32>>,
    damage_scratch: Mutex<Vec<LineDamageBounds>>,
    /// Escape-sequence parser for a bridged session; the PTY path is parsed by
    /// alacritty's event loop instead.
    parser: Mutex<Processor<StdSyncHandler>>,
    /// Where the shell is in its cycle, as the OSC 133 marks report it.
    blocks: Mutex<BlockTracker>,
    /// The last lines the session printed, for a session log.
    output: Arc<Mutex<OutputLog>>,
    /// The term's configuration, kept so it can be changed while it runs.
    ///
    /// alacritty's term can be reconfigured while it runs
    /// (`Term::set_options`), and the cursor's shape is part of that
    /// configuration. The term keeps its own copy and does not hand it out,
    /// so the session keeps one of its own.
    config: Mutex<Config>,
    worker: Mutex<Option<JoinHandle<()>>>,
    /// Whole rows the viewport sits above the live edge — kitty's
    /// `scrolled_by`. Kept here rather than derived from the term on every
    /// scroll, so the pixel total and the row count cannot drift apart.
    rows_above_edge: AtomicUsize,
    /// The sub-line remainder of that position, in pixels — kitty's
    /// `pixel_scroll_offset_y`.
    sub_line_px: AtomicU32,
}

impl Session {
    /// Start a PTY, the engine and the event loop for session `id`.
    pub fn spawn(id: u64, config: &SessionConfig) -> Result<Self, SessionError> {
        validate(config)?;

        let window_size = WindowSize {
            num_lines: config.size.rows,
            num_cols: config.size.columns,
            cell_width: config.cell_width,
            cell_height: config.cell_height,
        };

        let events = Arc::new(EventQueue::new(DEFAULT_QUEUE_CAPACITY));
        let sender_slot = Arc::new(Mutex::new(None));
        let size_slot = Arc::new(Mutex::new(window_size));

        let proxy = EventProxy::new(events.clone(), sender_slot.clone(), size_slot.clone());
        // OSC 52 paste is enabled because the prototype has no remote transport
        // (D12): only the local shell can read the clipboard. alacritty defaults
        // to `OnlyCopy` because a remote could exfiltrate it — revisit this when
        // SSH lands (PLAN.md §8).
        let term_config = Config {
            osc52: Osc52::CopyPaste,
            default_cursor_style: CursorStyle { shape: config.cursor_shape, blinking: false },
            ..Config::default()
        };
        let terminal = Term::new(term_config.clone(), &config.size, proxy.clone());
        let term = Arc::new(FairMutex::new(terminal));

        let pty = LocalPty::spawn(&PtyConfig {
            program: config.program.clone(),
            args: config.args.clone(),
            env: config.env.clone(),
            cwd: config.cwd.clone(),
            columns: config.size.columns,
            rows: config.size.rows,
            cell_width: config.cell_width,
            cell_height: config.cell_height,
        })
        .map_err(|err| SessionError::SpawnFailed(err.to_string()))?;

        // The output is copied on its way to the parser, so a session log can
        // be read out of it: the scrollback inside the grid cannot.
        let output = Arc::new(Mutex::new(OutputLog::new(DEFAULT_MAX_LINES)));

        // Shell-integration marks are tapped where the bytes enter the
        // engine; the parser itself never sees them disappear.
        let pty = tap::TapPty::new(pty, events.clone(), output.clone())
            .map_err(|err| SessionError::Io(err.to_string()))?;

        // `drain_on_exit`: the shell's last output must still reach the grid
        // after the child is gone. `ref_test` is alacritty's replay format —
        // not used by the prototype.
        let event_loop = EventLoop::new(term.clone(), proxy, pty, true, false)
            .map_err(|err| SessionError::Io(err.to_string()))?;

        let sender = event_loop.channel();
        *lock(&sender_slot) = Some(sender.clone());
        let sender = Mutex::new(Some(sender));

        let worker = thread::Builder::new()
            .name(format!("drosh-session-{id}"))
            .spawn(move || {
                // The engine runs the loop on a thread of its own; joining it
                // here means the PTY (and with it the child process) is torn
                // down when the loop ends, even though nobody joins us.
                if let Err(err) = event_loop.spawn().join() {
                    log::error!("drosh-core: event loop for session {id} died: {err:?}");
                }
            })
            .map_err(|err| SessionError::Internal(err.to_string()))?;

        Ok(Self {
            id,
            term,
            events,
            sender,
            size: size_slot,
            damage: Mutex::new(vec![0i32; snapshot::damage_out_len(config.size.rows())]),
            damage_scratch: Mutex::new(Vec::new()),
            parser: Mutex::new(Processor::new()),
            blocks: Mutex::new(BlockTracker::new()),
            output,
            config: Mutex::new(term_config),
            worker: Mutex::new(Some(worker)),
            rows_above_edge: AtomicUsize::new(0),
            sub_line_px: AtomicU32::new(0),
        })
    }

    /// Start a session that has no PTY of its own: bytes arrive through
    /// [`Session::feed`] (from SSH, or any other transport the host owns) and
    /// escape-sequence replies surface as `SessionEvent::PtyWrite`.
    pub fn spawn_bridged(
        id: u64,
        size: GridSize,
        cell_width: u16,
        cell_height: u16,
    ) -> Result<Self, SessionError> {
        if !size.is_valid() {
            return Err(SessionError::InvalidArgument(format!("invalid grid size {size:?}")));
        }

        let window_size =
            WindowSize { num_lines: size.rows, num_cols: size.columns, cell_width, cell_height };

        let events = Arc::new(EventQueue::new(DEFAULT_QUEUE_CAPACITY));
        let sender_slot = Arc::new(Mutex::new(None));
        let size_slot = Arc::new(Mutex::new(window_size));

        let proxy = EventProxy::new(events.clone(), sender_slot, size_slot.clone());
        // Same paste policy as a PTY session; see `spawn`.
        let term_config = Config { osc52: Osc52::CopyPaste, ..Config::default() };
        let terminal = Term::new(term_config.clone(), &size, proxy);

        Ok(Self {
            id,
            term: Arc::new(FairMutex::new(terminal)),
            events,
            sender: Mutex::new(None),
            size: size_slot,
            damage: Mutex::new(vec![0i32; snapshot::damage_out_len(size.rows())]),
            damage_scratch: Mutex::new(Vec::new()),
            parser: Mutex::new(Processor::new()),
            blocks: Mutex::new(BlockTracker::new()),
            output: Arc::new(Mutex::new(OutputLog::new(DEFAULT_MAX_LINES))),
            config: Mutex::new(term_config),
            worker: Mutex::new(None),
            rows_above_edge: AtomicUsize::new(0),
            sub_line_px: AtomicU32::new(0),
        })
    }

    pub fn id(&self) -> u64 {
        self.id
    }

    /// Send input to the PTY.
    pub fn write(&self, data: &[u8]) -> Result<(), SessionError> {
        if data.is_empty() {
            return Ok(());
        }

        let sender = lock(&self.sender);
        let sender = sender.as_ref().ok_or_else(|| {
            SessionError::Internal("bridged session: the host routes input".to_owned())
        })?;
        sender.send(Msg::Input(data.to_vec().into())).map_err(io_error)
    }

    /// Feed output bytes into the engine (a bridged session; a PTY session's
    /// bytes arrive through the event loop instead).
    pub fn feed(&self, data: &[u8]) {
        if data.is_empty() {
            return;
        }

        // A bridged session has no PTY and so no tap, but it has output all
        // the same — and a session log that is empty for every remote session
        // is worse than none.
        if let Ok(mut output) = self.output.lock() {
            output.push(data);
        }
        let mut term = self.term.lock();
        let mut parser = lock(&self.parser);
        parser.advance(&mut *term, data);
    }

    /// Resize grid, PTY and renderer state.
    pub fn resize(
        &self,
        size: GridSize,
        cell_width: u16,
        cell_height: u16,
    ) -> Result<(), SessionError> {
        if !size.is_valid() {
            return Err(SessionError::InvalidArgument(format!("invalid grid size {size:?}")));
        }

        let window_size =
            WindowSize { num_lines: size.rows, num_cols: size.columns, cell_width, cell_height };

        *lock(&self.size) = window_size;
        {
            // `Term::resize` marks everything damaged, so the next snapshot is
            // a full redraw.
            let mut term = self.term.lock();
            term.resize(size);
        }
        lock(&self.damage).resize(snapshot::damage_out_len(size.rows()), 0);

        if let Some(sender) = lock(&self.sender).as_ref() {
            sender.send(Msg::Resize(window_size)).map_err(io_error)?;
        }
        Ok(())
    }

    /// Scroll the viewport (positive = back into the scrollback history).
    pub fn scroll(&self, delta: i32) -> Result<(), SessionError> {
        if delta == 0 {
            return Ok(());
        }

        // `scroll_display` marks the whole viewport damaged when the offset
        // actually changed.
        let mut term = self.term.lock();
        let before = term.grid().display_offset();
        term.scroll_display(Scroll::Delta(delta));
        let after = term.grid().display_offset();
        drop(term);
        // A whole-row scroll discards the fraction: the two cannot be added
        // without saying which one the user meant, and the absolute position
        // is what a keyboard scroll is about.
        self.rows_above_edge.store(after, Ordering::Relaxed);
        self.sub_line_px.store(0f32.to_bits(), Ordering::Relaxed);
        let _ = before;
        Ok(())
    }

    /// Scroll by a finger distance, keeping the sub-line remainder.
    ///
    /// The model is kitty's (`kitty/kitty/screen.c` — `scroll_by_pixels`): one
    /// absolute total in pixels, split into whole rows for the engine and a
    /// remainder for the renderer. The two were apart — the engine moved by
    /// rows, Kotlin kept the fraction — and a pan carried them out of step, so
    /// the grid stepped while the finger slid.
    ///
    /// `delta_pixels` is positive when the content should move down, i.e. when
    /// the user is dragging back into the history. `cell_height` is the height
    /// of a row in the same pixels.
    ///
    /// Returns whether anything moved.
    pub fn scroll_by_pixels(&self, delta_pixels: f32, cell_height: u16) -> bool {
        let cell = f32::from(cell_height);
        if cell <= 0.0 || delta_pixels == 0.0 || !delta_pixels.is_finite() {
            return false;
        }

        let total = f32::from_bits(self.sub_line_px.load(Ordering::Relaxed))
            + self.rows_above_edge.load(Ordering::Relaxed) as f32 * cell
            + delta_pixels;

        // The engine clamps the rows it can actually show; the remainder is
        // clamped with them, so a finger that runs past the end does not leave
        // the grid offset by a line that is not there.
        let max_rows = self.history_rows();
        let max_total = max_rows as f32 * cell;
        let total = total.clamp(0.0, max_total);

        // At either end the remainder is zero by definition.
        //
        // At the live edge there is no row above to be part-way into, and at
        // the oldest row there is no older row to slide toward. Keeping a
        // fraction at a limit is what let a drag "move" past the end: the grid
        // shifted by a few pixels under a finger that had nothing left to
        // reveal.
        let at_end = total <= 0.0 || total >= max_total;
        let rows =
            if at_end { (total / cell).round() as usize } else { (total / cell).floor() as usize };
        let remainder = if at_end { 0.0 } else { total - rows as f32 * cell };

        let previous_rows = self.rows_above_edge.load(Ordering::Relaxed);
        let previous_remainder = f32::from_bits(self.sub_line_px.load(Ordering::Relaxed));
        if rows == previous_rows && (remainder - previous_remainder).abs() < 0.01 {
            return false;
        }

        self.rows_above_edge.store(rows, Ordering::Relaxed);
        self.sub_line_px.store(remainder.to_bits(), Ordering::Relaxed);
        if rows != previous_rows {
            // `rows_above_edge` counts *up* into the history; the engine's
            // `scroll_display` takes a signed delta on its own scale.
            let delta = previous_rows as i32 - rows as i32;
            self.term.lock().scroll_display(Scroll::Delta(delta));
        }
        true
    }

    /// How many rows of history the viewport can move through.
    fn history_rows(&self) -> usize {
        let term = self.term.lock();
        let grid = term.grid();
        grid.total_lines().saturating_sub(grid.screen_lines())
    }

    /// The sub-line part of the scroll, in pixels. The renderer shifts the grid
    /// by this so a drag follows the finger between rows.
    pub fn sub_line_pixels(&self) -> f32 {
        f32::from_bits(self.sub_line_px.load(Ordering::Relaxed))
    }

    /// Whole rows the viewport sits above the live edge.
    pub fn rows_above_edge(&self) -> usize {
        self.rows_above_edge.load(Ordering::Relaxed)
    }

    /// Fill `buffer` with the damaged cells and return `damage[0]`:
    /// [`snapshot::DAMAGE_FULL`] or the number of damaged lines.
    pub fn take_snapshot(&self, buffer: &mut [u8]) -> Result<i32, snapshot::SnapshotError> {
        let mut term = self.term.lock();
        let mut scratch = lock(&self.damage_scratch);
        let mut damage = lock(&self.damage);

        snapshot::take_snapshot(&mut term, buffer, &mut damage, &mut scratch)?;
        Ok(damage[0])
    }

    /// Run `f` with the damage list of the last [`Session::take_snapshot`].
    pub fn with_damage<R>(&self, f: impl FnOnce(&[i32]) -> R) -> R {
        f(&lock(&self.damage))
    }

    /// Snapshot the renderer state (size, cursor, modes).
    pub fn state(&self) -> [i32; snapshot::STATE_LEN] {
        let mut out = [0i32; snapshot::STATE_LEN];
        snapshot::write_state(&self.term.lock(), &mut out);
        out[snapshot::state_index::SUB_LINE_PX] = self.sub_line_pixels() as i32;
        out
    }

    /// Take and clear the "something changed" flag.
    pub fn poll_wakeup(&self) -> bool {
        self.events.take_wakeup()
    }

    /// Remove and return every queued event.
    ///
    /// A colour query the engine can answer itself — the application overrode
    /// that colour with `OSC 4/10/11/12` — is answered here and never reaches
    /// Kotlin; only queries about the default palette are handed over, because
    /// the palette lives on the Kotlin side (D5).
    pub fn drain_events(&self) -> Vec<SessionEvent> {
        let events = self.events.drain();
        let mut pending = Vec::with_capacity(events.len());

        for event in events {
            match event {
                SessionEvent::ColorQuery { index, prefix, terminator } => {
                    // The engine produces these from its own colour indices, but
                    // an out-of-range one must not abort the process across JNI.
                    let Ok(index) = usize::try_from(index) else { continue };
                    if index >= COLOR_COUNT {
                        continue;
                    }

                    match self.with_term(|term| term.colors()[index]) {
                        Some(rgb) => {
                            let reply = color_reply(&prefix, rgb, terminator);
                            if let Err(err) = self.write(reply.as_bytes()) {
                                log::warn!("drosh-core: colour reply failed: {err}");
                            }
                        }
                        None => pending.push(SessionEvent::ColorQuery {
                            index: index as i32,
                            prefix,
                            terminator,
                        }),
                    }
                }
                SessionEvent::ShellMark(mark) => {
                    // The marks are the block engine's input: the tracker turns
                    // them into "a prompt started", "a command finished" and the
                    // working directory, so the engine does not have to diff the
                    // transcript to find the boundaries.
                    self.blocks.lock().unwrap_or_else(|e| e.into_inner()).observe(&mark);
                    pending.push(SessionEvent::ShellMark(mark));
                }
                other => pending.push(other),
            }
        }

        pending
    }

    /// The last lines the session printed, oldest first, with the escape
    /// sequences stripped.
    ///
    /// These are the lines as they arrived, not as the screen drew them: a
    /// progress bar that redrew its line shows every draw. That is what the
    /// output was, and a log of what a program printed is more useful than a
    /// log of what a screen happened to show.
    pub fn transcript(&self) -> Vec<String> {
        let output = self.output.lock().unwrap_or_else(|e| e.into_inner());
        output.readable_lines()
    }

    /// Case-insensitive substring search over the output log, up to `limit`
    /// matches.
    ///
    /// The log is the engine's bounded copy of the output, so this reaches
    /// further back than the grid does — but its coordinates are the log's:
    /// the index of the line and the column range within that line. A line of
    /// the log is an output line, not a grid row, so the answer cannot be
    /// turned into a position on the screen.
    pub fn search_transcript(&self, query: &str, limit: usize) -> Vec<TranscriptHit> {
        let output = self.output.lock().unwrap_or_else(|e| e.into_inner());
        output.search(query, limit)
    }

    /// Where the shell is in its cycle, as the marks report it: the phase, the
    /// working directory and the editor target.
    pub fn block_state(&self) -> (BlockPhase, Option<String>, Option<String>) {
        let tracker = self.blocks.lock().unwrap_or_else(|e| e.into_inner());
        (tracker.phase(), tracker.cwd().map(str::to_owned), tracker.editor().map(str::to_owned))
    }

    /// Run `f` with the engine's terminal, holding its lock.
    pub fn with_term<R>(&self, f: impl FnOnce(&mut Term<EventProxy>) -> R) -> R {
        let mut term = self.term.lock();
        f(&mut term)
    }

    /// The cursor's shape, when the terminal has not asked for one.
    ///
    /// alacritty's own fallback: the terminal's `DECSCUSR` wins, and the
    /// application's setting is what it falls back on. The term's
    /// configuration is fixed when the session starts, but alacritty's term
    /// can be reconfigured while it runs (`Term::set_options`), so this can
    /// too — which is what makes the setting take effect without a new
    /// session.
    pub fn set_cursor_shape(&self, shape: CursorShape) {
        let next = {
            let mut guard = lock(&self.config);
            guard.default_cursor_style = CursorStyle { shape, blinking: false };
            guard.clone()
        };

        self.with_term(|term| term.set_options(next.clone()));
    }

    /// Stop the event loop; the PTY and the child are torn down when the loop
    /// thread returns.
    pub fn shutdown(&self) {
        if let Some(sender) = lock(&self.sender).take() {
            let _ = sender.send(Msg::Shutdown);
        }
        drop(lock(&self.worker).take());
    }

    // ── Capabilities (P1) ─────────────────────────────────────────────────────
    //
    // Each delegates to `session/{text,url,search,tint,selection}` so the FFI
    // layer (and Kotlin) only ever touches `Session` — never the engine grid.

    /// Strip ANSI escape sequences from `input`.
    pub fn strip_ansi(&self, input: &[u8]) -> String {
        crate::text::strip_ansi(input)
    }

    /// URLs on the visible grid, up to `limit`.
    pub fn find_urls(&self, limit: usize) -> Vec<crate::url::UrlHit> {
        self.with_term(|term| crate::url::find_urls(term, limit))
    }

    /// The URL covering visible (row, col), if any.
    pub fn url_at(&self, row: usize, col: usize) -> Option<String> {
        self.with_term(|term| crate::url::url_at(term, row, col))
    }

    /// Substring search over the visible grid, up to `limit` matches.
    pub fn search(&self, query: &str, limit: usize) -> Vec<crate::search::SearchHit> {
        self.with_term(|term| crate::search::search(term, query, limit))
    }

    /// Average background colour of the visible grid, if any cell sets one.
    pub fn ambient_tint(&self) -> Option<[u8; 3]> {
        self.with_term(|term| crate::tint::ambient_tint(term))
    }

    /// The colours of the cells in a rectangle of the visible grid.
    ///
    /// The rectangle is clipped to the grid, so a region that hangs over the
    /// edge reports the cells that are there.
    pub fn cell_colors(&self, region: crate::cells::CellRegion) -> Vec<crate::cells::CellColor> {
        self.with_term(|term| crate::cells::cell_colors(term, region))
    }

    /// Currently selected text, if a selection is active.
    pub fn selection_text(&self) -> Option<String> {
        self.with_term(|term| crate::selection::selection_text(term))
    }

    /// Select the word under (row, col); returns the selected text.
    pub fn select_word(&self, row: usize, col: usize) -> Option<String> {
        self.with_term(|term| crate::selection::select_word(term, row, col))
    }

    /// Select the whole line under (row, col); returns the selected text.
    pub fn select_line(&self, row: usize, col: usize) -> Option<String> {
        self.with_term(|term| crate::selection::select_line(term, row, col))
    }

    /// Clear any active selection.
    pub fn clear_selection(&self) {
        self.with_term(crate::selection::clear);
    }
}

/// Spawn a bridged session for unit tests without touching the global handle
/// table, so tests never exhaust `MAX_SESSIONS` and never bleed state into one
/// another. Each call gets its own throwaway `Manager`, so the only thing that
/// survives the call is the returned `Arc<Session>` (kept alive by the caller).
#[cfg(test)]
pub(crate) fn test_session() -> std::sync::Arc<Session> {
    let mut mgr = crate::manager::Manager::new();
    let id = mgr.spawn_bridged(crate::GridSize::new(80, 24), 10, 20).unwrap();
    mgr.get(id).unwrap()
}

impl Drop for Session {
    fn drop(&mut self) {
        // Sessions never outlive their handle: losing the last reference must
        // not leave a child process behind.
        self.shutdown();
    }
}

/// Recover from a poisoned lock: one panicking caller must not take the session
/// with it.
fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// alacritty's own reply format (`alacritty_terminal/src/term/mod.rs`,
/// `dynamic_color_sequence`): every component is printed twice, e.g.
/// `\x1b]4;1;rgb:1212/3434/5656\x07`.
pub fn color_reply(prefix: &str, color: Rgb, terminator: Terminator) -> String {
    format!(
        "\x1b]{};rgb:{:02x}{:02x}/{:02x}{:02x}/{:02x}{:02x}{}",
        prefix,
        color.r,
        color.r,
        color.g,
        color.g,
        color.b,
        color.b,
        terminator.as_str(),
    )
}

fn io_error(err: impl std::fmt::Display) -> SessionError {
    SessionError::Io(err.to_string())
}

fn validate(config: &SessionConfig) -> Result<(), SessionError> {
    if !config.size.is_valid() {
        return Err(SessionError::InvalidArgument(format!("invalid grid size {:?}", config.size)));
    }
    if config.program.is_empty() {
        return Err(SessionError::InvalidArgument("program is empty".to_owned()));
    }
    if config.program.contains('\0') {
        return Err(SessionError::InvalidArgument("program contains a NUL byte".to_owned()));
    }

    if config.args.iter().any(|arg| arg.contains('\0')) {
        return Err(SessionError::InvalidArgument("argument contains a NUL byte".to_owned()));
    }

    for (key, value) in &config.env {
        if key.is_empty() || key.contains('=') || key.contains('\0') || value.contains('\0') {
            return Err(SessionError::InvalidArgument(format!(
                "invalid environment entry {key:?}"
            )));
        }
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn colour_replies_match_alacritty() {
        let bell = color_reply("4;1", Rgb { r: 0x12, g: 0x34, b: 0x56 }, Terminator::Bell);
        assert_eq!(bell, "\x1b]4;1;rgb:1212/3434/5656\x07");

        let st = color_reply("11", Rgb { r: 0, g: 0, b: 0xff }, Terminator::St);
        assert_eq!(st, "\x1b]11;rgb:0000/0000/ffff\x1b\\");
    }
}
