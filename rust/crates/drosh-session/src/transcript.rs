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
//!
//! ## What a line of the log is
//!
//! A line is an **output** line: the text between two newlines. It is not a
//! grid row, and the difference is not cosmetic:
//!
//! - a line the terminal soft-wrapped covers several grid rows, and the log
//!   does not say where the wrap happened;
//! - a line a program redrew with a carriage return holds every redraw, while
//!   the grid holds only the last one;
//! - the grid applies the terminal's rules (tabs, wide glyphs, wrapping) and
//!   the log does not.
//!
//! So a match in the log is a match in the *output*. It cannot be turned into
//! a position on the screen without more than the log carries.
//!
//! ## Searching the log
//!
//! [`OutputLog::search`] is a case-insensitive substring search over the
//! **readable** lines — the escape sequences stripped — because a query that
//! matched a colour code would be a match nobody asked for. The coordinates it
//! reports are the log's own: the index of the line and the column range
//! within that line.

use std::collections::VecDeque;

/// How much output a session keeps by default.
pub const DEFAULT_MAX_LINES: usize = 4000;

/// A match in the output log.
///
/// The coordinates are the log's, not the grid's: [line] is the index of the
/// line in the log and [col_start] and [col_end] are character columns within
/// that line.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TranscriptHit {
    /// The index of the line in the log, oldest first.
    pub line: usize,
    /// The character column where the match starts.
    pub col_start: usize,
    /// The character column just past the match.
    pub col_end: usize,
}

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
            // `find` gives a byte index and `split_off` takes one, so the line
            // is cut where it says it is; the newline itself goes with it.
            let rest = self.pending.split_off(index + 1);
            let line = std::mem::replace(&mut self.pending, rest);
            self.push_line(line.trim_end_matches('\n').trim_end_matches('\r').to_owned());
        }
    }

    /// The last lines, oldest first.
    pub fn lines(&self) -> Vec<String> {
        self.lines.iter().cloned().collect()
    }

    /// The last lines, oldest first, with the escape sequences stripped.
    ///
    /// This is the text a person reads, and the text a search runs over: both
    /// go through the same helper, so the two can never drift apart.
    pub fn readable_lines(&self) -> Vec<String> {
        self.lines.iter().map(|line| readable_line(line.as_str())).collect()
    }

    /// The line count kept.
    pub fn len(&self) -> usize {
        self.lines.len()
    }

    /// Whether the log holds nothing.
    pub fn is_empty(&self) -> bool {
        self.lines.is_empty()
    }

    /// Find every occurrence of `query` in the log, oldest line first.
    ///
    /// The match is case-insensitive, and the occurrences in a line do not
    /// overlap: the scan continues past the match it just found, so the two
    /// halves of "aa" in "aaa" are one match and not two.
    ///
    /// The columns are **character** columns of the line, never byte offsets,
    /// so a multi-byte line (Turkish, box-drawing, emoji) cannot report a
    /// column in the middle of a character.
    ///
    /// The search is over the readable lines — the escape sequences stripped —
    /// because a query that matched a colour code would be a match nobody
    /// asked for.
    pub fn search(&self, query: &str, limit: usize) -> Vec<TranscriptHit> {
        let mut hits = Vec::new();
        if query.is_empty() || limit == 0 {
            return hits;
        }

        let needle = fold_case(query);
        let needle_len = needle.chars().count();
        if needle_len == 0 {
            return hits;
        }

        for (index, raw) in self.lines.iter().enumerate() {
            let line = readable_line(raw.as_str());
            if line.is_empty() {
                continue;
            }
            let haystack = fold_case(&line);

            // `find` answers a byte offset, but a column is a character
            // column: that is the unit the transcript is read in, and the unit
            // `TranscriptHit` documents. Two cursors keep them in step — `at`
            // slices `haystack` (always on a character boundary, so a
            // multi-byte line cannot panic the scan), `col` counts characters.
            let mut from = 0;
            while let Some(at) = haystack[from..].find(needle.as_str()) {
                let start = from + at;
                let col = haystack[..start].chars().count();
                let col_end = col + needle_len;
                hits.push(TranscriptHit { line: index, col_start: col, col_end });
                if hits.len() >= limit {
                    return hits;
                }
                // Past the match just found, so two overlapping matches are
                // one match.
                from = start + needle.len();
            }
        }

        hits
    }

    fn push_line(&mut self, line: String) {
        self.lines.push_back(line);
        while self.lines.len() > self.max_lines {
            self.lines.pop_front();
        }
    }
}

/// The text of a raw log line: the escape sequences stripped, the trailing
/// whitespace gone.
///
/// The transcript and the search both go through this, so a match is always
/// reported in the text the transcript shows.
fn readable_line(raw: &str) -> String {
    crate::text::strip_ansi(raw.as_bytes()).trim_end().to_owned()
}

/// Fold the case of `text`, one character in and one character out.
///
/// The fold is per character on purpose: a whole-string lowercase can change
/// the number of characters (`İ` becomes two), and a column counted in the
/// folded text would then not be a column of the line.
fn fold_case(text: &str) -> String {
    text.chars().map(|c| c.to_lowercase().next().unwrap_or(c)).collect()
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

    #[test]
    fn a_match_is_found_in_a_line() {
        let mut log = OutputLog::new(10);
        log.push(b"hello world\n");
        let hits = log.search("world", 10);
        assert_eq!(hits, vec![TranscriptHit { line: 0, col_start: 6, col_end: 11 }]);
    }

    #[test]
    fn the_match_is_case_insensitive() {
        let mut log = OutputLog::new(10);
        log.push(b"Hello World\n");
        let lower = log.search("hello", 10);
        assert_eq!(lower, vec![TranscriptHit { line: 0, col_start: 0, col_end: 5 }]);
        let upper = log.search("WORLD", 10);
        assert_eq!(upper, vec![TranscriptHit { line: 0, col_start: 6, col_end: 11 }]);
    }

    #[test]
    fn every_occurrence_in_a_line_is_reported() {
        let mut log = OutputLog::new(10);
        log.push(b"the cat sat on the cat mat\n");
        let hits = log.search("cat", 10);
        assert_eq!(hits.len(), 2);
        // The second "cat" begins at index 19: "the cat sat on the cat" -> 0..18
        // is 19 chars ("the cat sat on the ") before the second 'c'.
        assert_eq!(hits[0], TranscriptHit { line: 0, col_start: 4, col_end: 7 });
        assert_eq!(hits[1], TranscriptHit { line: 0, col_start: 19, col_end: 22 });
    }

    #[test]
    fn overlapping_matches_are_one_match() {
        let mut log = OutputLog::new(10);
        log.push(b"aaa\n");
        let hits = log.search("aa", 10);
        assert_eq!(hits, vec![TranscriptHit { line: 0, col_start: 0, col_end: 2 }]);
    }

    #[test]
    fn the_line_index_is_the_index_in_the_log() {
        let mut log = OutputLog::new(10);
        log.push(b"nothing here\nthe needle\n");
        let hits = log.search("needle", 10);
        assert_eq!(hits, vec![TranscriptHit { line: 1, col_start: 4, col_end: 10 }]);
    }

    #[test]
    fn the_escape_sequences_are_not_searchable() {
        let mut log = OutputLog::new(10);
        log.push(b"\x1b[31mred\x1b[0m\n");
        assert!(log.search("31m", 10).is_empty());
        let hits = log.search("red", 10);
        assert_eq!(hits, vec![TranscriptHit { line: 0, col_start: 0, col_end: 3 }]);
    }

    #[test]
    fn the_columns_are_characters_not_bytes() {
        // "ü" and "ş" are two bytes each, so a byte offset would put the
        // match two columns right of where it is.
        let mut log = OutputLog::new(10);
        log.push("üş cat üş cat\n".as_bytes());
        let hits = log.search("cat", 10);
        assert_eq!(hits.len(), 2);
        assert_eq!(hits[0], TranscriptHit { line: 0, col_start: 3, col_end: 6 });
        assert_eq!(hits[1], TranscriptHit { line: 0, col_start: 10, col_end: 13 });
    }

    #[test]
    fn an_empty_query_finds_nothing() {
        let mut log = OutputLog::new(10);
        log.push(b"hello\n");
        assert!(log.search("", 10).is_empty());
    }

    #[test]
    fn the_limit_is_honoured() {
        let mut log = OutputLog::new(10);
        log.push(b"a a a a a\n");
        assert_eq!(log.search("a", 2).len(), 2);
    }

    #[test]
    fn a_query_that_is_not_there_finds_nothing() {
        let mut log = OutputLog::new(10);
        log.push(b"hello\n");
        assert!(log.search("world", 10).is_empty());
    }

    #[test]
    fn a_carriage_return_redraw_is_one_line() {
        // The grid would show "50%" only; the log holds every redraw, because
        // the log is a log of the output and not of the screen.
        let mut log = OutputLog::new(10);
        log.push(b"10%\r50%\n");
        assert_eq!(log.readable_lines(), vec!["10%\r50%".to_owned()]);
        let hits = log.search("50%", 10);
        assert_eq!(hits, vec![TranscriptHit { line: 0, col_start: 4, col_end: 7 }]);
    }
}
