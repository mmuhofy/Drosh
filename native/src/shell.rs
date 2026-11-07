//! Shell integration: the marks a shell writes into its own output.
//!
//! `OSC 133` is a convention, not a sequence the terminal must obey. A shell
//! that opts in writes a marker at each point in its cycle:
//!
//! ```text
//! ESC ] 133 ; A ST     the prompt is showing
//! ESC ] 133 ; B ST     the user is typing
//! ESC ] 133 ; C ST     the command is running
//! ESC ] 133 ; D ; 0 ST the command finished with status 0
//! ```
//!
//! plus `OSC 7` for the working directory and `OSC 1339` for "open this file".
//!
//! ## Why this is here and not in the engine
//!
//! `libghostty-vt` parses the sequences but exposes no API for them: Ghostty
//! reads them in its own application layer, above the VT core. So does this —
//! the tap sits on the PTY read path, sees each chunk before the parser does,
//! and keeps the few facts the UI needs.
//!
//! ## The scanner is incremental
//!
//! A mark can be split across two reads — a shell writing `ESC ] 133 ;` and then
//! `A ST` is what a slow pipe does. The scanner keeps the unfinished tail and
//! retries it with the next chunk rather than dropping it.
//!
//! It never modifies the bytes: the same chunk goes to the parser unchanged.
//! The tap observes; it does not filter.

/// Where the shell is in its cycle.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Phase {
    /// No mark has been seen — the shell has not opted in.
    Unknown = 0,
    /// `OSC 133 ; A`: the prompt is on screen, waiting for a command.
    Prompt = 1,
    /// `OSC 133 ; B`: the user is typing the command.
    Typing = 2,
    /// `OSC 133 ; C`: the command is running.
    Running = 3,
    /// `OSC 133 ; D`: the command finished.
    Finished = 4,
}

/// The most marks one chunk can complete before the rest are dropped.
///
/// A chunk with more than this many marks in it is a shell in a loop, and the UI
/// cannot show them anyway.
const MARKS_PER_CHUNK: usize = 8;

/// A mark, as the scanner reports it.
///
/// No payload: a working directory is kept on the scanner and read with
/// [`Scanner::directory`]. A borrowed path here would make every mark borrow the
/// scanner, and a scanner that borrows itself cannot be written to while it is
/// being read.
#[derive(Debug, Clone, Copy)]
pub enum Mark {
    PromptStart,
    CommandStart,
    CommandExec,
    CommandFinished(Option<i32>),
    WorkingDirectory,
    EditorTarget,
}

/// What one chunk completed.
pub struct Marks {
    marks: [Option<Mark>; MARKS_PER_CHUNK],
    len: usize,
}

impl Marks {
    fn new() -> Self {
        Self {
            marks: [None; MARKS_PER_CHUNK],
            len: 0,
        }
    }

    fn push(&mut self, mark: Mark) {
        if self.len < MARKS_PER_CHUNK {
            self.marks[self.len] = Some(mark);
            self.len += 1;
        }
    }

    pub fn iter(&self) -> impl Iterator<Item = Mark> + '_ {
        self.marks[..self.len].iter().filter_map(|mark| *mark)
    }

    pub fn len(&self) -> usize {
        self.len
    }

    pub fn is_empty(&self) -> bool {
        self.len == 0
    }
}

/// The scanner: sees chunks, reports marks, never modifies bytes.
pub struct Scanner {
    phase: Phase,
    directory: String,
    editor: String,
    /// An unfinished mark carried from the previous chunk.
    carry: Vec<u8>,
}

impl Default for Scanner {
    fn default() -> Self {
        Self::new()
    }
}

impl Scanner {
    pub fn new() -> Self {
        Self {
            phase: Phase::Unknown,
            directory: String::new(),
            editor: String::new(),
            carry: Vec::new(),
        }
    }

    pub fn phase(&self) -> Phase {
        self.phase
    }

    pub fn directory(&self) -> Option<&str> {
        if self.directory.is_empty() {
            None
        } else {
            Some(&self.directory)
        }
    }

    pub fn editor(&self) -> Option<&str> {
        if self.editor.is_empty() {
            None
        } else {
            Some(&self.editor)
        }
    }

    /// Scan one chunk.
    pub fn scan(&mut self, chunk: &[u8]) -> Marks {
        // A carried tail and the new chunk are scanned as one buffer, so a mark
        // split across two reads is still found.
        let mut data = std::mem::take(&mut self.carry);
        data.extend_from_slice(chunk);

        let mut marks = Marks::new();
        let mut index = 0;

        while index < data.len() {
            match data[index..].iter().position(|&byte| byte == 0x1b) {
                Some(offset) => index += offset,
                None => {
                    index = data.len();
                    break;
                }
            }

            match self.try_mark(&data, index, &mut marks) {
                Some(consumed) => index += consumed,
                None => break,
            }
        }

        // Whatever was not consumed is an unfinished mark: keep the tail and
        // retry it with the next chunk.
        if index < data.len() {
            self.carry = data[index..].to_vec();
        }

        marks
    }

    /// Try to read one mark at `offset`. Returns the bytes consumed, or None
    /// when the mark is incomplete.
    fn try_mark(&mut self, data: &[u8], offset: usize, marks: &mut Marks) -> Option<usize> {
        // ESC ] ...
        if offset + 1 >= data.len() || data[offset] != 0x1b || data[offset + 1] != b']' {
            // Not a mark we know; step over the escape and keep scanning.
            return Some(1);
        }

        let mut end = offset + 2;
        let mut terminator = 0usize;
        while end < data.len() {
            if data[end] == 0x07 {
                terminator = 1;
                break;
            }
            if data[end] == 0x1b {
                if end + 1 >= data.len() {
                    return None; // may be the start of ESC \
                }
                if data[end + 1] == b'\\' {
                    terminator = 2;
                    break;
                }
            }
            end += 1;
        }
        if terminator == 0 {
            return None;
        }

        let payload = String::from_utf8_lossy(&data[offset + 2..end]).into_owned();
        self.dispatch(&payload, marks);
        Some(end + terminator - offset)
    }

    fn dispatch(&mut self, payload: &str, marks: &mut Marks) {
        if let Some(rest) = payload.strip_prefix("133;") {
            match rest.chars().next() {
                Some('A') => {
                    self.phase = Phase::Prompt;
                    marks.push(Mark::PromptStart);
                }
                Some('B') => {
                    self.phase = Phase::Typing;
                    marks.push(Mark::CommandStart);
                }
                Some('C') => {
                    self.phase = Phase::Running;
                    marks.push(Mark::CommandExec);
                }
                Some('D') => {
                    self.phase = Phase::Finished;
                    let status = rest
                        .strip_prefix("D;")
                        .and_then(|value| value.parse::<i32>().ok());
                    marks.push(Mark::CommandFinished(status));
                }
                _ => {}
            }
            return;
        }

        if let Some(value) = payload.strip_prefix("7;") {
            // Strip the `file://host` prefix: the UI shows a path, not a URL.
            let path = match value.find("://") {
                Some(scheme) => match value[scheme + 3..].find('/') {
                    Some(slash) => &value[scheme + 3 + slash..],
                    None => value,
                },
                None => value,
            };
            self.directory = path.to_string();
            marks.push(Mark::WorkingDirectory);
            return;
        }

        if let Some(target) = payload.strip_prefix("1339;") {
            self.editor = target.to_string();
            marks.push(Mark::EditorTarget);
        }
    }
}