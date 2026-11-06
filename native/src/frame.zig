//! One frame, in one call.
//!
//! The Kotlin side asks for a frame and gets a flat, fixed-layout buffer back.
//! It never touches a `GhosttyTerminal`, a `GhosttyRenderState` or a row
//! iterator, and it never asks a second question that could be answered from a
//! different moment — the header and the cells are one snapshot, taken under
//! one lock hold.
//!
//! ## The layout
//!
//! ```
//! header (FRAME_HEADER_BYTES, little-endian)
//!   0  u32  magic          'DRSH'
//!   4  u16  version
//!   6  u16  header_bytes
//!   8  u32  frame_sequence
//!  12  u16  columns
//!  14  u16  rows           (viewport rows, not counting overscan)
//!  16  u16  row_count      (rows actually written, including overscan)
//!  18  i16  overscan_above
//!  20  i16  overscan_below
//!  22  u16  cursor_x
//!  24  u16  cursor_y       (relative to the viewport)
//!  26  u16  cursor_style
//!  28  u16  cursor_flags   bit0 visible, bit1 blinking
//!  30  u16  dirty          bit0 full, bit1 partial, bit2 none
//!  32  u32  cell_count
//!  36  u32  cell_stride
//!  40  u32  cells_bytes
//!  44  u32  reserved
//! ```
//!
//! Cells follow the header: `cell_count` records of `cell_stride` bytes each,
//! row-major, `row_count` rows of `columns` cells.
//!
//! ## The cell
//!
//! ```
//!  0  u32  codepoint (base)
//!  4  u32  foreground RGBA
//!  8  u32  background RGBA
//! 12  u32  attributes (bold, italic, underline, …)
//! 16  u8   width      1, 2 (wide), 0 (spacer tail)
//! 17  u8   flags      bit0 selected, bit1 has grapheme
//! 18  u16  reserved
//! ```
//!
//! The colours are *resolved*: the library has already applied the palette, the
//! inverse flag, the bold-brightening and the selection. Kotlin draws a
//! rectangle with a colour and a glyph; it does not decide what a colour means.

const std = @import("std");
const ghostty = @import("ghostty.zig");
const c = ghostty.c;

const allocator = @import("alloc.zig").allocator;

// --- the wire format -------------------------------------------------------

pub const MAGIC: u32 = 0x4853_5244; // 'DRSH' little-endian
pub const VERSION: u16 = 1;

pub const HEADER_BYTES: u32 = 48;
pub const CELL_BYTES: u32 = 20;

pub const ATTR_BOLD: u32 = 1 << 0;
pub const ATTR_ITALIC: u32 = 1 << 1;
pub const ATTR_FAINT: u32 = 1 << 2;
pub const ATTR_BLINK: u32 = 1 << 3;
pub const ATTR_STRIKETHROUGH: u32 = 1 << 4;
pub const ATTR_OVERLINE: u32 = 1 << 5;
pub const ATTR_INVISIBLE: u32 = 1 << 6;
/// Underline styles occupy bits 8..11 (0 none, 1 single, 2 double, 3 curly,
/// 4 dotted, 5 dashed).
pub const ATTR_UNDERLINE_SHIFT: u32 = 8;
pub const ATTR_UNDERLINE_MASK: u32 = 0xf << ATTR_UNDERLINE_SHIFT;

pub const CELL_FLAG_SELECTED: u8 = 1 << 0;
pub const CELL_FLAG_GRAPHEME: u8 = 1 << 1;

pub const DIRTY_NONE: u16 = 0;
pub const DIRTY_FULL: u16 = 1;
pub const DIRTY_PARTIAL: u16 = 2;

pub const CURSOR_VISIBLE: u16 = 1 << 0;
pub const CURSOR_BLINKING: u16 = 1 << 1;

// --- state -----------------------------------------------------------------

/// Everything a session needs to produce frames, in one allocation.
///
/// The render state is expensive to create and is reused for the life of the
/// session; the cell buffer is grown only when the grid does.
pub const FrameSource = struct {
    terminal: ghostty.Terminal,
    state: ghostty.RenderState,
    iterator: ghostty.RowIterator,
    cells: ghostty.RowCells,
    /// The output buffer handed to Kotlin, reused frame to frame.
    buffer: []u8 = &.{},
    sequence: u32 = 0,

    pub fn create(terminal: ghostty.Terminal) !FrameSource {
        var state: ghostty.RenderState = null;
        if (c.ghostty_render_state_new(null, &state) != ghostty.SUCCESS) {
            return error.RenderStateFailed;
        }
        errdefer c.ghostty_render_state_free(state);

        var iterator: ghostty.RowIterator = null;
        if (c.ghostty_render_state_row_iterator_new(null, &iterator) != ghostty.SUCCESS) {
            return error.IteratorFailed;
        }
        errdefer c.ghostty_render_state_row_iterator_free(iterator);

        var cells: ghostty.RowCells = null;
        if (c.ghostty_render_state_row_cells_new(null, &cells) != ghostty.SUCCESS) {
            return error.CellsFailed;
        }
        errdefer c.ghostty_render_state_row_cells_free(cells);

        // One row of overscan below the viewport: the row that a smooth scroll
        // reveals at the bottom edge. The library captures it for us, so the
        // renderer never asks the grid for a row it does not have.
        var overscan = c.GhosttyRenderStateOverscan{ .above = 1, .below = 1 };
        _ = c.ghostty_render_state_set(
            state,
            c.GHOSTTY_RENDER_STATE_OPTION_OVERSCAN,
            @ptrCast(&overscan),
        );

        return .{
            .terminal = terminal,
            .state = state,
            .iterator = iterator,
            .cells = cells,
        };
    }

    pub fn destroy(self: *FrameSource) void {
        if (self.buffer.len > 0) allocator.free(self.buffer);
        c.ghostty_render_state_row_cells_free(self.cells);
        c.ghostty_render_state_row_iterator_free(self.iterator);
        c.ghostty_render_state_free(self.state);
        self.* = undefined;
    }

    /// Produce the next frame into `self.buffer` and return it.
    pub fn render(self: *FrameSource) ![]const u8 {
        if (c.ghostty_render_state_update(self.state, self.terminal) != ghostty.SUCCESS) {
            return error.UpdateFailed;
        }

        var cols: u16 = 0;
        var rows: u16 = 0;
        _ = c.ghostty_render_state_get(self.state, c.GHOSTTY_RENDER_STATE_DATA_COLS, @ptrCast(&cols));
        _ = c.ghostty_render_state_get(self.state, c.GHOSTTY_RENDER_STATE_DATA_ROWS, @ptrCast(&rows));
        if (cols == 0 or rows == 0) return error.EmptyGrid;

        // How many rows the render state actually captured, including the
        // overscan it could find. Near the top of the scrollback there is no
        // row above; at the live edge there is no row below.
        var overscan = c.GhosttyRenderStateOverscan{ .above = 0, .below = 0 };
        _ = c.ghostty_render_state_get(
            self.state,
            c.GHOSTTY_RENDER_STATE_DATA_OVERSCAN,
            @ptrCast(&overscan),
        );

        const row_count: u32 = @as(u32, rows) + overscan.above + overscan.below;
        const cell_count: u32 = row_count * cols;

        const needed: usize = HEADER_BYTES + @as(usize, cell_count) * CELL_BYTES;
        if (self.buffer.len < needed) {
            if (self.buffer.len > 0) allocator.free(self.buffer);
            self.buffer = try allocator.alloc(u8, needed);
        }

        // The header is written first and the cells after it, so a reader can
        // parse the header before deciding whether to trust the body.
        const header = self.buffer[0..HEADER_BYTES];
        writeU32(header, 0, MAGIC);
        writeU16(header, 4, VERSION);
        writeU16(header, 6, @intCast(HEADER_BYTES));
        self.sequence +%= 1;
        writeU32(header, 8, self.sequence);
        writeU16(header, 12, cols);
        writeU16(header, 14, rows);
        writeU16(header, 16, @intCast(row_count));
        writeI16(header, 18, @intCast(overscan.above));
        writeI16(header, 20, @intCast(overscan.below));
        writeU32(header, 32, cell_count);
        writeU32(header, 36, CELL_BYTES);
        writeU32(header, 40, @intCast(@as(usize, cell_count) * CELL_BYTES));

        // Cursor and dirty state come from the same update.
        var cursor_x: u16 = 0;
        var cursor_y: u16 = 0;
        var cursor_style: u16 = 0;
        var cursor_visible: bool = false;
        var cursor_blinking: bool = false;
        _ = c.ghostty_render_state_get(self.state, c.GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_X, @ptrCast(&cursor_x));
        _ = c.ghostty_render_state_get(self.state, c.GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_Y, @ptrCast(&cursor_y));
        _ = c.ghostty_render_state_get(self.state, c.GHOSTTY_RENDER_STATE_DATA_CURSOR_VISUAL_STYLE, @ptrCast(&cursor_style));
        _ = c.ghostty_render_state_get(self.state, c.GHOSTTY_RENDER_STATE_DATA_CURSOR_VISIBLE, @ptrCast(&cursor_visible));
        _ = c.ghostty_render_state_get(self.state, c.GHOSTTY_RENDER_STATE_DATA_CURSOR_BLINKING, @ptrCast(&cursor_blinking));
        writeU16(header, 22, cursor_x);
        writeU16(header, 24, cursor_y);
        writeU16(header, 26, cursor_style);
        writeU16(header, 28, (if (cursor_visible) CURSOR_VISIBLE else 0) |
            (if (cursor_blinking) CURSOR_BLINKING else 0));

        var dirty: c.GhosttyRenderStateDirty = c.GHOSTTY_RENDER_STATE_DIRTY_NONE;
        _ = c.ghostty_render_state_get(self.state, c.GHOSTTY_RENDER_STATE_DATA_DIRTY, @ptrCast(&dirty));
        writeU16(header, 30, switch (dirty) {
            c.GHOSTTY_RENDER_STATE_DIRTY_FULL => DIRTY_FULL,
            c.GHOSTTY_RENDER_STATE_DIRTY_PARTIAL => DIRTY_PARTIAL,
            else => DIRTY_NONE,
        });

        // The rows. The iterator visits overscan rows and viewport rows
        // together, top to bottom, so the buffer is already in draw order.
        if (c.ghostty_render_state_get(
            self.state,
            c.GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR,
            @ptrCast(&self.iterator),
        ) != ghostty.SUCCESS) {
            return error.IteratorBindFailed;
        }

        var index: u32 = 0;
        while (index < cell_count) {
            if (!c.ghostty_render_state_row_iterator_next(self.iterator)) break;

            if (c.ghostty_render_state_row_get(
                self.iterator,
                c.GHOSTTY_RENDER_STATE_ROW_DATA_CELLS,
                @ptrCast(&self.cells),
            ) != ghostty.SUCCESS) continue;

            var column: u16 = 0;
            while (column < cols) : (column += 1) {
                const at = HEADER_BYTES + @as(usize, index) * CELL_BYTES;
                writeCell(self.buffer[at .. at + CELL_BYTES], self.cells, column);
                index += 1;
            }
        }

        // A grid that shrank between the two halves leaves the tail of the
        // buffer unwritten. Zeroing it is cheaper than telling the caller, and
        // a zeroed cell draws nothing.
        if (index < cell_count) {
            const from = HEADER_BYTES + @as(usize, index) * CELL_BYTES;
            @memset(self.buffer[from..needed], 0);
        }

        return self.buffer[0..needed];
    }
};

/// Write one cell's record.
fn writeCell(out: []u8, cells: ghostty.RowCells, column: u16) void {
    @memset(out, 0);

    if (c.ghostty_render_state_row_cells_select(cells, column) != ghostty.SUCCESS) {
        return;
    }

    // The base codepoint. `GRAPHEMES_BUF` wants a buffer of `GRAPHEMES_LEN`
    // codepoints; the base is the first, and that is what a cell record draws.
    var codepoints: [8]u32 = undefined;
    _ = c.ghostty_render_state_row_cells_get(
        cells,
        c.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF,
        @ptrCast(&codepoints),
    );
    var grapheme_len: u32 = 0;
    _ = c.ghostty_render_state_row_cells_get(
        cells,
        c.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_LEN,
        @ptrCast(&grapheme_len),
    );

    var style: c.GhosttyStyle = std.mem.zeroes(c.GhosttyStyle);
    style.size = @sizeOf(c.GhosttyStyle);
    _ = c.ghostty_render_state_row_cells_get(
        cells,
        c.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE,
        @ptrCast(&style),
    );

    var bg = c.GhosttyColorRgb{ .r = 0, .g = 0, .b = 0 };
    var fg = c.GhosttyColorRgb{ .r = 255, .g = 255, .b = 255 };
    _ = c.ghostty_render_state_row_cells_get(
        cells,
        c.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR,
        @ptrCast(&bg),
    );
    _ = c.ghostty_render_state_row_cells_get(
        cells,
        c.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR,
        @ptrCast(&fg),
    );

    var selected: bool = false;
    _ = c.ghostty_render_state_row_cells_get(
        cells,
        c.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_SELECTED,
        @ptrCast(&selected),
    );

    var attributes: u32 = 0;
    if (style.bold) attributes |= ATTR_BOLD;
    if (style.italic) attributes |= ATTR_ITALIC;
    if (style.faint) attributes |= ATTR_FAINT;
    if (style.blink) attributes |= ATTR_BLINK;
    if (style.strikethrough) attributes |= ATTR_STRIKETHROUGH;
    if (style.overline) attributes |= ATTR_OVERLINE;
    if (style.invisible) attributes |= ATTR_INVISIBLE;
    attributes |= (@as(u32, @intCast(style.underline)) & 0xf) << ATTR_UNDERLINE_SHIFT;

    writeU32(out, 0, codepoints[0]);
    writeU32(out, 4, rgba(fg));
    writeU32(out, 8, rgba(bg));
    writeU32(out, 12, attributes);
    out[16] = 1;
    out[17] = (if (selected) CELL_FLAG_SELECTED else 0) |
        (if (grapheme_len > 1) CELL_FLAG_GRAPHEME else 0);
}

inline fn rgba(color: c.GhosttyColorRgb) u32 {
    return (@as(u32, color.r) << 24) | (@as(u32, color.g) << 16) |
        (@as(u32, color.b) << 8) | 0xff;
}

fn writeU16(out: []u8, at: usize, value: u16) void {
    std.mem.writeInt(u16, out[at..][0..2], value, .little);
}

fn writeI16(out: []u8, at: usize, value: i16) void {
    std.mem.writeInt(i16, out[at..][0..2], value, .little);
}

fn writeU32(out: []u8, at: usize, value: u32) void {
    std.mem.writeInt(u32, out[at..][0..4], value, .little);
}
