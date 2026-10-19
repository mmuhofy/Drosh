//! Scrollback search over the visible grid.
//!
//! alacritty's own search walks the whole grid including scrollback; for the
//! in-terminal "find" we only need the viewport and a plain substring match,
//! which avoids pulling in `RegexSearch` for a feature that is a highlight aid,
//! not a regex engine. (A regex variant can replace this later without changing
//! the FFI contract.)

use alacritty_terminal::term::Term;

use crate::session::grid::visible_lines;
use crate::session::EventProxy;

/// A match position on a visible row.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchHit {
    /// Visible row index (0 = top).
    pub row: usize,
    /// Character column where the match starts.
    pub col: usize,
    /// Character length of the match.
    pub len: usize,
}

/// Find every occurrence of `query` in the visible grid, up to `limit`.
pub fn search(term: &Term<EventProxy>, query: &str, limit: usize) -> Vec<SearchHit> {
    let mut hits = Vec::new();
    if query.is_empty() || limit == 0 {
        return hits;
    }

    let query_chars = query.chars().count();

    for (row, line) in visible_lines(term).iter().enumerate() {
        // `find` answers a byte offset, but `col` has to be a character column:
        // that is the unit the view highlights, and the unit `SearchHit`
        // documents. Two cursors keep them in step — `at` slices `line` (always
        // on a character boundary, so a multi-byte row cannot panic the scan),
        // `col` counts characters.
        let mut at = 0;
        let mut col = 0;
        while let Some(pos) = line[at..].find(query) {
            let start = at + pos;
            col += line[at..start].chars().count();
            hits.push(SearchHit { row, col, len: query_chars });
            if hits.len() >= limit {
                return hits;
            }
            at = start + query.len();
            col += query_chars;
        }
    }

    hits
}

#[cfg(test)]
mod tests {
    use super::*;
    fn feed(session: &crate::session::Session, lines: &[&str]) {
        let mut bytes = Vec::new();
        for line in lines {
            bytes.extend_from_slice(line.as_bytes());
            bytes.push(b'\r');
            bytes.push(b'\n');
        }
        session.feed(&bytes);
    }

    #[test]
    fn finds_all_occurrences() {
        let session = crate::session::test_session();
        feed(&session, &["the cat sat on the cat mat"]);
        let term = session.term.lock();
        let hits = search(&term, "cat", 10);
        assert_eq!(hits.len(), 2);
        assert_eq!(hits[0], SearchHit { row: 0, col: 4, len: 3 });
        // Second "cat" begins at index 19: "the cat sat on the cat" -> 0..18
        // is 19 chars ("the cat sat on the ") before the second 'c'.
        assert_eq!(hits[1].col, 19);
    }

    #[test]
    fn empty_query_is_safe() {
        let session = crate::session::test_session();
        feed(&session, &["anything"]);
        let term = session.term.lock();
        assert!(search(&term, "", 10).is_empty());
    }

    #[test]
    fn honours_limit() {
        let session = crate::session::test_session();
        feed(&session, &["a a a a a"]);
        let term = session.term.lock();
        assert_eq!(search(&term, "a", 2).len(), 2);
    }

    #[test]
    fn no_match_is_empty() {
        let session = crate::session::test_session();
        feed(&session, &["hello"]);
        let term = session.term.lock();
        assert!(search(&term, "world", 10).is_empty());
    }

    #[test]
    fn columns_are_characters_not_bytes() {
        // "ü" and "ş" are two bytes each, so reporting the byte offset would put
        // the highlight four columns right of the match by the second hit.
        let session = crate::session::test_session();
        feed(&session, &["üş cat üş cat"]);
        let term = session.term.lock();
        let hits = search(&term, "cat", 10);
        assert_eq!(hits.len(), 2);
        assert_eq!(hits[0], SearchHit { row: 0, col: 3, len: 3 });
        assert_eq!(hits[1], SearchHit { row: 0, col: 10, len: 3 });
    }

    #[test]
    fn multibyte_query_measures_characters() {
        let session = crate::session::test_session();
        feed(&session, &["üş üş"]);
        let term = session.term.lock();
        let hits = search(&term, "üş", 10);
        assert_eq!(hits.len(), 2);
        assert_eq!(hits[0], SearchHit { row: 0, col: 0, len: 2 });
        assert_eq!(hits[1], SearchHit { row: 0, col: 3, len: 2 });
    }
}
