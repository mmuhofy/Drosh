//! A bounded log of what the session printed.
//!
//! The scrollback lives inside alacritty's grid, and the grid does not hand it
//! out — there is no public API for it. The bytes are a different matter: they
//! pass through the tap on their way to the parser, and that is where a copy of
//! them is kept.
//!
//! The copy is bounded, because a session that has run for a week has more
//! output than a sheet can hold, and the lines anyone wants are the ones at the
//! end.
//!
//! The bytes are kept raw. The escape sequences are stripped when the log is
//! read, not as it arrives: a sequence can straddle a chunk boundary, and
//! stripping half of one leaves the other half in the text.

use std::collections::VecDeque;

/// How much output a session keeps by default.
pub const DEFAULT_MAX_LINES: usize = 4000;

/// The last lines a session printed.
#[derive(Debug, Default)]
pub struct OutputLog {
    /// The complete lines, oldest first.
    lines: VecDeque<String>,
    /// The bytes of a line that has not ended yet.
    pending: String,
    /// How many lines are kept.
    max_lines: usize,
}

impl OutputLog {
    /// A log that keeps the last `max_lines` lines.
    pub fn new(max_lines: usize) -> Self {
        Self { lines: VecDeque::new(), pending: String::new(), max_lines }
    }

    /// Feed the bytes the session produced.
    pub fn push(&mut self, bytes: &[u8]) {
        if bytes.is_empty() {
            return;
        }
        self.pending.push_str(&String::from_utf8_lossy(bytes));

        while let Some(index) = self.pending.find('\n') {
            let line: String = self.pending.drain(..=index).collect();
            self.push_line(line.trim_end_matches('\r').to_owned());
        }
    }

    /// The last lines, oldest first.
    pub fn lines(&self) -> Vec<String> {
        self.lines.iter().cloned().collect()
    }

    /// The line count kept.
    pub fn len(&self) -> usize {
        self.lines.len()
    }

    /// Whether the log holds nothing.
    pub fn is_empty(&self) -> bool {
        self.lines.is_empty()
    }

    fn push_line(&mut self, line: String) {
        self.lines.push_back(line);
        while self.lines.len() > self.max_lines {
            self.lines.pop_front();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_line_is_kept_when_it_ends() {
        let mut log = OutputLog::new(10);
        log.push(b"hello\n");
        assert_eq!(log.lines(), vec!["hello".to_owned()]);
    }

    #[test]
    fn a_line_split_across_reads_is_kept_whole() {
        let mut log = OutputLog::new(10);
        log.push(b"hel");
        assert!(log.lines().is_empty());
        log.push(b"lo\n");
        assert_eq!(log.lines(), vec!["hello".to_owned()]);
    }

    #[test]
    fn the_carriage_return_of_a_crlf_is_dropped() {
        let mut log = OutputLog::new(10);
        log.push(b"hello\r\n");
        assert_eq!(log.lines(), vec!["hello".to_owned()]);
    }

    #[test]
    fn the_oldest_lines_are_dropped_first() {
        let mut log = OutputLog::new(2);
        log.push(b"one\ntwo\nthree\n");
        assert_eq!(log.lines(), vec!["two".to_owned(), "three".to_owned()]);
    }

    #[test]
    fn the_trailing_line_without_a_newline_is_not_kept_yet() {
        let mut log = OutputLog::new(10);
        log.push(b"done\nnot finished");
        assert_eq!(log.lines(), vec!["done".to_owned()]);
        assert_eq!(log.len(), 1);
    }
}
