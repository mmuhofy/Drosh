//! The colours of the cells in the visible grid.
//!
//! The app draws chrome over the terminal and wants it in the terminal's own
//! colours: the background a program painted, the colour of the text on it.
//! With the Termux renderer the app could read the pixels back; with the GL
//! renderer there are no pixels to read, only the grid. So the engine reports
//! the colours of the cells themselves.
//!
//! The colours cross the boundary encoded the way the cell records already
//! encode them (`drosh_bridge::cell`), so the Kotlin side resolves them with
//! the palette table it already has: one encoding, one table, and no second
//! opinion about what a colour is.

use alacritty_terminal::grid::Dimensions;
use alacritty_terminal::index::{Column, Line};
use alacritty_terminal::term::Term;

use drosh_bridge::cell::encode_color;

use crate::EventProxy;

/// The colours of one cell of the visible grid.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CellColor {
    /// Visible row, counted from the top of the viewport.
    pub row: usize,
    /// Visible column, counted from the left.
    pub column: usize,
    /// The foreground colour, encoded as in `drosh_bridge::cell`.
    pub foreground: u32,
    /// The background colour, encoded as in `drosh_bridge::cell`.
    pub background: u32,
}

/// A rectangle of the visible grid.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CellRegion {
    /// The first row of the rectangle (0 = the top of the viewport).
    pub top: usize,
    /// The first column of the rectangle (0 = the leftmost column).
    pub left: usize,
    /// How many rows the rectangle is high.
    pub rows: usize,
    /// How many columns the rectangle is wide.
    pub columns: usize,
}

/// The colours of the cells in `region` of the visible grid.
///
/// The rectangle is clipped to the grid: a region that hangs over the edge
/// reports the cells that are there, and nothing more. The cells come back in
/// row order — top to bottom, and left to right within a row.
///
/// The rows are viewport rows: row 0 is the top of the screen, whatever the
/// viewport is scrolled to. That is the same numbering the cell records use
/// (`drosh_bridge::snapshot`), so the two answers can be put side by side.
pub fn cell_colors(term: &Term<EventProxy>, region: CellRegion) -> Vec<CellColor> {
    let grid = term.grid();
    let columns = term.columns();
    let screen_lines = term.screen_lines();
    // The viewport can be scrolled into the history, where the rows live
    // above the screen; the offset says how far up it is.
    let offset = grid.display_offset();

    let top = region.top.min(screen_lines);
    let left = region.left.min(columns);
    let rows = region.rows.min(screen_lines - top);
    let width = region.columns.min(columns - left);

    let mut cells = Vec::with_capacity(rows * width);
    for row in top..top + rows {
        let grid_row = &grid[Line(row as i32 - offset as i32)];
        for column in left..left + width {
            let cell = &grid_row[Column(column)];
            cells.push(CellColor {
                row,
                column,
                foreground: encode_color(cell.fg),
                background: encode_color(cell.bg),
            });
        }
    }

    cells
}

#[cfg(test)]
mod tests {
    use alacritty_terminal::vte::ansi::{Color, NamedColor, Rgb};
    use drosh_bridge::cell::CellRecord;

    use super::*;

    /// A scroll that reaches the top of the history, whatever it holds.
    const SCROLL_TO_THE_TOP: i32 = 1000;

    fn region(top: usize, left: usize, rows: usize, columns: usize) -> CellRegion {
        CellRegion { top, left, rows, columns }
    }

    #[test]
    fn reports_the_colours_of_the_cells() {
        let session = crate::test_session();
        // A palette foreground and a truecolour background.
        session.feed(b"\x1b[38;5;196m\x1b[48;2;0;0;255mX");
        let term = session.term.lock();

        let cells = cell_colors(&term, region(0, 0, 1, 2));
        assert_eq!(cells.len(), 2);
        assert_eq!(cells[0].row, 0);
        assert_eq!(cells[0].column, 0);
        assert_eq!(cells[0].foreground, encode_color(Color::Indexed(196)));
        assert_eq!(cells[0].background, encode_color(Color::Spec(Rgb { r: 0, g: 0, b: 255 })));

        // The cell after the text was never written, so it keeps the defaults.
        assert_eq!(cells[1].foreground, encode_color(Color::Named(NamedColor::Foreground)));
        assert_eq!(cells[1].background, encode_color(Color::Named(NamedColor::Background)));
    }

    #[test]
    fn a_region_is_clipped_to_the_grid() {
        let session = crate::test_session();
        let term = session.term.lock();

        // The test grid is 80 columns by 24 rows, so a region that starts at
        // (20, 70) and asks for 10 rows by 20 columns is cut down to the 4 rows
        // and 10 columns that are left.
        let cells = cell_colors(&term, region(20, 70, 10, 20));
        assert_eq!(cells.len(), 4 * 10);

        // The cells come in row-major order, ten to a row.
        assert_eq!(cells[0].row, 20);
        assert_eq!(cells[0].column, 70);
        assert_eq!(cells[9].row, 20);
        assert_eq!(cells[9].column, 79);

        // The last row of the region is the grid's last row.
        let last = cells.len() - 1;
        assert_eq!(cells[last].row, 23);
        assert_eq!(cells[last].column, 79);
    }

    #[test]
    fn a_region_outside_the_grid_is_empty() {
        let session = crate::test_session();
        let term = session.term.lock();

        assert!(cell_colors(&term, region(24, 0, 1, 1)).is_empty());
        assert!(cell_colors(&term, region(0, 80, 1, 1)).is_empty());
        assert!(cell_colors(&term, region(0, 0, 0, 0)).is_empty());
    }

    #[test]
    fn the_encoding_is_the_one_the_cell_records_use() {
        let session = crate::test_session();
        session.feed(b"\x1b[38;5;196m\x1b[48;2;0;0;255mX");
        let term = session.term.lock();

        let cell = &term.grid()[Line(0)][Column(0)];
        let record = CellRecord::from_cell(cell);

        let cells = cell_colors(&term, region(0, 0, 1, 1));
        assert_eq!(cells[0].foreground, record.fg);
        assert_eq!(cells[0].background, record.bg);
    }

    #[test]
    fn the_rows_are_viewport_rows() {
        let session = crate::test_session();
        // A red line, then enough blank lines to push it into the history.
        session.feed(b"\x1b[48;2;255;0;0mfirst\x1b[0m\r\n");
        for _ in 0..24 {
            session.feed(b"\r\n");
        }

        // Scrolled to the top of the history, the red line is the top row of
        // the viewport again: the rows are viewport rows, not grid rows.
        let _ = session.scroll(SCROLL_TO_THE_TOP);
        let term = session.term.lock();

        let cells = cell_colors(&term, region(0, 0, 1, 1));
        assert_eq!(cells.len(), 1);
        assert_eq!(cells[0].background, encode_color(Color::Spec(Rgb { r: 255, g: 0, b: 0 })));
    }
}
