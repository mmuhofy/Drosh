//! The frame's producer: the render state and the two-phase update.
//!
//! The wire format itself lives in `wire`, the images in `image` and the row
//! arithmetic in `row`; this file is what turns the engine's render state into
//! the bytes those modules describe.
//!
//! The state is expensive to create and is reused for the life of the session,
//! and the update is the engine's own two-phase one: the begin phase reads the
//! terminal, and the end phase — and the row iteration that follows it — work
//! on the render state's memory alone.

use std::ffi::c_void;

use crate::ghostty;

use super::wire::{
    put_i16, put_u16, put_u32, rgba, ATTR_BLINK, ATTR_BOLD, ATTR_FAINT, ATTR_INVERSE,
    ATTR_INVISIBLE, ATTR_ITALIC, ATTR_OVERLINE, ATTR_STRIKETHROUGH, ATTR_UNDERLINE_SHIFT,
    CELL_BYTES, CELL_FLAG_APP_SELECTED, CELL_FLAG_GRAPHEME, CELL_FLAG_HIGHLIGHT,
    CELL_FLAG_HIGHLIGHT_ALL, CELL_FLAG_SELECTED, CURSOR_BLINKING, CURSOR_IS_RECTANGLE,
    CURSOR_VISIBLE, DIRTY_FULL, DIRTY_NONE, DIRTY_PARTIAL, DIRTY_ROW_BYTES, HEADER_BYTES, MAGIC,
    VERSION,
};
use super::FrameError;

macro_rules! render_get {
    ($state:expr, $field:expr, $out:expr) => {
        ghostty::ghostty_render_state_get(
            $state,
            $field,
            $out as *mut _ as *mut std::ffi::c_void,
        )
    };
}

/// Everything a session needs to produce frames.
///
/// The render state is expensive to create and is reused for the life of the
/// session; the buffer is grown only when the grid does.
pub struct FrameSource {
    pub(super) terminal: ghostty::GhosttyTerminal,
    pub(super) state: ghostty::GhosttyRenderState,
    iterator: ghostty::GhosttyRenderStateRowIterator,
    cells: ghostty::GhosttyRenderStateRowCells,
    buffer: Vec<u8>,
    sequence: u32,
    /// The engine's effective default colours, refreshed per frame.
    ///
    /// A cell that carries no colour of its own is painted in these — which is
    /// what Ghostty itself does, and what an OSC 10/11 recolour changes.
    default_fg: ghostty::GhosttyColorRgb,
    default_bg: ghostty::GhosttyColorRgb,
    /// The pixels of the images, by image and generation.
    ///
    /// The pixels do not change unless the program sends new ones, and the
    /// generation says exactly that. So the pixels are read once and kept, and
    /// a frame that draws the same image again carries only where it is.
    pub(super) image_cache:
        std::collections::HashMap<(u32, u64), (u32, std::vec::Vec<u8>)>,

    /// The cells the user's selection covers, in the viewport's rows.
    ///
    /// The engine's own selection is not what the app draws: the app asks the
    /// engine for a *snapshot* of a selection — the word under a finger, the
    /// line, the range between two points — and the engine does not install a
    /// snapshot. So the app keeps the range and marks the cells itself.
    app_selection: Option<(u16, u16, u16)>,
    /// The range the search matched, in the viewport's rows and columns.
    ///
    /// One range: the find bar shows the match the user is on, and that is the
    /// one worth drawing.
    highlight: Option<(u16, u16, u16)>,
    /// The ranges the search's *other* matches cover, in viewport rows.
    ///
    /// The active match is [FrameSource::highlight]; these are the rest, and
    /// they are drawn in a weaker tint so the match the user is on stays the
    /// strongest thing on the screen. Rows outside the viewport mark nothing —
    /// the frame only carries the rows the viewport shows.
    highlights: Vec<(u16, u16, u16)>,
    /// The grid's shape, as the last frame saw it.
    pub(super) columns: u16,
    rows: u16,
    /// The overscan rows above the viewport, as the last frame saw them.
    pub(super) overscan_above: u16,
    /// The rows the app changed itself, as the last frame saw them.
    ///
    /// The engine knows which of *its* rows changed. The app has markings of
    /// its own — the search's highlight, the range the user's selection
    /// covers — and the engine does not know about them, so the rows they
    /// touch are dirty even when the engine says the screen did not change.
    ///
    /// The marks are consumed by the frame that carries them.
    app_dirty_rows: Vec<bool>,
}

impl FrameSource {
    pub fn new(terminal: ghostty::GhosttyTerminal) -> Result<Self, FrameError> {
        let mut state = std::ptr::null_mut();
        if !ghostty::ok(unsafe { ghostty::ghostty_render_state_new(std::ptr::null(), &mut state) }) {
            return Err(FrameError::UpdateFailed(-1));
        }

        let mut iterator = std::ptr::null_mut();
        if !ghostty::ok(unsafe {
            ghostty::ghostty_render_state_row_iterator_new(std::ptr::null(), &mut iterator)
        }) {
            unsafe { ghostty::ghostty_render_state_free(state) };
            return Err(FrameError::IteratorFailed(-1));
        }

        let mut cells = std::ptr::null_mut();
        if !ghostty::ok(unsafe {
            ghostty::ghostty_render_state_row_cells_new(std::ptr::null(), &mut cells)
        }) {
            unsafe {
                ghostty::ghostty_render_state_row_iterator_free(iterator);
                ghostty::ghostty_render_state_free(state);
            }
            return Err(FrameError::UpdateFailed(-1));
        }

        // One row of overscan on each side: the rows a smooth scroll reveals at
        // the edges. The library captures them for us, so the renderer never
        // asks the grid for a row it does not have.
        let overscan = ghostty::GhosttyRenderStateOverscan { above: 1, below: 1 };
        unsafe {
            ghostty::ghostty_render_state_set(
                state,
                ghostty::GHOSTTY_RENDER_STATE_OPTION_OVERSCAN,
                &overscan as *const _ as *const c_void,
            );
        }

        Ok(Self {
            terminal,
            state,
            iterator,
            cells,
            buffer: Vec::new(),
            sequence: 0,
            // White on black until the engine answers; both are replaced by the
            // effective values on the first frame.
            default_fg: ghostty::GhosttyColorRgb { r: 255, g: 255, b: 255 },
            default_bg: ghostty::GhosttyColorRgb { r: 0, g: 0, b: 0 },
            columns: 0,
            rows: 0,
            overscan_above: 0,
            highlight: None,
            highlights: Vec::new(),
            app_selection: None,
            app_dirty_rows: Vec::new(),
            image_cache: std::collections::HashMap::new(),
        })
    }

    /// The colours of a region of the grid, as `(foreground, background)` pairs.
    ///
    /// The values are the frame's own packed colours — red in the low byte —
    /// so a caller reads them with the same helpers the renderer uses.
    ///
    /// The region is in grid cells, and the rows are counted from the top of
    /// the *viewport*: row zero is the first row the terminal shows, and the
    /// overscan rows above it are not part of the grid the user sees.
    pub fn cell_colors(
        &self,
        first_row: u16,
        first_column: u16,
        rows: u16,
        columns: u16,
    ) -> Vec<u32> {
        let mut out = Vec::with_capacity(rows as usize * columns as usize * 2);
        let cols = self.columns;
        let overscan = self.overscan_above;
        for row in 0..rows {
            let grid_row = first_row as i32 + row as i32 + overscan as i32;
            if grid_row < 0 {
                // A row above the captured ones: the cells are not there.
                for _ in 0..columns {
                    out.push(0);
                    out.push(0);
                }
                continue;
            }
            for column in 0..columns {
                let grid_column = first_column as usize + column as usize;
                if grid_column >= cols as usize {
                    out.push(0);
                    out.push(0);
                    continue;
                }
                let index = grid_row as usize * cols as usize + grid_column;
                let at = HEADER_BYTES + index * CELL_BYTES;
                if at + CELL_BYTES > self.buffer.len() {
                    out.push(0);
                    out.push(0);
                    continue;
                }
                let fg = u32::from_le_bytes(
                    self.buffer[at + 4..at + 8].try_into().unwrap(),
                );
                let bg = u32::from_le_bytes(
                    self.buffer[at + 8..at + 12].try_into().unwrap(),
                );
                out.push(fg);
                out.push(bg);
            }
        }
        out
    }

    /// Begin the update: the only part of it that needs the terminal.
    ///
    /// The engine's own two-phase update. The begin phase reads the terminal
    /// and copies what it can into the render state's own memory; the end
    /// phase finishes the work without touching the terminal at all.
    ///
    /// So the terminal's lock is held for this call and for nothing else. That
    /// is the whole point: the terminal's lock is what the IO thread waits on
    /// when the program prints, and a frame that holds it for the whole of its
    /// production is a frame that makes the keyboard lag.
    ///
    /// Every begin must be completed by [FrameSource::end_update] before the
    /// render state is read.
    pub fn begin_update(&mut self) -> Result<(), FrameError> {
        unsafe {
            let result = ghostty::ghostty_render_state_begin_update(self.state, self.terminal);
            if !ghostty::ok(result) {
                return Err(FrameError::UpdateFailed(result));
            }
        }
        Ok(())
    }

    /// Complete the update and produce the next frame.
    ///
    /// No terminal access: the end phase works on the render state's own
    /// memory, and so does the row iteration that follows it. The terminal may
    /// be modified while this runs — that is what the two phases are for.
    pub fn end_update(&mut self) -> Result<&[u8], FrameError> {
        unsafe {
            // The end phase. It is a no-op without a begin, and it is safe
            // while the terminal is being written to.
            ghostty::ghostty_render_state_end_update(self.state);

            // The effective default colours, refreshed before every frame so an
            // OSC 10/11 recolour lands on the next one.
            self.refresh_default_colors();

            let mut cols: u16 = 0;
            let mut rows: u16 = 0;
            render_get!(self.state, ghostty::GHOSTTY_RENDER_STATE_DATA_COLS, &mut cols);
            render_get!(self.state, ghostty::GHOSTTY_RENDER_STATE_DATA_ROWS, &mut rows);
            if cols == 0 || rows == 0 {
                return Err(FrameError::EmptyGrid);
            }

            let mut overscan = ghostty::GhosttyRenderStateOverscan { above: 0, below: 0 };
            render_get!(self.state, ghostty::GHOSTTY_RENDER_STATE_DATA_OVERSCAN, &mut overscan);

            let row_count =
                u32::from(rows) + u32::from(overscan.above) + u32::from(overscan.below);
            let cell_count = row_count * u32::from(cols);

            self.columns = cols;
            self.rows = rows;
            self.overscan_above = overscan.above;

            let needed = HEADER_BYTES + cell_count as usize * CELL_BYTES;

            // The engine's own answer to "did anything change".
            //
            // A full dirty means every row changed: the screen was resized,
            // the program redrew all of it, or the viewport moved. A partial
            // one means some rows changed and the rest are as they were — and
            // the rest are *in the buffer already*, because the buffer is
            // reused from frame to frame.
            let mut dirty = ghostty::GHOSTTY_RENDER_STATE_DIRTY_FALSE;
            render_get!(self.state, ghostty::GHOSTTY_RENDER_STATE_DATA_DIRTY, &mut dirty);
            let mut full = dirty == ghostty::GHOSTTY_RENDER_STATE_DIRTY_FULL;

            // The buffer is reused, so a row that did not change keeps the
            // cells it had. A different size means the grid changed shape, and
            // then every cell is new.
            if self.buffer.len() != needed {
                self.buffer.clear();
                self.buffer.resize(needed, 0);
                full = true;
            }

            self.sequence = self.sequence.wrapping_add(1);
            self.write_header(cols, rows, row_count, overscan, cell_count);

            // The iterator visits overscan rows and viewport rows together, top
            // to bottom, so the buffer is already in draw order.
            if !ghostty::ok(ghostty::ghostty_render_state_get(
                self.state,
                ghostty::GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR,
                &mut self.iterator as *mut _ as *mut c_void,
            )) {
                return Err(FrameError::IteratorFailed(-1));
            }

            let mut index: u32 = 0;
            // The first captured row's own place in the viewport.
            //
            // The rows are not simply "the viewport's rows, in order": the
            // engine captures a row above and a row below when they exist, and
            // the row above has a *negative* viewport row. Deriving a row's
            // place from its index — which is what the frame used to do — is a
            // guess about how many rows were captured, and when the guess is
            // one row off, every cell on the screen moves a row: the text, the
            // blocks of a full-screen program, and the cursor's cell all
            // disagree with each other by exactly one line.
            //
            // The engine says where each row belongs, so the frame carries that
            // answer instead of a guess.
            let mut first_row_y: i16 = 0;
            let mut seen_first_row = false;

            // Which rows changed, as the frame carries it.
            //
            // The bitmap is the frame's own: one bit per captured row, in the
            // order the rows were captured. The renderer reads it to know what
            // it may keep and what it has to draw again.
            let mut row_flags = [0u8; DIRTY_ROW_BYTES];
            let mut row_index: usize = 0;

            while index < cell_count {
                if !ghostty::ghostty_render_state_row_iterator_next(self.iterator) {
                    break;
                }

                // The row's place in the viewport.
                //
                // The engine's own answer, for every row: the app's marks
                // are in the viewport's rows, so a row has to know which one it
                // is to be matched against them.
                let mut y: i32 = 0;
                let _ = ghostty::ghostty_render_state_row_get(
                    self.iterator,
                    ghostty::GHOSTTY_RENDER_STATE_ROW_DATA_VIEWPORT_Y,
                    &mut y as *mut i32 as *mut c_void,
                );
                if !seen_first_row {
                    first_row_y = y as i16;
                    seen_first_row = true;
                }

                // Did this row change?
                //
                // The engine knows: it tracks the dirty state of every row of
                // the screen, and a row that did not change has the cells it
                // had in the last frame — which are still in the buffer.
                //
                // The app's own markings count too: the search's highlight and
                // the user's selection are the app's, and the engine does not
                // know about them.
                let mut engine_dirty = false;
                let _ = ghostty::ghostty_render_state_row_get(
                    self.iterator,
                    ghostty::GHOSTTY_RENDER_STATE_ROW_DATA_DIRTY,
                    &mut engine_dirty as *mut bool as *mut c_void,
                );
                let app_dirty = self
                    .app_dirty_rows
                    .get(y.max(0) as usize)
                    .copied()
                    .unwrap_or(false);
                let changed = full || engine_dirty || app_dirty;
                if row_index < DIRTY_ROW_BYTES * 8 {
                    if changed {
                        row_flags[row_index / 8] |= 1u8 << (row_index % 8);
                    }
                }

                if !changed {
                    // The row did not change: its cells are the ones the last
                    // frame left in the buffer, and they are still right.
                    index += u32::from(cols);
                    row_index += 1;
                    continue;
                }

                // The row's cells, and the cell container that reads them.
                if !ghostty::ok(ghostty::ghostty_render_state_row_get(
                    self.iterator,
                    ghostty::GHOSTTY_RENDER_STATE_ROW_DATA_CELLS,
                    &mut self.cells as *mut _ as *mut c_void,
                )) {
                    index += u32::from(cols);
                    row_index += 1;
                    continue;
                }

                for column in 0..cols {
                    let at = HEADER_BYTES + index as usize * CELL_BYTES;
                    self.write_cell(at, column);
                    index += 1;
                }
                row_index += 1;
            }

            // The app's marks are consumed: the rows they named are in this
            // frame now.
            self.app_dirty_rows.clear();

            // The bitmap goes into the header, so the renderer can see which
            // rows it may keep.
            for (index, byte) in row_flags.iter().enumerate() {
                self.buffer[48 + index] = *byte;
            }

            // The render state's own dirty state is consumed.
            //
            // The frame carries the answer now, in its header. Leaving the
            // render state dirty would make every following frame a full
            // rebuild — the engine would keep saying "everything changed"
            // because nobody ever told it that the change was taken.
            ghostty::ghostty_render_state_clean(self.state);

            // The first row's place is written last: it is only known once the
            // rows have been visited.
            if seen_first_row {
                put_i16(&mut self.buffer, 46, first_row_y);
            }

            Ok(&self.buffer)
        }
    }

    /// The cells the user's selection covers, or none.
    pub fn set_app_selection(&mut self, range: Option<(u16, u16, u16)>) {
        if let Some((row, _, _)) = self.app_selection {
            self.mark_row_dirty(row);
        }
        if let Some((row, _, _)) = range {
            self.mark_row_dirty(row);
        }
        self.app_selection = range;
    }

    /// Mark a viewport row as changed by the app.
    ///
    /// The engine tracks the dirty state of its own rows; the app's markings
    /// — the search's highlight, the user's selection — are the app's, and
    /// the rows they cover have to be drawn again.
    fn mark_row_dirty(&mut self, row: u16) {
        let index = row as usize;
        if self.app_dirty_rows.len() <= index {
            self.app_dirty_rows.resize(index + 1, false);
        }
        self.app_dirty_rows[index] = true;
    }

    /// Highlight a range of cells, or none.
    ///
    /// The range is in viewport rows: the find bar scrolls the viewport to the
    /// match, and a range in the scrollback's coordinates would be a different
    /// place by the time it is drawn.
    ///
    /// The rows the range covers are marked dirty: the highlight is the app's
    /// own, and the engine does not know that those rows changed.
    pub fn set_highlight(&mut self, range: Option<(u16, u16, u16)>) {
        if let Some((row, _, _)) = self.highlight {
            self.mark_row_dirty(row);
        }
        if let Some((row, _, _)) = range {
            self.mark_row_dirty(row);
        }
        self.highlight = range;
    }

    /// Highlight every range the search's other matches cover.
    ///
    /// The ranges are in viewport rows, the same space [FrameSource::set_highlight]
    /// speaks: the find bar converts the scan's grid rows with the viewport's
    /// first row. An empty list clears the marking.
    pub fn set_highlights(&mut self, ranges: Vec<(u16, u16, u16)>) {
        // The rows are collected first: marking one needs the frame source,
        // and the list being marked is borrowed from it.
        let old: Vec<u16> = self.highlights.iter().map(|(row, _, _)| *row).collect();
        for row in old {
            self.mark_row_dirty(row);
        }
        for (row, _, _) in &ranges {
            self.mark_row_dirty(*row);
        }
        self.highlights = ranges;
    }

    /// Draw no all-matches highlight; the active one is untouched.
    pub fn clear_highlights(&mut self) {
        let old: Vec<u16> = self.highlights.iter().map(|(row, _, _)| *row).collect();
        for row in old {
            self.mark_row_dirty(row);
        }
        self.highlights.clear();
    }

    /// Read the engine's effective default foreground and background.
    ///
    /// "Effective" is the OSC override when there is one and the engine's own
    /// default otherwise — the same colours Ghostty paints behind and in front
    /// of unstyled cells.
    unsafe fn refresh_default_colors(&mut self) {
        let mut fg: ghostty::GhosttyColorRgb = self.default_fg;
        let mut bg: ghostty::GhosttyColorRgb = self.default_bg;
        let fg_ok = ghostty::ok(render_get!(
            self.state,
            ghostty::GHOSTTY_RENDER_STATE_DATA_COLOR_FOREGROUND,
            &mut fg
        ));
        let bg_ok = ghostty::ok(render_get!(
            self.state,
            ghostty::GHOSTTY_RENDER_STATE_DATA_COLOR_BACKGROUND,
            &mut bg
        ));
        if fg_ok {
            self.default_fg = fg;
        }
        if bg_ok {
            self.default_bg = bg;
        }
    }

    unsafe fn write_header(
        &mut self,
        cols: u16,
        rows: u16,
        row_count: u32,
        overscan: ghostty::GhosttyRenderStateOverscan,
        cell_count: u32,
    ) {
        // The cursor, exactly as the engine's own renderer asks for it.
        //
        // The render state answers the only question a renderer has: is the
        // cursor on the screen, and on which viewport cell. Ghostty's own
        // renderer reads these three values and nothing else, and it is right
        // to: the row is already in the viewport's own row space, so there is
        // no arithmetic here to get wrong. Deriving it from the terminal's
        // active row and the scrollbar put it a row off whenever the two row
        // spaces disagreed by one, which is exactly what a cursor one line high
        // looks like.
        let mut cursor_has_value = false;
        let mut cursor_x: u16 = 0;
        let mut cursor_y: u16 = 0;
        let mut wide_tail = false;
        render_get!(
            self.state,
            ghostty::GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_HAS_VALUE,
            &mut cursor_has_value
        );
        if cursor_has_value {
            render_get!(
                self.state,
                ghostty::GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_X,
                &mut cursor_x
            );
            render_get!(
                self.state,
                ghostty::GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_Y,
                &mut cursor_y
            );
            render_get!(
                self.state,
                ghostty::GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_WIDE_TAIL,
                &mut wide_tail
            );
            // A cursor on the tail of a wide character belongs on the
            // character: the block would cover the right half of a two-cell
            // glyph.
            if wide_tail && cursor_x > 0 {
                cursor_x -= 1;
            }
        }

        let mut cursor_visible = false;
        let mut cursor_style: u32 = 0;
        let mut cursor_blinking = false;
        render_get!(
            self.state,
            ghostty::GHOSTTY_RENDER_STATE_DATA_CURSOR_VISIBLE,
            &mut cursor_visible
        );
        render_get!(
            self.state,
            ghostty::GHOSTTY_RENDER_STATE_DATA_CURSOR_VISUAL_STYLE,
            &mut cursor_style
        );
        render_get!(
            self.state,
            ghostty::GHOSTTY_RENDER_STATE_DATA_CURSOR_BLINKING,
            &mut cursor_blinking
        );

        // A cursor with no position, or one outside the grid, is not drawn:
        // there is nothing to point at, and a block in the corner is worse than
        // no block.
        cursor_has_value =
            cursor_has_value && cursor_x < cols && cursor_y < rows;

        // The header's own fields, and then the cursor's.
        //
        // The body is written first and the header last, because the header is
        // what says how much body there is: a frame that carries its own length
        // is a frame the other side can check before it reads.
        let header = &mut self.buffer[0..HEADER_BYTES];
        put_u32(header, 0, MAGIC);
        put_u16(header, 4, VERSION);
        put_u16(header, 6, HEADER_BYTES as u16);
        put_u32(header, 8, self.sequence);
        put_u16(header, 12, cols);
        put_u16(header, 14, rows);
        put_u16(header, 16, row_count as u16);
        put_i16(header, 18, overscan.above as i16);
        put_i16(header, 20, overscan.below as i16);
        put_u32(header, 32, cell_count);
        put_u32(header, 36, CELL_BYTES as u32);
        put_u32(header, 40, cell_count * CELL_BYTES as u32);

        put_u16(header, 22, if cursor_has_value { cursor_x } else { 0 });
        put_u16(header, 24, if cursor_has_value { cursor_y } else { 0 });
        // The visual style, masked to a byte so that it reads the same whether
        // the engine's enum is one byte wide or four.
        put_u16(header, 26, (cursor_style & 0xff) as u16);

        let mut flags = 0u16;
        if cursor_visible && cursor_has_value {
            flags |= CURSOR_VISIBLE;
        }
        if cursor_blinking {
            flags |= CURSOR_BLINKING;
        }
        // A block cursor over a cell that has a glyph is drawn by the glyph pass
        // as an inverted cell; the renderer does not need a separate rectangle
        // for it. Bar and underline are thin rectangles and need one.
        if (cursor_style & 0xff) as u16
            != ghostty::GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK as u16
        {
            flags |= CURSOR_IS_RECTANGLE;
        }
        put_u16(header, 28, flags);

        // How much of the screen changed, as the engine saw it.
        let mut dirty = ghostty::GHOSTTY_RENDER_STATE_DIRTY_FALSE;
        render_get!(
            self.state,
            ghostty::GHOSTTY_RENDER_STATE_DATA_DIRTY,
            &mut dirty
        );

        let dirty_bits = match dirty {
            ghostty::GHOSTTY_RENDER_STATE_DIRTY_FULL => DIRTY_FULL,
            ghostty::GHOSTTY_RENDER_STATE_DIRTY_PARTIAL => DIRTY_PARTIAL,
            _ => DIRTY_NONE,
        };
        put_u16(header, 30, dirty_bits);

        // The cursor's colour: an explicit one when the terminal set it, the
        // foreground otherwise. Painting it black because "no explicit colour"
        // was read as "black" is what put a black square at the origin.
        let mut cursor_color: ghostty::GhosttyColorRgb = self.default_fg;
        let mut has_cursor_color = false;
        render_get!(
            self.state,
            ghostty::GHOSTTY_RENDER_STATE_DATA_COLOR_CURSOR_HAS_VALUE,
            &mut has_cursor_color
        );
        if has_cursor_color {
            let _ = render_get!(
                self.state,
                ghostty::GHOSTTY_RENDER_STATE_DATA_COLOR_CURSOR,
                &mut cursor_color
            );
        }
        put_u32(header, 44, rgba(cursor_color));
    }

    unsafe fn write_cell(&mut self, at: usize, column: u16) {
        // The cell is cleared first, and the selection is what positions the
        // cell iterator — a selection that fails leaves the iterator on the
        // *previous* cell, so a cell that was not written would keep the
        // previous one's text and colour.
        {
            let cell = &mut self.buffer[at..at + CELL_BYTES];
            cell.fill(0);
        }

        if !ghostty::ok(ghostty::ghostty_render_state_row_cells_select(
            self.cells, column,
        )) {
            return;
        }

        // The base codepoint: `GRAPHEMES_BUF` writes the cell's codepoints into
        // the buffer, base first, and the base is what a cell record draws.
        let mut codepoints = [0u32; 8];
        let _ = ghostty::ghostty_render_state_row_cells_get(
            self.cells,
            ghostty::GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF,
            codepoints.as_mut_ptr() as *mut c_void,
        );
        let mut grapheme_len: u32 = 0;
        let _ = ghostty::ghostty_render_state_row_cells_get(
            self.cells,
            ghostty::GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_LEN,
            &mut grapheme_len as *mut u32 as *mut c_void,
        );

        let mut style: ghostty::GhosttyStyle = std::mem::zeroed();
        style.size = std::mem::size_of::<ghostty::GhosttyStyle>();
        let _ = ghostty::ghostty_render_state_row_cells_get(
            self.cells,
            ghostty::GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE,
            &mut style as *mut _ as *mut c_void,
        );

        // A cell that carries no colour of its own reports GHOSTTY_INVALID_VALUE,
        // and the caller is expected to substitute the terminal's default — see
        // ROW_CELLS_DATA_BG_COLOR's note in the engine's header. Starting from
        // the defaults and keeping them on failure is what makes an unstyled
        // cell paint in the theme's colours instead of a guessed black.
        // The cell's own colours, or none when the style has none: the
        // difference between "the style asked for black" and "the style said
        // nothing" is the difference between a black cell and a default one,
        // and it is what the inverse rule turns into the text colour.
        let mut bg: ghostty::GhosttyColorRgb = ghostty::GhosttyColorRgb { r: 0, g: 0, b: 0 };
        let mut fg: ghostty::GhosttyColorRgb = ghostty::GhosttyColorRgb { r: 0, g: 0, b: 0 };
        let bg_ok = ghostty::ok(ghostty::ghostty_render_state_row_cells_get(
            self.cells,
            ghostty::GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR,
            &mut bg as *mut _ as *mut c_void,
        ));
        let fg_ok = ghostty::ok(ghostty::ghostty_render_state_row_cells_get(
            self.cells,
            ghostty::GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR,
            &mut fg as *mut _ as *mut c_void,
        ));
        // The cell's colours, from the engine's own queries.
        //
        // These are what Ghostty's renderer calls `bg_style` and `fg_style`:
        // the style's colour, resolved through the palette, or the content
        // tag's colour when the cell has one. So the reverse-video rule can
        // work on them directly.
        let bg_style = if bg_ok { Some(rgb(bg)) } else { None };
        let fg_style = if fg_ok { Some(rgb(fg)) } else { None };

        let mut selected = false;
        let _ = ghostty::ghostty_render_state_row_cells_get(
            self.cells,
            ghostty::GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_SELECTED,
            &mut selected as *mut bool as *mut c_void,
        );

        // Whether this cell is part of the app's own selection. Computed here
        // so the colour swap below can use it: the selection is the terminal's
        // two colours swapped, which is what makes it read as a highlight
        // instead of as a white block.
        let cell_index_for_app = (at - HEADER_BYTES) / CELL_BYTES;
        let viewport_row_for_app = self.viewport_row(cell_index_for_app);
        let cell_column_for_app = cell_index_for_app % self.columns.max(1) as usize;
        let app_selected = if let Some((row, from, length)) = self.app_selection {
            viewport_row_for_app == row as i64
                && cell_column_for_app >= from as usize
                && cell_column_for_app < (from as usize) + length as usize
        } else {
            false
        };

        // The colours the cell is drawn with, by Ghostty's own rule.
        //
        // Reverse video is a *rendering* instruction the engine hands to the
        // caller (see ROW_CELLS_DATA_SELECTED's note on rendering policy) —
        // the colours it resolves are the cell's own, not the swapped ones.
        // A status bar drawn with SGR 7 is exactly this: without the rule its
        // foreground lands on its background and the whole line disappears.
        //
        // The rule is not a swap of the two colours. It picks between them,
        // and which one it picks depends on whether the cell carries a
        // colour of its own — see `renderer::cell`.
        let mut colors = crate::renderer::cell::colors(
            &crate::renderer::cell::CellStyle {
                foreground: fg_style,
                background: bg_style,
                inverse: style.inverse,
                bold: style.bold,
                faint: style.faint,
            },
            codepoints[0],
            &crate::renderer::cell::Defaults {
                foreground: rgb(self.default_fg),
                background: rgb(self.default_bg),
            },
        );

        // A selected cell is drawn in the terminal's own two colours, swapped:
        // the selection is a region of the terminal's foreground on its
        // background, which is what a selection has to look like wherever the
        // text under it happens to be.
        if selected || app_selected {
            colors.foreground = crate::renderer::cell::Rgb::new(
                self.default_bg.r,
                self.default_bg.g,
                self.default_bg.b,
            );
            colors.background = crate::renderer::cell::Rgb::new(
                self.default_fg.r,
                self.default_fg.g,
                self.default_fg.b,
            );
        }
        let fg = crate::renderer::cell::Rgb {
            r: colors.foreground.r,
            g: colors.foreground.g,
            b: colors.foreground.b,
        };
        let bg = crate::renderer::cell::Rgb {
            r: colors.background.r,
            g: colors.background.g,
            b: colors.background.b,
        };

        let mut attributes = 0u32;
        if style.inverse {
            attributes |= ATTR_INVERSE;
        }
        if style.bold {
            attributes |= ATTR_BOLD;
        }
        if style.italic {
            attributes |= ATTR_ITALIC;
        }
        if style.faint {
            attributes |= ATTR_FAINT;
        }
        if style.blink {
            attributes |= ATTR_BLINK;
        }
        if style.strikethrough {
            attributes |= ATTR_STRIKETHROUGH;
        }
        if style.overline {
            attributes |= ATTR_OVERLINE;
        }
        if style.invisible {
            attributes |= ATTR_INVISIBLE;
        }
        attributes |= (style.underline as u32 & 0xf) << ATTR_UNDERLINE_SHIFT;

        let mut flags = 0u8;
        if selected {
            flags |= CELL_FLAG_SELECTED;
        }

        if app_selected {
            flags |= CELL_FLAG_APP_SELECTED;
        }

        // The search match, if this cell is part of it.
        //
        // The range is in viewport rows and the cell's own row is its index in
        // the frame minus the overscan rows above the viewport — the same
        // space, so the two can be compared directly. The cell's place is
        // computed once: the active range and the others are marked from the
        // same answer, so the two cannot disagree about where a cell is.
        let cell_index = (at - HEADER_BYTES) / CELL_BYTES;
        let viewport_row = self.viewport_row(cell_index);
        if let Some((row, from, length)) = self.highlight {
            let end = from as u32 + length as u32;
            if viewport_row == row as i64
                && column as u32 >= from as u32
                && (column as u32) < end
            {
                flags |= CELL_FLAG_HIGHLIGHT;
            }
        }

        // The search's other matches, if this cell is part of one.
        //
        // The active match is marked above and wins: a cell that is both is
        // the one the user is on, and it keeps the stronger flag. The list is
        // the find bar's scan result — at most a few hundred short ranges —
        // so a linear pass per cell is the cheap answer, and a range on a row
        // the viewport does not show simply never matches.
        for &(row, from, length) in &self.highlights {
            let end = from as u32 + length as u32;
            if viewport_row == row as i64
                && column as u32 >= from as u32
                && (column as u32) < end
            {
                flags |= CELL_FLAG_HIGHLIGHT_ALL;
                break;
            }
        }
        if grapheme_len > 1 {
            flags |= CELL_FLAG_GRAPHEME;
        }

        // The buffer borrow is taken last, once every read from `self` is done:
        // the cell's bytes and the source of its colours cannot be borrowed out
        // of the same struct at once.
        // The cell's width.
        //
        // A wide character — a CJK ideograph, an emoji — covers two cells, and
        // the cell after it is a spacer that carries no text of its own. The
        // frame said "one cell" for everything, so a wide glyph was drawn in
        // half the space it belongs to and the next glyph landed on top of it.
        //
        // The raw cell is the engine's packed value, and the width is one of
        // its fields: the raw bits are not a documented layout, but the query
        // is.
        let mut raw_cell: u64 = 0;
        let _ = ghostty::ghostty_render_state_row_cells_get(
            self.cells,
            ghostty::GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW,
            &mut raw_cell as *mut u64 as *mut c_void,
        );
        let mut wide: u32 = 0;
        let _ = ghostty::ghostty_cell_get(
            raw_cell,
            ghostty::GHOSTTY_CELL_DATA_WIDE,
            &mut wide as *mut u32 as *mut c_void,
        );
        let cell_width = match wide {
            ghostty::GHOSTTY_CELL_WIDE_WIDE => 2u8,
            ghostty::GHOSTTY_CELL_WIDE_SPACER_TAIL => 0u8,
            ghostty::GHOSTTY_CELL_WIDE_SPACER_HEAD => 0u8,
            _ => 1u8,
        };

        let cell = &mut self.buffer[at..at + CELL_BYTES];
        put_u32(cell, 0, codepoints[0]);
        put_u32(cell, 4, rgba(ghostty::GhosttyColorRgb {
            r: fg.r,
            g: fg.g,
            b: fg.b,
        }));
        put_u32(cell, 8, rgba(ghostty::GhosttyColorRgb {
            r: bg.r,
            g: bg.g,
            b: bg.b,
        }));
        put_u32(cell, 12, attributes);
        cell[16] = cell_width;
        cell[17] = flags;
    }
}

impl Drop for FrameSource {
    fn drop(&mut self) {
        unsafe {
            ghostty::ghostty_render_state_row_cells_free(self.cells);
            ghostty::ghostty_render_state_row_iterator_free(self.iterator);
            ghostty::ghostty_render_state_free(self.state);
        }
    }
}

/// The engine's colour as the renderer's own.
///
/// The two are the same three bytes; the renderer's type is the one the
/// colour rules speak, so that the rules do not have to know about the
/// engine's ABI.
fn rgb(color: ghostty::GhosttyColorRgb) -> crate::renderer::cell::Rgb {
    crate::renderer::cell::Rgb::new(color.r, color.g, color.b)
}