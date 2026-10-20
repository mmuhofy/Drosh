//! URL detection over the visible grid.
//!
//! alacritty has no URL API, so we walk the visible rows and look for URLs. The
//! result is a per-cell hit the Android view can resolve on a tap — keeping the
//! hit-test on the Kotlin side and the text scan here, where the grid already
//! lives (no transcript copy across JNI).
//!
//! `col` and `len` are **character** columns, never byte offsets: the scan steps
//! character by character, so a multi-byte row (Turkish, box-drawing, emoji)
//! cannot slice a character in half.
//!
//! ## The rules
//!
//! The detection is deliberately **lexical** rather than grammatical. A URL is
//! `scheme://` followed by every non-whitespace character, so this keeps working
//! for shapes a strict grammar would reject or truncate: IPv6 literals in
//! brackets, ports, IDN hosts, long query strings and percent-encoded paths.
//!
//! A grammatical pattern was tried first, ported from termux-app's
//! `TermuxUrlUtils.URL_MATCH_REGEX`, and rejected. Its path segment was a
//! required group rather than an optional one, so `https://example.com` did not
//! match at all and only URLs that happened to carry a trailing path were
//! detected. `www.` kept working because a separate pattern matched it, which
//! is why the gap read as "only bare hosts are found". Anything stricter trades
//! that class of silent miss for a different one.
//!
//! The cost of being lexical is that punctuation sitting directly after a URL
//! is swallowed by the greedy run, so [trimmed_span] pulls it back off and the
//! match is shortened to match.
//!
//! These are the rules the app's own detector uses
//! (`dev.drosh.domain.UrlDetector`), kept in step with it on purpose: a link
//! that stops working the moment the app moves over to the engine is not a
//! move, it is a regression.
//!
//! ## Logical lines
//!
//! The scan runs over **logical lines**, not over screen rows: a URL the shell
//! soft-wrapped across two rows is one URL, and a caller that opened half of
//! it would open the wrong link. A hit is therefore reported per row it covers,
//! and every row of one URL carries the same [UrlHit::text].
//!
//! LIMITATION: a character is assumed to occupy one cell column. A wide glyph
//! (CJK, emoji) is one character over two columns, so a URL drawn after one is
//! reported one column left of where it is. Tracked in docs/TODO.md (P1).

use alacritty_terminal::term::Term;

use crate::grid::{logical_lines, visible_rows, LogicalLine};
use crate::EventProxy;

/// A URL found on the grid.
///
/// A URL that covers more than one row is reported once per row: each hit
/// covers that row's part of the URL, and all of them carry the same
/// [UrlHit::text] — the whole link, which is what a tap has to open.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UrlHit {
    /// Visible row index (0 = top).
    pub row: usize,
    /// Character column where this row's part of the URL starts.
    pub col: usize,
    /// Character length of this row's part.
    pub len: usize,
    /// The whole URL, across the rows it covers.
    pub text: String,
}

/// The scheme grammar of RFC 3986: a letter, then letters, digits, `+`, `-`, `.`.
fn is_scheme_start(character: char) -> bool {
    character.is_ascii_alphabetic()
}

fn is_scheme_char(character: char) -> bool {
    character.is_ascii_alphanumeric() || character == '+' || character == '-' || character == '.'
}

/// Characters that follow prose far more often than they end a URL.
const TRAILING_PUNCTUATION: &str = ".,;:!?'\"";

/// The closers that are only kept when the URL opened one itself.
const BRACKET_PAIRS: [(char, char); 4] = [(')', '('), (']', '['), ('}', '{'), ('>', '<')];

/// Scan the visible grid for URLs, returning at most `limit` of them.
pub fn find_urls(term: &Term<EventProxy>, limit: usize) -> Vec<UrlHit> {
    let mut hits: Vec<UrlHit> = Vec::new();

    for line in logical_lines(&visible_rows(term)) {
        for span in scan_line(&line.text) {
            for hit in hits_for(&line, &span) {
                hits.push(hit);
                if hits.len() >= limit {
                    return hits;
                }
            }
        }
    }

    hits
}

/// The hits of one URL: one per row it covers.
///
/// A URL that the shell soft-wrapped is reported once per row, and every row
/// carries the whole URL as its text — a tap on any of them opens the whole
/// link, not the fragment the terminal happened to break it at.
fn hits_for(line: &LogicalLine, span: &Span) -> Vec<UrlHit> {
    let text: String = line.text.chars().take(span.end).skip(span.start).collect();
    let mut hits = Vec::new();

    for row in &line.rows {
        let row_start = row.offset;
        let row_end = row.offset + row.len;
        let start = span.start.max(row_start);
        let end = span.end.min(row_end);
        if end <= start {
            continue;
        }
        hits.push(UrlHit {
            row: row.row,
            col: start - row_start,
            len: end - start,
            text: text.clone(),
        });
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

/// A URL in a line: the character offsets it covers.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct Span {
    /// Character offset where the URL starts.
    start: usize,
    /// Character offset where the URL ends (exclusive).
    end: usize,
}

/// Every URL in one line.
fn scan_line(line: &str) -> Vec<Span> {
    let chars: Vec<char> = line.chars().collect();
    let mut spans: Vec<Span> = Vec::new();
    let mut at = 0usize;

    while at < chars.len() {
        if let Some(end) = scheme_run(&chars, at) {
            if let Some(span) = trimmed_span(&chars, at, end) {
                spans.push(span);
                at = span.end;
                continue;
            }
        }

        if let Some(end) = www_run(&chars, at) {
            if let Some(span) = trimmed_span(&chars, at, end) {
                spans.push(span);
                at = span.end;
                continue;
            }
        }

        at += 1;
    }

    spans
}

/// The end of a `scheme://` run starting at `at`, if there is one.
///
/// The run goes to the next whitespace: the greedy run is what makes the
/// detection lexical, and [trimmed_span] is what takes back what the prose
/// swallowed.
fn scheme_run(chars: &[char], at: usize) -> Option<usize> {
    if at >= chars.len() || !is_scheme_start(chars[at]) {
        return None;
    }

    let mut cursor = at + 1;
    while cursor < chars.len() && is_scheme_char(chars[cursor]) {
        cursor += 1;
    }

    // The separator has to follow the scheme, or this is just a word.
    if cursor + 2 >= chars.len() {
        return None;
    }
    if chars[cursor] != ':' || chars[cursor + 1] != '/' || chars[cursor + 2] != '/' {
        return None;
    }

    // The scheme has to be followed by something: `https://` on its own is a
    // scheme and a separator, not a link.
    let mut end = cursor + 3;
    if end >= chars.len() || chars[end].is_whitespace() {
        return None;
    }
    while end < chars.len() && !chars[end].is_whitespace() {
        end += 1;
    }
    Some(end)
}

/// The end of a bare `www.` host starting at `at`, if there is one.
///
/// The host may hold a wide set of characters, but it has to *end* on one that
/// can end a host: `www.foo.` is prose that happens to start with a host, not
/// a host.
fn www_run(chars: &[char], at: usize) -> Option<usize> {
    if at + 4 > chars.len() {
        return None;
    }
    if !is_www_prefix(&chars[at..at + 4]) {
        return None;
    }

    let mut end = at + 4;
    while end < chars.len() && is_www_char(chars[end]) {
        end += 1;
    }
    while end > at + 4 && !can_end_a_host(chars[end - 1]) {
        end -= 1;
    }
    if end <= at + 4 {
        return None;
    }
    Some(end)
}

/// `www.`, in any case: a host is written that way whatever the sentence around
/// it does.
fn is_www_prefix(chars: &[char]) -> bool {
    chars.len() == 4
        && chars[0].eq_ignore_ascii_case(&'w')
        && chars[1].eq_ignore_ascii_case(&'w')
        && chars[2].eq_ignore_ascii_case(&'w')
        && chars[3] == '.'
}

/// The characters a bare `www.` host may hold after its prefix.
fn is_www_char(character: char) -> bool {
    character.is_ascii_alphanumeric() || "-+&@#/%?=~_|!,:;.".contains(character)
}

/// The characters a host may end on: everything a host may hold, except the
/// punctuation that reads as prose.
fn can_end_a_host(character: char) -> bool {
    character.is_ascii_alphanumeric() || "-+&@#/%=~_|".contains(character)
}

/// The span of a run, with the trailing prose taken back off.
///
/// Three steps, repeated until the span stops changing, because taking one
/// character off can expose another:
///  1. a second `scheme://` inside the same run means the run held two links,
///     so cut back to where that scheme starts
///  2. sentence punctuation (`. , ; : ! ? ' "`)
///  3. a closing bracket, kept only when the URL opened one itself — so
///     `https://en.wikipedia.org/wiki/A_(b)` survives intact while
///     `(see https://example.com.)` trims cleanly
fn trimmed_span(chars: &[char], start: usize, end: usize) -> Option<Span> {
    let mut stop = end;

    // A second scheme inside the run: cut back to it.
    if let Some(second) = second_scheme(chars, start, end) {
        stop = second;
        while stop > start && is_scheme_char(chars[stop - 1]) {
            stop -= 1;
        }
    }

    loop {
        let before = stop;
        while stop > start && TRAILING_PUNCTUATION.contains(chars[stop - 1]) {
            stop -= 1;
        }
        stop = trim_unbalanced_closers(chars, start, stop);
        if stop == before {
            break;
        }
    }

    if stop <= start {
        return None;
    }
    Some(Span { start, end: stop })
}

/// The offset of a second `scheme://` inside the run, if there is one.
///
/// The run's own separator is not a second one, so the scan starts after it.
fn second_scheme(chars: &[char], start: usize, end: usize) -> Option<usize> {
    let mut cursor = start;
    // Skip past the run's own separator.
    while cursor + 2 < end {
        if chars[cursor] == ':' && chars[cursor + 1] == '/' && chars[cursor + 2] == '/' {
            cursor += 3;
            break;
        }
        cursor += 1;
    }

    while cursor + 2 < end {
        if chars[cursor] == ':' && chars[cursor + 1] == '/' && chars[cursor + 2] == '/' {
            return Some(cursor);
        }
        cursor += 1;
    }
    None
}

/// The end of the run with unbalanced closers taken off.
fn trim_unbalanced_closers(chars: &[char], start: usize, end: usize) -> usize {
    let mut stop = end;
    while stop > start {
        let closer = chars[stop - 1];
        let Some((_, opener)) = BRACKET_PAIRS.iter().find(|(close, _)| *close == closer) else {
            break;
        };
        if chars[start..stop].contains(opener) {
            break;
        }
        stop -= 1;
    }
    stop
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

    fn urls_of(lines: &[&str]) -> Vec<(usize, usize, String)> {
        let session = crate::test_session();
        feed(&session, lines);
        let term = session.term.lock();
        find_urls(&term, 10)
            .into_iter()
            .map(|hit| (hit.row, hit.col, hit.text))
            .collect()
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

    #[test]
    fn a_bare_host_without_a_scheme_is_found() {
        // The app's detector finds these, and an engine that did not would be a
        // link that stops working the moment the app moves over to it.
        assert_eq!(
            urls_of(&["see www.example.com here"]),
            vec![(0, 4, "www.example.com".to_owned())]
        );
    }

    #[test]
    fn trailing_punctuation_is_not_part_of_the_url() {
        assert_eq!(
            urls_of(&["go to https://example.com."]),
            vec![(0, 6, "https://example.com".to_owned())]
        );
    }

    #[test]
    fn a_balanced_bracket_stays() {
        assert_eq!(
            urls_of(&["https://en.wikipedia.org/wiki/A_(b)"]),
            vec![(0, 0, "https://en.wikipedia.org/wiki/A_(b)".to_owned())]
        );
    }

    #[test]
    fn an_unbalanced_closer_is_trimmed() {
        assert_eq!(
            urls_of(&["(see https://example.com.)"]),
            vec![(0, 5, "https://example.com".to_owned())]
        );
    }

    #[test]
    fn two_links_in_one_run_are_two_links() {
        let found = urls_of(&["https://a.com,https://b.com"]);
        assert_eq!(found.len(), 2);
        assert_eq!(found[0].2, "https://a.com");
        assert_eq!(found[1].2, "https://b.com");
    }

    #[test]
    fn a_scheme_word_without_a_separator_is_not_a_url() {
        assert!(urls_of(&["https and http are schemes"]).is_empty());
    }

    #[test]
    fn a_www_host_ends_on_a_host_character() {
        assert_eq!(
            urls_of(&["visit www.example.com."]),
            vec![(0, 6, "www.example.com".to_owned())]
        );
    }

    #[test]
    fn a_url_the_shell_wrapped_is_one_url() {
        // The shell soft-wrapped the link, so it is on two rows. The engine
        // reads the wrap flag and joins them, so the URL is whole — a caller
        // that opened half of it would open the wrong link.
        let url = format!("https://example.com/{}", "a".repeat(70));
        let session = crate::test_session();
        feed(&session, &[&format!("see {url} here")]);
        let term = session.term.lock();

        let hits = find_urls(&term, 10);
        assert_eq!(hits.len(), 2, "the URL covers two rows");

        // Both rows carry the whole URL.
        assert_eq!(hits[0].text, url);
        assert_eq!(hits[1].text, url);

        // The first row's part starts after "see "; the second row's part
        // starts at its first column.
        assert_eq!(hits[0].row, 0);
        assert_eq!(hits[0].col, 4);
        assert_eq!(hits[1].row, 1);
        assert_eq!(hits[1].col, 0);

        // The two parts are the whole URL between them.
        assert_eq!(hits[0].len + hits[1].len, url.chars().count());

        // A tap on either row opens the whole link.
        assert_eq!(url_at(&term, 0, 4), Some(url.clone()));
        assert_eq!(url_at(&term, 1, 0), Some(url));
    }
}
