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

    for (row, line) in visible_lines(term).iter().enumerate() {
        let mut start = 0;
        while let Some(pos) = line[start..].find(query) {
            let at = start + pos;
            hits.push(SearchHit { row, col: at, len: query.len() });
            if hits.len() >= limit {
                return hits;
            }
            start = at + query.len();
        }
    }

    hits
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::session::manager;

    fn bridged(
        lines: &[&str],
    ) -> (
        u64,
        alacritty_terminal::sync::FairMutexGuard<'static, Term<crate::bridge::events::EventProxy>>,
    ) {
        let id = manager::spawn_bridged(crate::session::GridSize::new(80, 24), 10, 20).unwrap();
        let session = manager::get(id).unwrap();
        let mut bytes = Vec::new();
        for line in lines {
            bytes.extend_from_slice(line.as_bytes());
            bytes.push(b'\r');
            bytes.push(b'\n');
        }
        session.feed(&bytes);
        (id, session.term.lock())
    }

    #[test]
    fn finds_all_occurrences() {
        let (_id, term) = bridged(&["the cat sat on the cat mat"]);
        let hits = search(&term, "cat", 10);
        assert_eq!(hits.len(), 2);
        assert_eq!(hits[0], SearchHit { row: 0, col: 4, len: 3 });
        assert_eq!(hits[1].col, 16);
    }

    #[test]
    fn empty_query_is_safe() {
        let (_id, term) = bridged(&["anything"]);
        assert!(search(&term, "", 10).is_empty());
    }

    #[test]
    fn honours_limit() {
        let (_id, term) = bridged(&["a a a a a"]);
        assert_eq!(search(&term, "a", 2).len(), 2);
    }

    #[test]
    fn no_match_is_empty() {
        let (_id, term) = bridged(&["hello"]);
        assert!(search(&term, "world", 10).is_empty());
    }
}
