//! Read-only helpers over a live [`Term`] grid.
//!
//! Shared by the URL, search and tint capabilities so they all agree on what a
//! "visible line" is. The viewport is exactly `Term::grid().display_iter()`,
//! which yields only the on-screen cells in row order.

use alacritty_terminal::term::cell::Flags;
use alacritty_terminal::term::Term;

use crate::EventProxy;

/// A visible row of the grid.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VisibleRow {
    /// The row's text, as the grid holds it — padded to the column width, the
    /// way a terminal pads every row.
    pub text: String,
    /// Whether the row's last cell carries the wrap flag: the logical line
    /// continues on the next row.
    pub wraps: bool,
}

/// Visible (viewport) rows as plain text, top row first.
///
/// Wide glyphs occupy two grid cells but one character, so a row's character
/// length can be shorter than its column count; callers that map a hit back to a
/// cell use the character index, which is what the renderer's text layout uses.
pub fn visible_lines(term: &Term<EventProxy>) -> Vec<String> {
    visible_rows(term).into_iter().map(|row| row.text).collect()
}

/// Visible (viewport) rows, with the wrap flag of each.
///
/// The flag is the engine's own: alacritty sets `WRAPLINE` on the last cell of
/// a row that continues on the next one. Reading it here is what lets a
/// capability that works on *logical* lines — a URL the shell soft-wrapped
/// across two rows is one URL — join the rows the way the shell meant them.
pub fn visible_rows(term: &Term<EventProxy>) -> Vec<VisibleRow> {
    let mut rows: Vec<VisibleRow> = Vec::new();
    let mut current: Option<i32> = None;
    let mut buf = String::new();
    // The wrap flag of the last cell seen. It is read after the loop, so the
    // value is never stale: the flag lives on the last cell of a row, and the
    // last cell is the last one the iterator yields for that row.
    let mut cell_wraps = false;

    for indexed in term.grid().display_iter() {
        let line = indexed.point.line.0;
        if current != Some(line) {
            if current.is_some() {
                rows.push(VisibleRow { text: std::mem::take(&mut buf), wraps: cell_wraps });
            }
            current = Some(line);
        }

        let ch = indexed.cell.c;
        if ch == '\0' {
            buf.push(' ');
        } else {
            buf.push(ch);
        }
        cell_wraps = indexed.cell.flags.contains(Flags::WRAPLINE);
    }
    if current.is_some() {
        rows.push(VisibleRow { text: buf, wraps: cell_wraps });
    }

    rows
}

/// The visible rows joined into the logical lines they are.
///
/// A row that wraps continues on the next one, so the two are one line. The
/// rows a logical line covers are listed in order, and the line's text is
/// their concatenation — no separator, because the shell wrote none.
pub fn logical_lines(rows: &[VisibleRow]) -> Vec<LogicalLine> {
    let mut lines: Vec<LogicalLine> = Vec::new();
    let mut index = 0usize;

    while index < rows.len() {
        let mut text = String::new();
        let mut parts: Vec<RowSpan> = Vec::new();

        loop {
            let row = &rows[index];
            // A row that wraps is full: its last cell is a character the shell
            // wrote, so nothing is trimmed off it. A row that does not wrap is
            // padded to the column width, and that padding is not part of what
            // was said — joining it in would insert spaces where the shell
            // wrote none.
            let row_text = if row.wraps { row.text.as_str() } else { row.text.trim_end() };
            let offset = text.chars().count();
            parts.push(RowSpan { row: index, offset, len: row_text.chars().count() });
            text.push_str(row_text);
            index += 1;
            if !row.wraps || index >= rows.len() {
                break;
            }
        }

        lines.push(LogicalLine { rows: parts, text });
    }

    lines
}

/// Where one row sits inside a logical line.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RowSpan {
    /// The row's index in the visible grid.
    pub row: usize,
    /// The character offset where the row's text starts in the line.
    pub offset: usize,
    /// The row's character length.
    pub len: usize,
}

/// One logical line: the text of several rows, and where each row starts in it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LogicalLine {
    /// The rows the line covers, in order.
    pub rows: Vec<RowSpan>,
    /// The line's text, the rows concatenated.
    pub text: String,
}

impl LogicalLine {
    /// The character offset of a row inside the line, if the line has it.
    pub fn offset_of(&self, row: usize) -> Option<usize> {
        self.rows.iter().find(|span| span.row == row).map(|span| span.offset)
    }
}
