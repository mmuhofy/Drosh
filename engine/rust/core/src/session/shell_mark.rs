//! Shell-integration marks, tapped from the PTY output stream.
//!
//! Drosh's shell integration scripts (see `:terminal`'s `DroshShellIntegration`)
//! wrap prompts and commands in OSC sequences:
//!
//! - `OSC 133 ; A` — the prompt starts,
//! - `OSC 133 ; B` — the prompt ends, command input begins,
//! - `OSC 133 ; C` — the command starts running,
//! - `OSC 133 ; D ; <status>` — the command finished,
//! - `OSC 7 ; file://…` — the working directory,
//! - `OSC 1339 ; <path>` — the file the shell asked the editor to open.
//!
//! The engine (`alacritty_terminal` 0.26) ignores these sequences; rather than
//! fork it, the PTY read path taps the bytes and turns the marks into events
//! the block engine can consume. Parsing is a small byte state machine that
//! survives arbitrary chunk boundaries: an OSC body may arrive split across
//! reads.

use crate::bridge::events::ShellMark;

/// Upper bound on a buffered OSC body. Marks are paths and statuses, not data
/// channels; anything longer than this is a runaway sequence, not a mark.
const MAX_BODY: usize = 4096;

#[derive(Debug)]
enum State {
    Ground,
    /// `ESC` seen; waiting to learn whether an OSC starts.
    Escape,
    /// Inside `ESC ]`; collecting the body until `BEL` or `ESC \`. `runaway`
    /// marks a body that grew past [`MAX_BODY`]: it is still consumed (so the
    /// stream stays in sync) but never yields a mark.
    Osc {
        body: Vec<u8>,
        escaped: bool,
        runaway: bool,
    },
}

/// Incremental scanner: feed it arbitrary chunks, collect the marks found.
#[derive(Debug)]
pub struct ShellMarkScanner {
    state: State,
}

impl ShellMarkScanner {
    pub fn new() -> Self {
        Self { state: State::Ground }
    }

    /// Scan one read chunk and return the marks completed inside it.
    pub fn scan(&mut self, bytes: &[u8]) -> Vec<ShellMark> {
        let mut marks = Vec::new();
        for &byte in bytes {
            match std::mem::replace(&mut self.state, State::Ground) {
                State::Ground => {
                    if byte == 0x1b {
                        self.state = State::Escape;
                    }
                }
                State::Escape => {
                    if byte == b']' {
                        self.state =
                            State::Osc { body: Vec::new(), escaped: false, runaway: false };
                    }
                    // Other introducers (CSI, DCS, …) belong to the engine's
                    // parser; the tap only watches for OSC.
                }
                State::Osc { body, escaped, runaway } => {
                    if escaped {
                        if byte == b'\\' && !runaway {
                            if let Some(mark) = parse_osc_body(&body) {
                                marks.push(mark);
                            }
                        }
                        // A bare `ESC` inside an OSC body is not a terminator;
                        // the sequence is malformed, so it is dropped.
                    } else {
                        match byte {
                            0x07 => {
                                if !runaway {
                                    if let Some(mark) = parse_osc_body(&body) {
                                        marks.push(mark);
                                    }
                                }
                            }
                            0x1b => {
                                self.state = State::Osc { body, escaped: true, runaway };
                                continue;
                            }
                            _ => {
                                let mut body = body;
                                let mut runaway = runaway;
                                if body.len() >= MAX_BODY {
                                    runaway = true;
                                } else {
                                    body.push(byte);
                                }
                                self.state = State::Osc { body, escaped: false, runaway };
                                continue;
                            }
                        }
                    }
                }
            }
        }
        marks
    }
}

impl Default for ShellMarkScanner {
    fn default() -> Self {
        Self::new()
    }
}

/// `133;A`, `133;D;0`, `7;file://…`, `1339;/path` — the OSC body without the
/// introducer and terminator.
fn parse_osc_body(body: &[u8]) -> Option<ShellMark> {
    let body = std::str::from_utf8(body).ok()?;
    let (number, rest) = body.split_once(';')?;

    match number {
        "133" => match rest.as_bytes().first()? {
            b'A' => Some(ShellMark::PromptStart),
            b'B' => Some(ShellMark::CommandStart),
            b'C' => Some(ShellMark::CommandExec),
            b'D' => {
                let status = rest
                    .split_once(';')
                    .map(|(_, code)| code.trim().parse::<i32>().ok())
                    .unwrap_or(None);
                Some(ShellMark::CommandFinished { status })
            }
            _ => None,
        },
        "7" => {
            let url = rest.trim();
            (!url.is_empty()).then(|| ShellMark::WorkingDirectory { url: url.to_owned() })
        }
        "1339" => {
            let path = rest.trim();
            (!path.is_empty()).then(|| ShellMark::EditorTarget { path: path.to_owned() })
        }
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const BEL: u8 = 0x07;

    fn marks(input: &[u8]) -> Vec<ShellMark> {
        ShellMarkScanner::new().scan(input)
    }

    #[test]
    fn flag_marks_arrive_complete() {
        assert_eq!(marks(b"\x1b]133;A\x07"), vec![ShellMark::PromptStart]);
        assert_eq!(marks(b"\x1b]133;B\x07"), vec![ShellMark::CommandStart]);
        assert_eq!(marks(b"\x1b]133;C\x07"), vec![ShellMark::CommandExec]);
    }

    #[test]
    fn finished_carries_the_status_when_present() {
        assert_eq!(
            marks(b"\x1b]133;D;0\x07"),
            vec![ShellMark::CommandFinished { status: Some(0) }]
        );
        assert_eq!(
            marks(b"\x1b]133;D;127\x07"),
            vec![ShellMark::CommandFinished { status: Some(127) }]
        );
        assert_eq!(marks(b"\x1b]133;D\x07"), vec![ShellMark::CommandFinished { status: None }]);
    }

    #[test]
    fn directory_and_editor_targets_carry_their_payload() {
        assert_eq!(
            marks(b"\x1b]7;file:///home/u\x07"),
            vec![ShellMark::WorkingDirectory { url: "file:///home/u".to_owned() }]
        );
        assert_eq!(
            marks(b"\x1b]1339;/etc/hosts\x07"),
            vec![ShellMark::EditorTarget { path: "/etc/hosts".to_owned() }]
        );
    }

    #[test]
    fn string_terminator_is_recognised() {
        assert_eq!(marks(b"\x1b]133;A\x1b\\"), vec![ShellMark::PromptStart]);
    }

    #[test]
    fn marks_survive_chunk_boundaries() {
        let mut scanner = ShellMarkScanner::new();
        assert!(scanner.scan(b"\x1b]13").is_empty());
        assert!(scanner.scan(b"3;D;").is_empty());
        assert_eq!(scanner.scan(b"2\x07"), vec![ShellMark::CommandFinished { status: Some(2) }]);
    }

    #[test]
    fn unknown_sequences_are_ignored() {
        // Unknown OSC numbers, CSI sequences and ordinary text produce nothing.
        assert!(marks(b"\x1b]0;title\x07").is_empty());
        assert!(marks(b"\x1b[2Jclear").is_empty());
        assert!(marks(b"plain text \x07 with a bell").is_empty());
    }

    #[test]
    fn a_bare_escape_aborts_the_sequence() {
        // `ESC x` inside an OSC body is malformed; the scanner drops it and
        // returns to ground, so the next real mark is still seen.
        let mut scanner = ShellMarkScanner::new();
        assert!(scanner.scan(b"\x1b]133;A\x1bx").is_empty());
        assert_eq!(scanner.scan(b"\x1b]133;C\x07"), vec![ShellMark::CommandExec]);
    }

    #[test]
    fn an_oversized_body_is_dropped() {
        let long = vec![b'a'; MAX_BODY + 10];
        let mut input = b"\x1b]1339;".to_vec();
        input.extend_from_slice(&long);
        input.push(BEL);
        assert!(marks(&input).is_empty(), "a runaway OSC is not a mark");
    }
}
