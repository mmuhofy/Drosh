//! Term → flat cell buffer + damage list (decision D5).
//!
//! Kotlin owns the snapshot memory: it allocates one direct `ByteBuffer` for
//! `rows * columns` cell records (see [`crate::cell`]) and one
//! `IntArray` for the damage list, then calls
//! `Session::take_snapshot(buffer)` once per frame. This module only writes
//! into those buffers, so a frame costs one copy of the damaged cells and no
//! allocation on the Kotlin side.

use alacritty_terminal::event::EventListener;
use alacritty_terminal::grid::{Dimensions, Grid};
use alacritty_terminal::index::{Column, Line};
use alacritty_terminal::term::cell::Cell;
use alacritty_terminal::term::color::{Colors, COUNT as COLOR_COUNT};
use alacritty_terminal::term::{point_to_viewport, LineDamageBounds, Term, TermDamage, TermMode};
use alacritty_terminal::vte::ansi::{CursorShape, Rgb};

use crate::cell::{CellRecord, CELL_BYTES};

/// `damage_out[0]` when the whole viewport was copied into the buffer.
pub const DAMAGE_FULL: i32 = -1;

/// Ints per damaged line in `damage_out`: `row`, `left`, `right`.
pub const DAMAGE_STRIDE: usize = 3;

/// Ints `damage_out` must have for a terminal with `rows` screen lines.
pub const fn damage_out_len(rows: usize) -> usize {
    1 + DAMAGE_STRIDE * rows
}

/// Ints written by [`write_state`] into `state_out`.
pub const STATE_LEN: usize = 9;

/// Frozen indices of the `state_out` array.
pub mod state_index {
    pub const COLUMNS: usize = 0;
    pub const ROWS: usize = 1;
    pub const CURSOR_ROW: usize = 2;
    pub const CURSOR_COL: usize = 3;
    pub const CURSOR_SHAPE: usize = 4;
    pub const DISPLAY_OFFSET: usize = 5;
    pub const MODE_BITS: usize = 6;
    /// Fingerprint of the engine's dynamic colour table (see
    /// [`colors_fingerprint`]); Kotlin pulls the table when it changes.
    pub const COLORS: usize = 7;
    /// The sub-line part of the scroll position, in pixels. The renderer
    /// shifts the whole grid by this so a drag follows the finger between
    /// rows instead of stepping a line at a time.
    pub const SUB_LINE_PX: usize = 8;
}

/// Cursor shapes on the wire.
pub mod cursor_shape {
    pub const BLOCK: i32 = 0;
    pub const UNDERLINE: i32 = 1;
    pub const BEAM: i32 = 2;
    pub const HOLLOW_BLOCK: i32 = 3;
    pub const HIDDEN: i32 = 4;
}

/// Terminal modes on the wire.
pub mod mode {
    pub const SHOW_CURSOR: u32 = 1 << 0;
    pub const APP_CURSOR: u32 = 1 << 1;
    pub const APP_KEYPAD: u32 = 1 << 2;
    pub const MOUSE_REPORT_CLICK: u32 = 1 << 3;
    pub const MOUSE_MOTION: u32 = 1 << 4;
    pub const MOUSE_DRAG: u32 = 1 << 5;
    pub const SGR_MOUSE: u32 = 1 << 6;
    pub const BRACKETED_PASTE: u32 = 1 << 7;
    pub const FOCUS_IN_OUT: u32 = 1 << 8;
    pub const ALT_SCREEN: u32 = 1 << 9;
    pub const INSERT: u32 = 1 << 10;
    pub const LINE_WRAP: u32 = 1 << 11;
    pub const LINE_FEED_NEW_LINE: u32 = 1 << 12;
    pub const ORIGIN: u32 = 1 << 13;
    pub const UTF8_MOUSE: u32 = 1 << 14;
    pub const ALTERNATE_SCROLL: u32 = 1 << 15;
}

/// Engine mode → wire bit.
const MODE_TABLE: &[(TermMode, u32)] = &[
    (TermMode::SHOW_CURSOR, mode::SHOW_CURSOR),
    (TermMode::APP_CURSOR, mode::APP_CURSOR),
    (TermMode::APP_KEYPAD, mode::APP_KEYPAD),
    (TermMode::MOUSE_REPORT_CLICK, mode::MOUSE_REPORT_CLICK),
    (TermMode::MOUSE_MOTION, mode::MOUSE_MOTION),
    (TermMode::MOUSE_DRAG, mode::MOUSE_DRAG),
    (TermMode::SGR_MOUSE, mode::SGR_MOUSE),
    (TermMode::BRACKETED_PASTE, mode::BRACKETED_PASTE),
    (TermMode::FOCUS_IN_OUT, mode::FOCUS_IN_OUT),
    (TermMode::ALT_SCREEN, mode::ALT_SCREEN),
    (TermMode::INSERT, mode::INSERT),
    (TermMode::LINE_WRAP, mode::LINE_WRAP),
    (TermMode::LINE_FEED_NEW_LINE, mode::LINE_FEED_NEW_LINE),
    (TermMode::ORIGIN, mode::ORIGIN),
    (TermMode::UTF8_MOUSE, mode::UTF8_MOUSE),
    (TermMode::ALTERNATE_SCROLL, mode::ALTERNATE_SCROLL),
];

/// Everything that can go wrong while filling Kotlin's buffers.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SnapshotError {
    /// The cell buffer is smaller than `rows * columns * CELL_BYTES`.
    BufferTooSmall { needed: usize, got: usize },
    /// The damage array is smaller than `1 + 3 * rows`.
    DamageOutTooSmall { needed: usize, got: usize },
    /// The grid has no cells (cannot happen for a live `Term`).
    InvalidGrid,
}

impl std::fmt::Display for SnapshotError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::BufferTooSmall { needed, got } => {
                write!(formatter, "snapshot buffer too small: need {needed} bytes, got {got}")
            }
            Self::DamageOutTooSmall { needed, got } => {
                write!(formatter, "damage array too small: need {needed} ints, got {got}")
            }
            Self::InvalidGrid => write!(formatter, "terminal grid has no cells"),
        }
    }
}

impl std::error::Error for SnapshotError {}

/// Copy the damaged cells of `term` into `buffer`, describe them in
/// `damage_out`, and reset the engine's damage state.
///
/// `scratch` is a caller-owned buffer that avoids an allocation per frame; its
/// contents are meaningless to the caller.
///
/// `damage_out[0]` becomes [`DAMAGE_FULL`] (the whole viewport was copied) or
/// the number of damaged lines, each followed by `row`, `left`, `right`.
pub fn take_snapshot<T>(
    term: &mut Term<T>,
    buffer: &mut [u8],
    damage_out: &mut [i32],
    scratch: &mut Vec<LineDamageBounds>,
) -> Result<(), SnapshotError> {
    let columns = term.columns();
    let rows = term.screen_lines();

    if columns == 0 || rows == 0 {
        return Err(SnapshotError::InvalidGrid);
    }

    let needed = columns * rows * CELL_BYTES;
    if buffer.len() < needed {
        return Err(SnapshotError::BufferTooSmall { needed, got: buffer.len() });
    }

    let damage_len = damage_out.len();
    let damage_needed = damage_out_len(rows);
    if damage_len < damage_needed {
        return Err(SnapshotError::DamageOutTooSmall { needed: damage_needed, got: damage_len });
    }

    // Damage rows are viewport-relative, and `Term::damage` marks the whole
    // viewport whenever the display offset changed — which is exactly what a
    // scroll is. The earlier version read `offset > 0` and copied the entire
    // grid on every frame of a pan: sixty kilobytes per frame while a finger
    // moved, for a viewport that had moved one row.
    //
    // The engine already reports the scroll as full damage when it happens
    // once; the frames after it are not damaged at all. So the flow is
    // untouched and the per-frame cost of a pan is what it always should have
    // been: the one row that came into view.
    let offset = term.grid().display_offset();
    let mut full = false;
    // A frame in which nothing was damaged still has to say so: an empty list
    // means "the picture on screen is correct", which is what lets the
    // renderer skip the upload and the draw entirely.

    match term.damage() {
        TermDamage::Full => full = true,
        TermDamage::Partial(lines) => {
            scratch.clear();
            scratch.extend(lines);
        }
    }

    if full {
        for row in 0..rows {
            write_row(term.grid(), offset, row, columns, 0, columns - 1, buffer);
        }
        damage_out[0] = DAMAGE_FULL;
    } else {
        let mut count = 0usize;
        for bounds in scratch.iter() {
            let row = bounds.line;
            if row >= rows {
                continue;
            }

            let right = bounds.right.min(columns - 1);
            let left = bounds.left.min(right);
            write_row(term.grid(), offset, row, columns, left, right, buffer);

            let base = 1 + count * DAMAGE_STRIDE;
            damage_out[base] = row as i32;
            damage_out[base + 1] = left as i32;
            damage_out[base + 2] = right as i32;
            count += 1;
        }
        damage_out[0] = count as i32;
    }

    term.reset_damage();
    Ok(())
}

/// Fill `out` with the renderer state that is not part of the cell buffer.
pub fn write_state<T: EventListener>(term: &Term<T>, out: &mut [i32; STATE_LEN]) {
    let content = term.renderable_content();
    let offset = content.display_offset;
    let viewport = point_to_viewport(offset, content.cursor.point);

    out[state_index::COLUMNS] = term.columns() as i32;
    out[state_index::ROWS] = term.screen_lines() as i32;
    out[state_index::CURSOR_ROW] = viewport.map_or(-1, |point| point.line as i32);
    out[state_index::CURSOR_COL] = viewport.map_or(-1, |point| point.column.0 as i32);
    // alacritty's rule: the terminal's own `DECSCUSR` wins, and the
    // application's setting is the fallback (`Term::cursor_style` —
    // `self.cursor_style.unwrap_or(self.config.default_cursor_style)`).
    out[state_index::CURSOR_SHAPE] = cursor_shape_code(term.cursor_style().shape);
    out[state_index::DISPLAY_OFFSET] = offset as i32;
    out[state_index::MODE_BITS] = mode_bits(content.mode) as i32;
    out[state_index::COLORS] = colors_fingerprint(term.colors());
}

/// One entry of the engine's dynamic colour table, or `None` when the
/// application has not overridden it (the default palette applies).
pub fn color_at(colors: &Colors, index: usize) -> Option<u32> {
    let color = colors[index]?;
    Some(u32::from(color.r) << 16 | u32::from(color.g) << 8 | u32::from(color.b))
}

/// Fingerprint of the dynamic colour table (`OSC 4/10/11/12` overrides).
///
/// The table is 269 entries and changes only when an application sets a colour,
/// so a hash is cheaper than copying it on every frame: Kotlin compares this
/// value against the last one it saw and pulls the table only when it differs.
pub fn colors_fingerprint(colors: &Colors) -> i32 {
    // FNV-1a, 32 bit.
    let mut hash: u32 = 0x811c_9dc5;
    for index in 0..COLOR_COUNT {
        let entry = match colors[index] {
            // `+ 1` keeps "unset" distinct from black (`0`).
            Some(Rgb { r, g, b }) => 1 + (u32::from(r) << 16 | u32::from(g) << 8 | u32::from(b)),
            None => 0,
        };
        hash ^= entry;
        hash = hash.wrapping_mul(0x0100_0193);
    }
    hash as i32
}

/// Copy one line of the viewport, `left..=right`, into the cell buffer.
fn write_row(
    grid: &Grid<Cell>,
    offset: usize,
    row: usize,
    columns: usize,
    left: usize,
    right: usize,
    buffer: &mut [u8],
) {
    let grid_row = &grid[Line(row as i32 - offset as i32)];
    let base = row * columns * CELL_BYTES;

    for column in left..=right {
        let record = CellRecord::from_cell(&grid_row[Column(column)]);
        let at = base + column * CELL_BYTES;
        record.encode_into(&mut buffer[at..at + CELL_BYTES]);
    }
}

fn cursor_shape_code(shape: CursorShape) -> i32 {
    match shape {
        CursorShape::Block => cursor_shape::BLOCK,
        CursorShape::Underline => cursor_shape::UNDERLINE,
        CursorShape::Beam => cursor_shape::BEAM,
        CursorShape::HollowBlock => cursor_shape::HOLLOW_BLOCK,
        CursorShape::Hidden => cursor_shape::HIDDEN,
    }
}

fn mode_bits(engine: TermMode) -> u32 {
    let mut bits = 0u32;
    for (source, wire) in MODE_TABLE {
        if engine.contains(*source) {
            bits |= wire;
        }
    }
    bits
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mode_layout_is_frozen() {
        assert_eq!(mode_bits(TermMode::empty()), 0);
        assert_eq!(mode_bits(TermMode::BRACKETED_PASTE), mode::BRACKETED_PASTE);
        assert_eq!(mode_bits(TermMode::ALT_SCREEN), mode::ALT_SCREEN);

        let both = mode_bits(TermMode::ALT_SCREEN | TermMode::BRACKETED_PASTE);
        assert_eq!(both, mode::ALT_SCREEN | mode::BRACKETED_PASTE);
    }

    #[test]
    fn damage_out_len_matches_layout() {
        assert_eq!(damage_out_len(0), 1);
        assert_eq!(damage_out_len(24), 1 + 24 * DAMAGE_STRIDE);
    }
}
