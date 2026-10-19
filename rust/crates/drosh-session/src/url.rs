//! URL detection over the visible grid.
//!
//! alacritty has no URL API, so we walk the visible rows and look for the common
//! scheme prefixes. The result is a per-cell hit the Android view can resolve on
//! a tap — keeping the hit-test on the Kotlin side and the text scan here, where
//! the grid already lives (no transcript copy across JNI).
//!
//! `col` and `len` are **character** columns, never byte offsets: the scan steps
//! character by character, so a multi-byte row (Turkish, box-drawing, emoji)
//! cannot slice a character in half.
//!
//! LIMITATION: a character is assumed to occupy one cell column. A wide glyph
//! (CJK, emoji) is one character over two columns, so a URL drawn after one is
//! reported one column left of where it is. Tracked in docs/TODO.md (P1).

use alacritty_terminal::term::Term;

use crate::grid::visible_lines;
use crate::EventProxy;

/// A URL found on a visible row.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UrlHit {
    /// Visible row index (0 = top).
    pub row: usize,
    /// Character column where the URL starts.
    pub col: usize,
    /// Character length of the URL.
    pub len: usize,
    /// The matched text.
    pub text: String,
}

const URL_PREFIXES: &[&str] = &["http://", "https://", "ftp://", "file://"];

/// Characters that can terminate a URL inside terminal text.
const URL_BREAKERS: &str = " \t\r\n\"'<>`()[]{}\\";

/// Scan the visible grid for URLs, returning at most `limit` of them.
pub fn find_urls(term: &Term<EventProxy>, limit: usize) -> Vec<UrlHit> {
    let lines = visible_lines(term);
    let mut hits = Vec::new();

    for (row, line) in lines.iter().enumerate() {
        // Two cursors, because the units are not interchangeable: `at` is a byte
        // offset into `line`, `col` counts characters and is what a caller maps
        // back to a cell. `at` only ever moves by whole characters — slicing
        // `line` anywhere else panics, and a panic across JNI aborts the process
        // (the release profile is built with `panic = "abort"`).
        let mut at = 0;
        let mut col = 0;

        while at < line.len() {
            let rest = &line[at..];
            let prefix_len = URL_PREFIXES
                .iter()
                .find(|prefix| rest.starts_with(*prefix))
                .map(|prefix| prefix.len());

            match prefix_len {
                Some(start) => {
                    // Walk to the first breaker one character at a time, so a
                    // multi-byte character is never cut in half.
                    let mut end = at + start;
                    for (offset, character) in rest[start..].char_indices() {
                        if URL_BREAKERS.contains(character) {
                            break;
                        }
                        end = at + start + offset + character.len_utf8();
                    }

                    let text = line[at..end].to_owned();
                    let len = text.chars().count();
                    hits.push(UrlHit { row, col, len, text });
                    if hits.len() >= limit {
                        return hits;
                    }

                    at = end;
                    col += len;
                }
                None => {
                    // Step exactly one character.
                    if let Some(character) = rest.chars().next() {
                        at += character.len_utf8();
                        col += 1;
                    } else {
                        break;
                    }
                }
            }
        }
    }

    hits
}

/// The URL at a specific visible (row, col), if the cell is inside one.
pub fn url_at(term: &Term<EventProxy>, row: usize, col: usize) -> Option<String> {
    find_urls(term, usize::MAX)
        .into_iter()
        .find(|hit| hit.row == row && col >= hit.col && col < hit.col + hit.len)
        .map(|hit| hit.text)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn feed(session: &crate::Session, lines: &[&str]) {
        let mut bytes = Vec::new();
        for line in lines {
            bytes.extend_from_slice(line.as_bytes());
            bytes.push(b'\r');
            bytes.push(b'\n');
        }
        session.feed(&bytes);
    }

    #[test]
    fn finds_http_and_https() {
        let session = crate::test_session();
        feed(&session, &["visit https://example.com and http://a.b/c now"]);
        let term = session.term.lock();
        let hits = find_urls(&term, 10);
        assert_eq!(hits.len(), 2);
        assert_eq!(hits[0].text, "https://example.com");
        assert_eq!(hits[1].text, "http://a.b/c");
        assert_eq!(hits[0].row, 0);
        assert_eq!(hits[0].col, 6);
    }

    #[test]
    fn url_at_resolves_within_range() {
        let session = crate::test_session();
        feed(&session, &["see https://x.io here"]);
        let term = session.term.lock();
        assert_eq!(url_at(&term, 0, 8), Some("https://x.io".to_owned()));
        // Column 3 is the space before "https" (s,e,e,space); the URL begins at
        // column 4, so a tap there must not resolve.
        assert_eq!(url_at(&term, 0, 3), None);
    }

    #[test]
    fn respects_limit() {
        let session = crate::test_session();
        feed(&session, &["http://a http://b http://c"]);
        let term = session.term.lock();
        assert_eq!(find_urls(&term, 2).len(), 2);
    }

    #[test]
    fn survives_multibyte_rows() {
        // A byte-wise scan used to slice `"│ şu https://x.io"` inside `│` or `ş`
        // and panic; across JNI that is a process abort, so this test is the
        // difference between a link chip and a dead app.
        let session = crate::test_session();
        feed(&session, &["│ şu https://x.io bak │"]);
        let term = session.term.lock();

        let hits = find_urls(&term, 10);
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].text, "https://x.io");
        // "│ şu " is five characters, so the URL starts at character column 5.
        assert_eq!(hits[0].col, 5);
        assert_eq!(hits[0].len, "https://x.io".len());

        assert_eq!(url_at(&term, 0, 5), Some("https://x.io".to_owned()));
        assert_eq!(url_at(&term, 0, 4), None);
    }
}
