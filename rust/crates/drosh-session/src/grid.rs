//! Read-only helpers over a live [`Term`] grid.
//!
//! Shared by the URL, search and tint capabilities so they all agree on what a
//! "visible line" is. The viewport is exactly `Term::grid().display_iter()`,
//! which yields only the on-screen cells in row order.

use alacritty_terminal::term::Term;

use crate::EventProxy;

/// Visible (viewport) rows as plain text, top row first.
///
/// Wide glyphs occupy two grid cells but one character, so a row's character
/// length can be shorter than its column count; callers that map a hit back to a
/// cell use the character index, which is what the renderer's text layout uses.
pub fn visible_lines(term: &Term<EventProxy>) -> Vec<String> {
    let mut lines: Vec<String> = Vec::new();
    let mut current: Option<i32> = None;
    let mut buf = String::new();

    for indexed in term.grid().display_iter() {
        let line = indexed.point.line.0;
        if current != Some(line) {
            if current.is_some() {
                lines.push(std::mem::take(&mut buf));
            }
            current = Some(line);
        }

        let ch = indexed.cell.c;
        if ch == '\0' {
            buf.push(' ');
        } else {
            buf.push(ch);
        }
    }
    if current.is_some() {
        lines.push(buf);
    }

    lines
}
