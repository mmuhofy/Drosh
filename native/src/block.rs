//! Engine-driven command blocks.
//!
//! The shell writes `OSC 133` marks into its own output. `shell::Scanner` turns
//! those marks into a phase and a working directory; this module turns them into
//! *blocks*: one prompt, one command, one stretch of output, one exit status.
//! It is a second, independent tap on the same bytes — see the note on
//! [`BlockRecorder::observe`] for why the two scanners do not share code.
//!
//! ## The wire format (defined here, parsed by `EngineBlockBridge` on Kotlin)
//!
//! Records are separated by `\n`; fields by exactly one space. Every field that
//! can contain text is hexadecimal UTF-8 (output is raw PTY bytes, SGR included),
//! so a tab, a newline or a wide glyph can never break the record boundaries:
//!
//! ```text
//! S <idHex> <promptHex> <commandHex> <cwdHex> <startedMsDecimal>
//! O <idHex> <outputBytesHex>
//! F <idHex> <exitCodeDecimal> <completedMsDecimal>
//! ```
//!
//! `idHex` is a lowercase hexadecimal, per-session monotonic counter. A block is
//! opened at `C` (command exec), grows with `O` records as output arrives, and is
//! closed at `D` (finished) with the status the shell reported. `A`/`B` bracket
//! the prompt and the command bytes between them. A block that never reaches
//! `D` is abandoned when the next `A` arrives — the repository closes its own
//! running block at that point.
//!
//! The recorder keeps the last [`MAX_BLOCKS`] blocks. Draining drains *records*,
//! not blocks: an in-flight block's `S` is returned once and its later `O`/`F`
//! lines come on the following drains.

use std::collections::VecDeque;
use std::time::{SystemTime, UNIX_EPOCH};

/// How many blocks stay in the recorder when nobody drains it.
pub const MAX_BLOCKS: usize = 200;

/// The 16 lowercase hex digits.
const HEX: &[u8; 16] = b"0123456789abcdef";

/// Encode bytes as lowercase hex. No dependency: this is four lines of table
/// lookup, and a new crate for it would be heavier than the code it replaces.
fn hex_encode(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len() * 2);
    for &byte in bytes {
        out.push(HEX[(byte >> 4) as usize] as char);
        out.push(HEX[(byte & 0x0f) as usize] as char);
    }
    out
}

/// Decode lowercase or uppercase hex, or `None` when the field is malformed.
/// Both sides of the wire use this shape so a test can round-trip a record.
fn hex_decode(text: &str) -> Option<Vec<u8>> {
    if text.len() % 2 != 0 {
        return None;
    }
    let bytes = text.as_bytes();
    let mut out = Vec::with_capacity(bytes.len() / 2);
    let mut index = 0;
    while index < bytes.len() {
        let high = (bytes[index] as char).to_digit(16)?;
        let low = (bytes[index + 1] as char).to_digit(16)?;
        out.push(((high << 4) | low) as u8);
        index += 2;
    }
    Some(out)
}

fn system_now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|elapsed| elapsed.as_millis().min(i64::MAX as u128) as i64)
        .unwrap_or(0)
}

/// A mark and its byte span in the recorder's current buffer.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct PositionedMark {
    kind: Kind,
    start: usize,
    end: usize,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Kind {
    /// `A`: the prompt is showing.
    Prompt,
    /// `B`: the user is typing.
    Command,
    /// `C`: the command is running.
    Exec,
    /// `D`: the command finished. `None` means the shell sent no status.
    Finished(Option<i32>),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Stage {
    /// No block is being captured.
    Idle,
    /// Between `A` and `B`: capturing the prompt.
    Prompt,
    /// Between `B` and `C`: capturing the command.
    Typing,
    /// Between `C` and `D`: capturing output.
    Running,
}

/// One block's wire records, with the drain cursor for the in-flight one.
#[derive(Debug)]
struct BlockRecord {
    id: String,
    lines: Vec<String>,
    finished: bool,
    drained: usize,
}

/// The block recorder: sees PTY bytes, emits wire records.
pub struct BlockRecorder {
    /// The current chunk preceded by an unfinished mark carried from the last
    /// one. Offsets in [`PositionedMark`] point into this buffer and are only
    /// valid until the next [`Self::observe`].
    buffer: Vec<u8>,
    carry: Vec<u8>,
    stage: Stage,
    /// Prompt/command bytes, accumulated across chunks because a mark can split
    /// two reads and so can the text it brackets.
    capture: Vec<u8>,
    prompt: Vec<u8>,
    command: Vec<u8>,
    /// Output bytes, flushed as one `O` record per read that carried any.
    pending_output: Vec<u8>,
    blocks: VecDeque<BlockRecord>,
    current_id: Option<String>,
    next_id: u64,
    /// True while a TUI owns the alternate screen. A half-recorded block from
    /// vim's startup is worse than no block at all.
    suppressed: bool,
    /// The last status a `D` mark carried, or -1 when none has been seen.
    last_exit_status: i32,
    /// Injected by tests so timestamps are assertable.
    now: fn() -> i64,
}

impl Default for BlockRecorder {
    fn default() -> Self {
        Self::new()
    }
}

impl BlockRecorder {
    pub fn new() -> Self {
        Self::with_clock(system_now_ms)
    }

    /// A recorder with a deterministic clock, for tests.
    pub fn with_clock(now: fn() -> i64) -> Self {
        Self {
            buffer: Vec::new(),
            carry: Vec::new(),
            stage: Stage::Idle,
            capture: Vec::new(),
            prompt: Vec::new(),
            command: Vec::new(),
            pending_output: Vec::new(),
            blocks: VecDeque::new(),
            current_id: None,
            next_id: 0,
            suppressed: false,
            last_exit_status: -1,
            now,
        }
    }

    /// Observe one chunk of PTY bytes.
    ///
    /// `alt_screen` is the terminal's answer *after* the chunk was parsed, so a
    /// chunk that enters vim is suppressed in the same read that entered it.
    /// `cwd` is the shell's last reported directory, read from `shell::Scanner`
    /// on the same chunk.
    ///
    /// # UNTESTED — verify before use
    /// Event ordering across frames (an `S` drained separately from its `O`/`F`)
    /// is covered by unit tests, but not on a device against a live shell.
    pub fn observe(&mut self, bytes: &[u8], alt_screen: bool, cwd: Option<&str>) {
        let marks = self.scan(bytes);

        if alt_screen {
            if !self.suppressed {
                // UNTESTED — verify before use: abandoning an in-flight block
                // when a TUI enters the alternate screen.
                self.abandon_in_flight();
                self.suppressed = true;
            }
            return;
        }
        if self.suppressed {
            // The TUI has left. The shell is redrawing; the next `A` starts the
            // first block we record. Bytes before it belong to no command.
            self.suppressed = false;
            self.abandon_in_flight();
            return;
        }

        let mut cursor = 0usize;
        for mark in marks {
            // Bytes between marks belong to whatever segment is open.
            if mark.start > cursor && self.stage != Stage::Idle {
                let slice = &self.buffer[cursor..mark.start];
                if self.stage == Stage::Running {
                    self.pending_output.extend_from_slice(slice);
                } else {
                    self.capture.extend_from_slice(slice);
                }
            }

            match mark.kind {
                Kind::Prompt => {
                    // A prompt without a preceding D means the command never
                    // reported a finish. Drop its records; the repository will
                    // close its own running block when S arrives.
                    self.abandon_in_flight();
                    self.stage = Stage::Prompt;
                }
                Kind::Command => {
                    self.prompt = std::mem::take(&mut self.capture);
                    self.stage = Stage::Typing;
                }
                Kind::Exec => {
                    self.command = std::mem::take(&mut self.capture);
                    self.emit_started(cwd, (self.now)());
                    self.stage = Stage::Running;
                }
                Kind::Finished(status) => {
                    self.flush_output();
                    self.emit_finished(status, (self.now)());
                    self.stage = Stage::Idle;
                }
            }
            cursor = mark.end;
        }

        // Hold back the tail of an unfinished mark: it will be carried into the
        // next chunk, and emitting it now would duplicate those bytes there.
        let emit_end = self.buffer.len() - self.carry.len();
        if emit_end > cursor && self.stage != Stage::Idle {
            let slice = &self.buffer[cursor..emit_end];
            if self.stage == Stage::Running {
                self.pending_output.extend_from_slice(slice);
            } else {
                self.capture.extend_from_slice(slice);
            }
        }
        if self.stage == Stage::Running {
            self.flush_output();
        }
    }

    /// Take the records produced so far. Empty when nothing is new.
    pub fn drain(&mut self) -> String {
        let mut lines: Vec<String> = Vec::new();
        for block in self.blocks.iter_mut() {
            if block.drained < block.lines.len() {
                lines.extend_from_slice(&block.lines[block.drained..]);
                block.drained = block.lines.len();
            }
        }
        // Finished blocks will never be appended to again. An in-flight one
        // stays, so its later O/F lines can be drained on the next frame.
        while self.blocks.front().map_or(false, |block| block.finished) {
            self.blocks.pop_front();
        }
        lines.join("\n")
    }

    /// The last status a `D` mark carried, or -1 when the shell sent none.
    pub fn last_exit_status(&self) -> i32 {
        self.last_exit_status
    }

    /// Find OSC 133 marks in `bytes`, carrying an unfinished tail.
    ///
    /// This is deliberately a separate scanner from `shell::Scanner`: that one
    /// reports phase/directory/editor facts and must keep its existing contract,
    /// while this one needs byte offsets to slice the PTY stream. Keeping them
    /// apart means a change to block boundaries cannot shift a phase.
    fn scan(&mut self, bytes: &[u8]) -> Vec<PositionedMark> {
        self.buffer.clear();
        self.buffer.extend_from_slice(&self.carry);
        self.buffer.extend_from_slice(bytes);

        let mut marks = Vec::new();
        let mut index = 0usize;
        while index < self.buffer.len() {
            let Some(offset) = self.buffer[index..].iter().position(|&byte| byte == 0x1b) else {
                index = self.buffer.len();
                break;
            };
            index += offset;
            match self.try_mark(index, &mut marks) {
                Some(consumed) => index += consumed,
                None => break,
            }
        }

        self.carry.clear();
        if index < self.buffer.len() {
            self.carry.extend_from_slice(&self.buffer[index..]);
        }
        marks
    }

    /// Try one OSC sequence at `offset`; `None` means "incomplete, carry it".
    fn try_mark(&self, offset: usize, marks: &mut Vec<PositionedMark>) -> Option<usize> {
        if offset + 1 >= self.buffer.len()
            || self.buffer[offset] != 0x1b
            || self.buffer[offset + 1] != b']'
        {
            // Not an OSC: step over the escape and keep looking.
            return Some(1);
        }

        let mut end = offset + 2;
        let mut terminator = 0usize;
        while end < self.buffer.len() {
            if self.buffer[end] == 0x07 {
                terminator = 1;
                break;
            }
            if self.buffer[end] == 0x1b {
                if end + 1 >= self.buffer.len() {
                    return None; // may be the start of ESC \
                }
                if self.buffer[end + 1] == b'\\' {
                    terminator = 2;
                    break;
                }
            }
            end += 1;
        }
        if terminator == 0 {
            return None;
        }

        let payload = &self.buffer[offset + 2..end];
        let consumed = end + terminator - offset;
        let Some(rest) = payload.strip_prefix(b"133;") else {
            // An OSC we do not record (OSC 7, OSC 0, ...): skip it whole so its
            // bytes are neither carried nor mistaken for command output.
            return Some(consumed);
        };

        let kind = match rest.first() {
            Some(b'A') => Kind::Prompt,
            Some(b'B') => Kind::Command,
            Some(b'C') => Kind::Exec,
            Some(b'D') => {
                let status = rest
                    .strip_prefix(b"D;")
                    .and_then(|value| std::str::from_utf8(value).ok())
                    .and_then(|value| value.trim().parse::<i32>().ok());
                Kind::Finished(status)
            }
            _ => return Some(consumed),
        };
        marks.push(PositionedMark {
            kind,
            start: offset,
            end: end + terminator,
        });
        Some(consumed)
    }

    fn emit_started(&mut self, cwd: Option<&str>, started_ms: i64) {
        self.next_id = self.next_id.wrapping_add(1);
        let id = format!("{:x}", self.next_id);
        let line = format!(
            "S {} {} {} {} {}",
            id,
            hex_encode(&self.prompt),
            hex_encode(&self.command),
            hex_encode(cwd.unwrap_or("").as_bytes()),
            started_ms,
        );
        self.blocks.push_back(BlockRecord {
            id: id.clone(),
            lines: vec![line],
            finished: false,
            drained: 0,
        });
        while self.blocks.len() > MAX_BLOCKS {
            self.blocks.pop_front();
        }
        self.current_id = Some(id);
        self.prompt.clear();
        self.command.clear();
    }

    fn flush_output(&mut self) {
        if self.pending_output.is_empty() {
            return;
        }
        if let Some(id) = self.current_id.clone() {
            let line = format!("O {} {}", id, hex_encode(&self.pending_output));
            if let Some(block) = self.blocks.back_mut() {
                if block.id == id {
                    block.lines.push(line);
                }
            }
        }
        self.pending_output.clear();
    }

    fn emit_finished(&mut self, status: Option<i32>, completed_ms: i64) {
        let code = status.unwrap_or(-1);
        self.last_exit_status = code;
        if let Some(id) = self.current_id.take() {
            let line = format!("F {} {} {}", id, code, completed_ms);
            if let Some(block) = self.blocks.back_mut() {
                if block.id == id {
                    block.lines.push(line);
                    block.finished = true;
                }
            }
        }
    }

    fn abandon_in_flight(&mut self) {
        if self.blocks.back().map_or(false, |block| !block.finished) {
            self.blocks.pop_back();
        }
        self.current_id = None;
        self.stage = Stage::Idle;
        self.capture.clear();
        self.prompt.clear();
        self.command.clear();
        self.pending_output.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn clock() -> i64 {
        1_700_000_000_123
    }

    fn field<'a>(line: &'a str, index: usize) -> &'a str {
        line.split(' ').nth(index).unwrap_or("")
    }

    fn text(line: &str, index: usize) -> String {
        String::from_utf8(hex_decode(field(line, index)).expect("hex field")).expect("utf-8 field")
    }

    fn marks(kind: &str) -> Vec<u8> {
        format!("\x1b]133;{kind}\x07").into_bytes()
    }

    #[test]
    fn a_mark_split_across_reads_is_still_one_mark() {
        let mut recorder = BlockRecorder::with_clock(clock);
        // The A mark is split three ways, the way a slow pipe writes it.
        recorder.observe(b"\x1b]133;", false, None);
        recorder.observe(b"A", false, None);
        recorder.observe(b"\x07prompt$ ", false, None);
        recorder.observe(&marks("B"), false, None);
        recorder.observe(b"ls -la", false, None);
        recorder.observe(&marks("C"), false, Some("/home/me"));

        let wire = recorder.drain();
        let started = wire
            .lines()
            .find(|line| line.starts_with("S "))
            .expect("S record");
        assert_eq!(field(started, 0), "S");
        assert_eq!(field(started, 1), "1");
        assert_eq!(text(started, 2), "prompt$ ");
        assert_eq!(text(started, 3), "ls -la");
        assert_eq!(text(started, 4), "/home/me");
        assert_eq!(field(started, 5), "1700000000123");

        recorder.observe(b"total 0\n", false, None);
        recorder.observe(&format!("\x1b]133;D;0\x07").into_bytes(), false, None);
        let rest = recorder.drain();
        let output = rest
            .lines()
            .find(|line| line.starts_with("O "))
            .expect("O record");
        assert_eq!(text(output, 2), "total 0\n");
        assert!(rest.lines().any(|line| line == "F 1 0 1700000000123"));
        assert_eq!(recorder.last_exit_status(), 0);
    }

    #[test]
    fn the_exit_status_comes_from_the_d_mark() {
        let mut recorder = BlockRecorder::with_clock(clock);
        let mut command = Vec::new();
        command.extend(marks("A"));
        command.extend(b"$\x20");
        command.extend(marks("B"));
        command.extend(marks("C"));
        command.extend(b"x");
        command.extend(marks("D;42"));
        recorder.observe(&command, false, None);

        let wire = recorder.drain();
        assert!(wire.lines().any(|line| line == "F 1 42 1700000000123"));
        assert_eq!(recorder.last_exit_status(), 42);

        // A D with no status is recorded as unknown, not as success.
        let mut second = Vec::new();
        second.extend(marks("A"));
        second.extend(marks("B"));
        second.extend(marks("C"));
        second.extend(marks("D"));
        recorder.observe(&second, false, None);
        assert!(recorder
            .drain()
            .lines()
            .any(|line| line == "F 2 -1 1700000000123"));
        assert_eq!(recorder.last_exit_status(), -1);
    }

    #[test]
    fn nothing_is_recorded_while_the_alternate_screen_is_active() {
        let mut recorder = BlockRecorder::with_clock(clock);
        let mut command = Vec::new();
        command.extend(marks("A"));
        command.extend(b"$ ");
        command.extend(marks("B"));
        command.extend(marks("C"));
        recorder.observe(&command, false, None);
        assert!(recorder.drain().contains("S 1"));

        // vim takes the screen in this read: the output and the finish are both
        // suppressed, and the half-recorded block is dropped.
        let mut tui = Vec::new();
        tui.extend(b"drawing");
        tui.extend(marks("D;0"));
        recorder.observe(&tui, true, None);
        assert!(recorder.drain().is_empty());

        // The first read back on the primary screen is the shell's redraw.
        let mut redraw = Vec::new();
        redraw.extend(marks("A"));
        redraw.extend(b"$ ");
        recorder.observe(&redraw, false, None);
        assert!(recorder.drain().is_empty());

        let mut next = Vec::new();
        next.extend(marks("B"));
        next.extend(b"echo hi");
        next.extend(marks("C"));
        next.extend(b"hi\n");
        next.extend(marks("D;0"));
        recorder.observe(&next, false, None);
        let wire = recorder.drain();
        assert!(wire.contains("S 2"));
        assert!(wire.contains("O 2"));
        assert!(wire.contains("F 2 0"));
    }

    #[test]
    fn utf8_and_wide_text_survive_the_hex_round_trip() {
        let mut recorder = BlockRecorder::with_clock(clock);
        let mut command = Vec::new();
        command.extend(marks("A"));
        command.extend("❯ ~/Drosh ".as_bytes());
        command.extend(marks("B"));
        command.extend("echo 你好👋\t".as_bytes());
        command.extend(marks("C"));
        command.extend("你好, 世界 🌍\n".as_bytes());
        command.extend(marks("D;0"));
        recorder.observe(&command, false, Some("/home/你好"));

        let wire = recorder.drain();
        let started = wire.lines().find(|line| line.starts_with("S ")).unwrap();
        assert_eq!(text(started, 2), "❯ ~/Drosh ");
        assert_eq!(text(started, 3), "echo 你好👋\t");
        assert_eq!(text(started, 4), "/home/你好");
        let output = wire.lines().find(|line| line.starts_with("O ")).unwrap();
        assert_eq!(text(output, 2), "你好, 世界 🌍\n");
    }

    #[test]
    fn a_drain_only_hands_each_record_once() {
        let mut recorder = BlockRecorder::with_clock(clock);
        recorder.observe(&marks("A"), false, None);
        recorder.observe(&marks("B"), false, None);
        recorder.observe(&marks("C"), false, Some("/"));
        let first = recorder.drain();
        assert_eq!(first.lines().count(), 1);
        assert!(first.contains("S 1"));
        assert_eq!(recorder.drain(), "");

        recorder.observe(b"out", false, None);
        assert!(recorder.drain().contains("O 1"));
        recorder.observe(&marks("D;0"), false, None);
        let last = recorder.drain();
        assert!(last.contains("F 1 0"));
        assert_eq!(recorder.drain(), "");
    }

    #[test]
    fn the_recorder_keeps_only_the_last_200_blocks() {
        let mut recorder = BlockRecorder::with_clock(clock);
        for _ in 0..(MAX_BLOCKS + 5) {
            let mut command = Vec::new();
            command.extend(marks("A"));
            command.extend(marks("B"));
            command.extend(marks("C"));
            command.extend(marks("D;0"));
            recorder.observe(&command, false, None);
        }
        let wire = recorder.drain();
        assert_eq!(
            wire.lines().filter(|line| line.starts_with("S ")).count(),
            MAX_BLOCKS
        );
        assert!(wire.contains(&format!("S {}", format!("{:x}", MAX_BLOCKS + 5))));
    }
}
