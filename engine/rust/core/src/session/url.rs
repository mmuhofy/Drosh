//! URL detection over the visible grid.
//!
//! alacritty has no URL API, so we walk the visible rows and look for the common
//! scheme prefixes. The result is a per-cell hit the Android view can resolve on
//! a tap — keeping the hit-test on the Kotlin side and the text scan here, where
//! the grid already lives (no transcript copy across JNI).

use alacritty_terminal::term::Term;

use crate::session::grid::visible_lines;
use crate::session::EventProxy;

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
const URL_BREAKERS: &[u8] = b" \t\r\n\"'<>`()[]{}\\";

/// Scan the visible grid for URLs, returning at most `limit` of them.
pub fn find_urls(term: &Term<EventProxy>, limit: usize) -> Vec<UrlHit> {
    let lines = visible_lines(term);
    let mut hits = Vec::new();

    for (row, line) in lines.iter().enumerate() {
        let mut col = 0;
        let bytes = line.as_bytes();
        while col < bytes.len() {
            let rest = &line[col..];
            let prefix_len = URL_PREFIXES
                .iter()
                .find(|prefix| rest.starts_with(*prefix))
                .map(|prefix| prefix.len());

            match prefix_len {
                Some(start) => {
                    let mut end = col + start;
                    while end < line.len() && !URL_BREAKERS.contains(&line.as_bytes()[end]) {
                        end += 1;
                    }
                    let text = line[col..end].to_owned();
                    hits.push(UrlHit { row, col, len: end - col, text });
                    if hits.len() >= limit {
                        return hits;
                    }
                    col = end;
                }
                None => col += 1,
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
    use crate::session::manager;

    fn bridged(lines: &[&str]) -> u64 {
        let id = manager::spawn_bridged(crate::session::GridSize::new(80, 24), 10, 20).unwrap();
        let session = manager::get(id).unwrap();
        let mut bytes = Vec::new();
        for line in lines {
            bytes.extend_from_slice(line.as_bytes());
            bytes.push(b'\r');
            bytes.push(b'\n');
        }
        session.feed(&bytes);
        id
    }

    #[test]
    fn finds_http_and_https() {
        let id = bridged(&["visit https://example.com and http://a.b/c now"]);
        let session = manager::get(id).unwrap();
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
        let id = bridged(&["see https://x.io here"]);
        let session = manager::get(id).unwrap();
        let term = session.term.lock();
        assert_eq!(url_at(&term, 0, 8), Some("https://x.io".to_owned()));
        assert_eq!(url_at(&term, 0, 4), None);
    }

    #[test]
    fn respects_limit() {
        let id = bridged(&["http://a http://b http://c"]);
        let session = manager::get(id).unwrap();
        let term = session.term.lock();
        assert_eq!(find_urls(&term, 2).len(), 2);
    }
}
