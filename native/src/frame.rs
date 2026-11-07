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
//!  44  u32  reserved
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
pub const ATTR_UNDERLINE_SHIFT: u32 = 8;

pub const CELL_FLAG_SELECTED: u8 = 1 << 0;
pub const CELL_FLAG_GRAPHEME: u8 = 1 << 1;

pub const DIRTY_NONE: u16 = 0;
pub const DIRTY_FULL: u16 = 1;
pub const DIRTY_PARTIAL: u16 = 2;

pub const CURSOR_VISIBLE: u16 = 1 << 0;
pub const CURSOR_BLINKING: u16 = 1 << 1;

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
}

impl FrameSource {
    pub fn new(terminal: ghostty::GhosttyTerminal) -> Result<Self, FrameError> {
        let mut state = std::ptr::null_mut();
        if !ghostty::ok(ghostty::ghostty_render_state_new(std::ptr::null(), &mut state)) {
            return Err(FrameError::UpdateFailed(-1));
        }

        let mut iterator = std::ptr::null_mut();
        if !ghostty::ok(ghostty::ghostty_render_state_row_iterator_new(
            std::ptr::null(),
            &mut iterator,
        )) {
            unsafe { ghostty::ghostty_render_state_free(state) };
            return Err(FrameError::IteratorFailed(-1));
        }

        let mut cells = std::ptr::null_mut();
        if !ghostty::ok(ghostty::ghostty_render_state_row_cells_new(
            std::ptr::null(),
            &mut cells,
        )) {
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
        })
    }

    /// Produce the next frame and return it.
    pub fn render(&mut self) -> Result<&[u8], FrameError> {
        unsafe {
            let result = ghostty::ghostty_render_state_update(self.state, self.terminal);
            if !ghostty::ok(result) {
                return Err(FrameError::UpdateFailed(result));
            }

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

                for column in 0..cols {
                    let at = HEADER_BYTES + index as usize * CELL_BYTES;
                    self.write_cell(at, column);
                    index += 1;
                }
            }

            Ok(&self.buffer)
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

        let mut cursor_x: u16 = 0;
        let mut cursor_y: u16 = 0;
        let mut cursor_style: u16 = 0;
        let mut cursor_visible = false;
        let mut cursor_blinking = false;
        render_get!(self.state, ghostty::GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_X, &mut cursor_x);
        render_get!(self.state, ghostty::GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_Y, &mut cursor_y);
        render_get!(self.state, ghostty::GHOSTTY_RENDER_STATE_DATA_CURSOR_VISUAL_STYLE, &mut cursor_style);
        render_get!(self.state, ghostty::GHOSTTY_RENDER_STATE_DATA_CURSOR_VISIBLE, &mut cursor_visible);
        render_get!(self.state, ghostty::GHOSTTY_RENDER_STATE_DATA_CURSOR_BLINKING, &mut cursor_blinking);

        put_u16(header, 22, cursor_x);
        put_u16(header, 24, cursor_y);
        put_u16(header, 26, cursor_style);
        let mut flags = 0u16;
        if cursor_visible {
            flags |= CURSOR_VISIBLE;
        }
        if cursor_blinking {
            flags |= CURSOR_BLINKING;
        }
        put_u16(header, 28, flags);

        let mut dirty = ghostty::GHOSTTY_RENDER_STATE_DIRTY_FALSE;
        render_get!(self.state, ghostty::GHOSTTY_RENDER_STATE_DATA_DIRTY, &mut dirty);
        let dirty_bits = match dirty {
            ghostty::GHOSTTY_RENDER_STATE_DIRTY_FULL => DIRTY_FULL,
            ghostty::GHOSTTY_RENDER_STATE_DIRTY_PARTIAL => DIRTY_PARTIAL,
            _ => DIRTY_NONE,
        };
        put_u16(header, 30, dirty_bits);
    }

    unsafe fn write_cell(&mut self, at: usize, column: u16) {
        let cell = &mut self.buffer[at..at + CELL_BYTES];
        cell.fill(0);

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

        let mut bg = ghostty::GhosttyColorRgb { r: 0, g: 0, b: 0 };
        let mut fg = ghostty::GhosttyColorRgb { r: 255, g: 255, b: 255 };
        let _ = ghostty::ghostty_render_state_row_cells_get(
            self.cells,
            ghostty::GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR,
            &mut bg as *mut _ as *mut c_void,
        );
        let _ = ghostty::ghostty_render_state_row_cells_get(
            self.cells,
            ghostty::GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR,
            &mut fg as *mut _ as *mut c_void,
        );

        let mut selected = false;
        let _ = ghostty::ghostty_render_state_row_cells_get(
            self.cells,
            ghostty::GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_SELECTED,
            &mut selected as *mut bool as *mut c_void,
        );

        let mut attributes = 0u32;
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

        put_u32(cell, 0, codepoints[0]);
        put_u32(cell, 4, rgba(fg));
        put_u32(cell, 8, rgba(bg));
        put_u32(cell, 12, attributes);
        cell[16] = 1;
        let mut flags = 0u8;
        if selected {
            flags |= CELL_FLAG_SELECTED;
        }
        if grapheme_len > 1 {
            flags |= CELL_FLAG_GRAPHEME;
        }
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

fn rgba(color: ghostty::GhosttyColorRgb) -> u32 {
    (u32::from(color.r) << 24) | (u32::from(color.g) << 16) | (u32::from(color.b) << 8) | 0xff
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
