//! One frame, in one call.
//!
//! The Kotlin side asks for a frame and gets a flat, fixed-layout buffer back.
//! It never touches a terminal, a render state or a row iterator, and it never
//! asks a second question that could be answered from a different moment — the
//! header and the cells are one snapshot.
//!
//! ```text
//! header (48 bytes, little-endian)
//!   0  u32  magic          'DRSH'
//!   4  u16  version
//!   6  u16  header_bytes
//!   8  u32  frame_sequence
//!  12  u16  columns
//!  14  u16  rows           viewport rows, overscan excluded
//!  16  u16  row_count      rows actually written, overscan included
//!  18  i16  overscan_above
//!  20  i16  overscan_below
//!  22  u16  cursor_x
//!  24  u16  cursor_y       viewport-relative
//!  26  u16  cursor_style
//!  28  u16  cursor_flags   bit0 visible, bit1 blinking
//!  30  u16  dirty          bit0 full, bit1 partial, bit2 none
//!  32  u32  cell_count
//!  36  u32  cell_stride
//!  40  u32  cells_bytes
//!  44  u32  cursor_color  R,G,B,A little-endian
//!  46  i16  first_row_y   the first captured row's viewport row
//! ```
//!
//! Cells follow: `cell_count` records of `cell_stride` bytes, row-major.
//!
//! ```text
//!  0  u32  codepoint (base)
//!  4  u32  foreground RGBA
//!  8  u32  background RGBA
//! 12  u32  attributes
//! 16  u8   width     1 narrow, 2 wide, 0 spacer tail
//! 17  u8   flags     bit0 selected, bit1 has grapheme
//! 18  u16  reserved
//! ```
//!
//! The colours are *resolved*: the library has already applied the palette, the
//! inverse flag, bold-brightening and the selection. Kotlin draws a rectangle
//! with a colour and a glyph; it does not decide what a colour means.

use std::ffi::c_void;

use crate::ghostty;

macro_rules! render_get {
    ($state:expr, $field:expr, $out:expr) => {
        ghostty::ghostty_render_state_get(
            $state,
            $field,
            $out as *mut _ as *mut std::ffi::c_void,
        )
    };
}


pub const MAGIC: u32 = 0x4853_5244; // 'DRSH' little-endian
pub const VERSION: u16 = 1;

pub const HEADER_BYTES: usize = 48;
pub const CELL_BYTES: usize = 20;

pub const ATTR_BOLD: u32 = 1 << 0;
pub const ATTR_ITALIC: u32 = 1 << 1;
pub const ATTR_FAINT: u32 = 1 << 2;
pub const ATTR_BLINK: u32 = 1 << 3;
pub const ATTR_STRIKETHROUGH: u32 = 1 << 4;
pub const ATTR_OVERLINE: u32 = 1 << 5;
pub const ATTR_INVISIBLE: u32 = 1 << 6;
/// Reverse video, already applied to the colours the frame carries. The bit is
/// kept so a caller can tell a styled cell from a naturally-dark one.
pub const ATTR_INVERSE: u32 = 1 << 7;
pub const ATTR_UNDERLINE_SHIFT: u32 = 8;

pub const CELL_FLAG_SELECTED: u8 = 1 << 0;
pub const CELL_FLAG_GRAPHEME: u8 = 1 << 1;
/// The cell is part of a search match and is drawn highlighted.
pub const CELL_FLAG_HIGHLIGHT: u8 = 1 << 2;
/// The cell is part of the selection the user made.
pub const CELL_FLAG_APP_SELECTED: u8 = 1 << 3;

pub const DIRTY_NONE: u16 = 0;
pub const DIRTY_FULL: u16 = 1;
pub const DIRTY_PARTIAL: u16 = 2;

pub const CURSOR_VISIBLE: u16 = 1 << 0;
pub const CURSOR_BLINKING: u16 = 1 << 1;
/// The cursor is a thin rectangle (bar or underline), not a filled cell.
pub const CURSOR_IS_RECTANGLE: u16 = 1 << 2;

/// What went wrong while producing a frame.
#[derive(Debug)]
pub enum FrameError {
    /// The render state refused the update.
    UpdateFailed(i32),
    /// The grid has no size yet.
    EmptyGrid,
    /// The iterator could not be bound to the render state.
    IteratorFailed(i32),
}

/// Everything a session needs to produce frames.
///
/// The render state is expensive to create and is reused for the life of the
/// session; the buffer is grown only when the grid does.
pub struct FrameSource {
    terminal: ghostty::GhosttyTerminal,
    state: ghostty::GhosttyRenderState,
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
    image_cache:
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
    /// The grid's shape, as the last frame saw it.
    columns: u16,
    rows: u16,
    /// The overscan rows above the viewport, as the last frame saw them.
    overscan_above: u16,
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
            app_selection: None,
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

    /// Produce the next frame and return it.
    pub fn render(&mut self) -> Result<&[u8], FrameError> {
        unsafe {
            let result = ghostty::ghostty_render_state_update(self.state, self.terminal);
            if !ghostty::ok(result) {
                return Err(FrameError::UpdateFailed(result));
            }

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
            self.buffer.clear();
            self.buffer.resize(needed, 0);

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
            while index < cell_count {
                if !ghostty::ghostty_render_state_row_iterator_next(self.iterator) {
                    break;
                }
                if !ghostty::ok(ghostty::ghostty_render_state_row_get(
                    self.iterator,
                    ghostty::GHOSTTY_RENDER_STATE_ROW_DATA_CELLS,
                    &mut self.cells as *mut _ as *mut c_void,
                )) {
                    continue;
                }

                if !seen_first_row {
                    let mut y: i32 = 0;
                    let _ = ghostty::ghostty_render_state_row_get(
                        self.iterator,
                        ghostty::GHOSTTY_RENDER_STATE_ROW_DATA_VIEWPORT_Y,
                        &mut y as *mut i32 as *mut c_void,
                    );
                    first_row_y = y as i16;
                    seen_first_row = true;
                }

                for column in 0..cols {
                    let at = HEADER_BYTES + index as usize * CELL_BYTES;
                    self.write_cell(at, column);
                    index += 1;
                }
            }

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
        self.app_selection = range;
    }

    /// Highlight a range of cells, or none.
    ///
    /// The range is in viewport rows: the find bar scrolls the viewport to the
    /// match, and a range in the scrollback's coordinates would be a different
    /// place by the time it is drawn.
    pub fn set_highlight(&mut self, range: Option<(u16, u16, u16)>) {
        self.highlight = range;
    }

    /// The frame's images, as one buffer.
    ///
    /// A program that draws pictures — the kitty graphics protocol — puts them
    /// in the terminal, and the renderer has to draw them. The engine knows
    /// where each one sits (the cell, the offset inside it, the part of the
    /// image to show) and it knows the pixels; this collects both into one
    /// buffer the other side can read in a single crossing.
    ///
    /// The format is the app's: a count, then per image its id, its
    /// generation, where it sits and how much of it is shown, and its pixels.
    pub fn images(&mut self) -> Vec<u8> {
        let mut iterator = std::ptr::null_mut();
        if !ghostty::ok(unsafe {
            ghostty::ghostty_render_state_image_iterator_new(std::ptr::null(), &mut iterator)
        }) {
            return Vec::new();
        }

        // The kitty graphics handle: the pixels come from there.
        let mut graphics: ghostty::GhosttyKittyGraphics = std::ptr::null_mut();
        let _ = unsafe {
            ghostty::ghostty_terminal_get(
                self.terminal,
                ghostty::GHOSTTY_TERMINAL_DATA_KITTY_GRAPHICS,
                &mut graphics as *mut _ as *mut c_void,
            )
        };

        // The iterator is a factory; the render state *populates* it with the
        // placements of the last update. Without this call the iterator is
        // empty and no picture is ever drawn.
        unsafe {
            ghostty::ghostty_render_state_get(
                self.state,
                ghostty::GHOSTTY_RENDER_STATE_DATA_IMAGE_ITERATOR,
                &mut iterator as *mut _ as *mut c_void,
            );
        }

        let mut out: Vec<u8> = Vec::new();
        let mut count: u32 = 0;

        unsafe {
            while ghostty::ghostty_render_state_image_iterator_next(iterator) {
                let mut protocol: u32 = 0;
                let mut image_id: u32 = 0;
                let mut generation: u64 = 0;
                let mut layer: u32 = 0;
                let mut z: i32 = 0;
                let mut col: i32 = 0;
                let mut row: i32 = 0;
                let mut offset_x: u32 = 0;
                let mut offset_y: u32 = 0;
                let mut width: u32 = 0;
                let mut height: u32 = 0;
                let mut src_x: u32 = 0;
                let mut src_y: u32 = 0;
                let mut src_width: u32 = 0;
                let mut src_height: u32 = 0;

                let keys = [
                    ghostty::GHOSTTY_RENDER_STATE_IMAGE_DATA_PROTOCOL,
                    ghostty::GHOSTTY_RENDER_STATE_IMAGE_DATA_IMAGE_ID,
                    ghostty::GHOSTTY_RENDER_STATE_IMAGE_DATA_IMAGE_GENERATION,
                    ghostty::GHOSTTY_RENDER_STATE_IMAGE_DATA_LAYER,
                    ghostty::GHOSTTY_RENDER_STATE_IMAGE_DATA_Z,
                    ghostty::GHOSTTY_RENDER_STATE_IMAGE_DATA_VIEWPORT_COL,
                    ghostty::GHOSTTY_RENDER_STATE_IMAGE_DATA_VIEWPORT_ROW,
                    ghostty::GHOSTTY_RENDER_STATE_IMAGE_DATA_CELL_OFFSET_X,
                    ghostty::GHOSTTY_RENDER_STATE_IMAGE_DATA_CELL_OFFSET_Y,
                    ghostty::GHOSTTY_RENDER_STATE_IMAGE_DATA_PIXEL_WIDTH,
                    ghostty::GHOSTTY_RENDER_STATE_IMAGE_DATA_PIXEL_HEIGHT,
                    ghostty::GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_X,
                    ghostty::GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_Y,
                    ghostty::GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_WIDTH,
                    ghostty::GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_HEIGHT,
                ];
                let mut values: [*mut c_void; 15] = [
                    &mut protocol as *mut u32 as *mut c_void,
                    &mut image_id as *mut u32 as *mut c_void,
                    &mut generation as *mut u64 as *mut c_void,
                    &mut layer as *mut u32 as *mut c_void,
                    &mut z as *mut i32 as *mut c_void,
                    &mut col as *mut i32 as *mut c_void,
                    &mut row as *mut i32 as *mut c_void,
                    &mut offset_x as *mut u32 as *mut c_void,
                    &mut offset_y as *mut u32 as *mut c_void,
                    &mut width as *mut u32 as *mut c_void,
                    &mut height as *mut u32 as *mut c_void,
                    &mut src_x as *mut u32 as *mut c_void,
                    &mut src_y as *mut u32 as *mut c_void,
                    &mut src_width as *mut u32 as *mut c_void,
                    &mut src_height as *mut u32 as *mut c_void,
                ];
                let mut written: usize = 0;
                if !ghostty::ok(ghostty::ghostty_render_state_image_get_multi(
                    iterator,
                    15,
                    keys.as_ptr(),
                    values.as_mut_ptr(),
                    &mut written,
                )) {
                    continue;
                }

                // Only the kitty protocol: the others are not implemented here,
                // and an image the renderer cannot draw is better left out than
                // drawn wrong.
                if protocol != ghostty::GHOSTTY_IMAGE_PROTOCOL_KITTY as u32 {
                    continue;
                }

                // The pixels.
                let image = ghostty::ghostty_kitty_graphics_image(graphics, image_id);
                if graphics.is_null() || image.is_null() {
                    continue;
                }

                let mut data_ptr: *const u8 = std::ptr::null();
                let mut data_len: usize = 0;
                let mut format: u32 = 0;
                let mut compression: u32 = 0;
                if !ghostty::ok(ghostty::ghostty_kitty_graphics_image_get_multi(
                    image,
                    5,
                    [
                        ghostty::GHOSTTY_KITTY_IMAGE_DATA_DATA_PTR,
                        ghostty::GHOSTTY_KITTY_IMAGE_DATA_DATA_LEN,
                        ghostty::GHOSTTY_KITTY_IMAGE_DATA_FORMAT,
                        ghostty::GHOSTTY_KITTY_IMAGE_DATA_COMPRESSION,
                        ghostty::GHOSTTY_KITTY_IMAGE_DATA_GENERATION,
                    ]
                    .as_ptr(),
                    [
                        &mut data_ptr as *mut *const u8 as *mut c_void,
                        &mut data_len as *mut usize as *mut c_void,
                        &mut format as *mut u32 as *mut c_void,
                        &mut compression as *mut u32 as *mut c_void,
                        &mut generation as *mut u64 as *mut c_void,
                    ]
                    .as_mut_ptr(),
                    &mut written,
                )) {
                    continue;
                }

                // A compressed image would have to be inflated here, and the
                // renderer does not do that: the kitty protocol sends its
                // pictures uncompressed unless the program asks otherwise.
                if compression != ghostty::GHOSTTY_KITTY_IMAGE_COMPRESSION_NONE as u32
                    || data_ptr.is_null()
                    || data_len == 0
                {
                    continue;
                }

                // The pixels: read once per generation, and only carried
                // when they are new.
                let cached = self.image_cache.get(&(image_id, generation)).cloned();
                let (format, pixels) = match cached {
                    Some(entry) => (entry.0, Vec::new()),
                    None => {
                        let pixels = std::slice::from_raw_parts(data_ptr, data_len).to_vec();
                        self.image_cache
                            .insert((image_id, generation), (format, pixels.clone()));
                        (format, pixels)
                    }
                };
                let has_pixels = !pixels.is_empty();

                count += 1;
                push_u32(&mut out, image_id);
                push_u64(&mut out, generation);
                // The layer and the z: the pictures are drawn in Ghostty's
                // order — some under the cell backgrounds, some between the
                // backgrounds and the text, some over it.
                push_u32(&mut out, layer);
                push_i32(&mut out, z);
                push_i32(&mut out, col);
                push_i32(&mut out, row);
                push_u32(&mut out, offset_x);
                push_u32(&mut out, offset_y);
                push_u32(&mut out, width);
                push_u32(&mut out, height);
                push_u32(&mut out, src_x);
                push_u32(&mut out, src_y);
                push_u32(&mut out, src_width);
                push_u32(&mut out, src_height);
                push_u32(&mut out, format);
                push_u32(&mut out, data_len as u32);
                push_u8(&mut out, if has_pixels { 1 } else { 0 });
                if has_pixels {
                    out.extend_from_slice(&pixels);
                }
            }
            ghostty::ghostty_render_state_image_iterator_free(iterator);
        }

        // The count, at the front.
        let mut framed: Vec<u8> = Vec::with_capacity(out.len() + 4);
        push_u32(&mut framed, count);
        framed.extend_from_slice(&out);
        framed
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
        if selected {
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

        // The user's own selection: the cells the app asked to be marked.
        if let Some((row, from, length)) = self.app_selection {
            let cell_index = (at - HEADER_BYTES) / CELL_BYTES;
            let viewport_row = cell_index / self.columns.max(1) as usize;
            let cell_column = cell_index % self.columns.max(1) as usize;
            if viewport_row == row as usize
                && cell_column >= from as usize
                && cell_column < (from as usize) + length as usize
            {
                flags |= CELL_FLAG_APP_SELECTED;
            }
        }

        // The search match, if this cell is part of it.
        //
        // The range is in viewport rows and the cell's own row is its index in
        // the frame minus the overscan rows above the viewport — the same
        // space, so the two can be compared directly.
        if let Some((row, from, length)) = self.highlight {
            let cell_index = (at - HEADER_BYTES) / CELL_BYTES;
            let viewport_row = cell_index / self.columns.max(1) as usize;
            let end = from as u32 + length as u32;
            if viewport_row == row as usize
                && column as u32 >= from as u32
                && (column as u32) < end
            {
                flags |= CELL_FLAG_HIGHLIGHT;
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

/// A colour as the frame carries it: R in the low byte.
///
/// Little-endian that is the byte sequence R, G, B, A — which is what
/// `GL_UNSIGNED_BYTE` normalised hands the fragment shader as `vec4`. The
/// previous packing put R in the high byte, so the renderer read green as
/// red, blue as green, and the real alpha byte as a colour channel.
/// The engine's colour as the renderer's own.
///
/// The two are the same three bytes; the renderer's type is the one the
/// colour rules speak, so that the rules do not have to know about the
/// engine's ABI.
fn rgb(color: ghostty::GhosttyColorRgb) -> crate::renderer::cell::Rgb {
    crate::renderer::cell::Rgb::new(color.r, color.g, color.b)
}

fn rgba(color: ghostty::GhosttyColorRgb) -> u32 {
    u32::from(color.r) | (u32::from(color.g) << 8) | (u32::from(color.b) << 16) | (0xff << 24)
}

fn push_u8(out: &mut Vec<u8>, value: u8) {
    out.push(value);
}

fn push_u32(out: &mut Vec<u8>, value: u32) {
    out.extend_from_slice(&value.to_le_bytes());
}

fn push_u64(out: &mut Vec<u8>, value: u64) {
    out.extend_from_slice(&value.to_le_bytes());
}

fn push_i32(out: &mut Vec<u8>, value: i32) {
    out.extend_from_slice(&value.to_le_bytes());
}

fn put_u16(out: &mut [u8], at: usize, value: u16) {
    out[at..at + 2].copy_from_slice(&value.to_le_bytes());
}

fn put_i16(out: &mut [u8], at: usize, value: i16) {
    out[at..at + 2].copy_from_slice(&value.to_le_bytes());
}

fn put_u32(out: &mut [u8], at: usize, value: u32) {
    out[at..at + 4].copy_from_slice(&value.to_le_bytes());
}
