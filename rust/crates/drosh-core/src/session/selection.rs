//! Selection text and boundaries, derived from the engine's own grid.
//!
//! Word and line boundaries come from `Term::semantic_search_*` /
//! `line_search_*` so they match exactly what the renderer highlights. The
//! selection itself lives on the `Term` (`term.selection`); setting it here lets
//! the Kotlin view read it back through `selection_to_string()` with zero extra
//! glue.

use alacritty_terminal::index::{Column, Line, Point, Side};
use alacritty_terminal::selection::{Selection, SelectionType};
use alacritty_terminal::term::Term;

use crate::session::EventProxy;

/// The currently selected text, if any selection is active.
pub fn selection_text(term: &Term<EventProxy>) -> Option<String> {
    term.selection_to_string()
}

/// Select the word under (row, col) and return the selected text.
///
/// `row`/`col` are 0-based grid coordinates. The selection is written to the
/// engine so the renderer can draw it on the next frame.
pub fn select_word(term: &mut Term<EventProxy>, row: usize, col: usize) -> Option<String> {
    let point = Point::new(Line::from(row as i32), Column::from(col));
    let start = term.semantic_search_left(point);
    let end = term.semantic_search_right(point);

    let mut selection = Selection::new(SelectionType::Semantic, start, Side::Left);
    selection.update(end, Side::Left);
    term.selection = Some(selection);

    term.selection_to_string()
}

/// Select the full line under `row` and return the selected text.
pub fn select_line(term: &mut Term<EventProxy>, row: usize, col: usize) -> Option<String> {
    let point = Point::new(Line::from(row as i32), Column::from(col));
    let start = term.line_search_left(point);
    let end = term.line_search_right(point);

    let mut selection = Selection::new(SelectionType::Lines, start, Side::Left);
    selection.update(end, Side::Left);
    term.selection = Some(selection);

    term.selection_to_string()
}

/// Clear any active selection.
pub fn clear(term: &mut Term<EventProxy>) {
    term.selection = None;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn word_selection_captures_the_token() {
        let session = crate::session::test_session();
        session.feed(b"hello world");
        let mut term = session.term.lock();
        let text = select_word(&mut term, 0, 1).expect("selection produced text");
        assert_eq!(text, "hello");
        assert!(selection_text(&term).is_some());
    }

    #[test]
    fn line_selection_captures_the_whole_line() {
        let session = crate::session::test_session();
        session.feed(b"hello world");
        let mut term = session.term.lock();
        let text = select_line(&mut term, 0, 3).expect("selection produced text");
        // alacritty's line selection includes the trailing line break, so a
        // pasted line selection is a complete line.
        assert_eq!(text, "hello world\n");
    }

    #[test]
    fn clear_removes_selection() {
        let session = crate::session::test_session();
        session.feed(b"hello world");
        let mut term = session.term.lock();
        select_word(&mut term, 0, 1);
        assert!(selection_text(&term).is_some());
        clear(&mut term);
        assert!(selection_text(&term).is_none());
    }
}
