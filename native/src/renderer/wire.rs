//! One frame, in one call.
//!
//! The Kotlin side asks for a frame and gets a flat, fixed-layout buffer back.
//! It never touches a terminal, a render state or a row iterator, and it never
//! asks a second question that could be answered from a different moment — the
//! header and the cells are one snapshot.
//!
//! ```text
//! header (80 bytes, little-endian)
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
//!  48  u8x32 dirty_rows   one bit per captured row: the row changed
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
//! 17  u8   flags     bit0 selected, bit1 has grapheme, bit2 highlight,
//!                    bit3 app-selected, bit4 all-matches highlight
//! 18  u16  reserved
//! ```
//!
//! The colours are *resolved*: the library has already applied the palette, the
//! inverse flag, bold-brightening and the selection. Kotlin draws a rectangle
//! with a colour and a glyph; it does not decide what a colour means.

pub const MAGIC: u32 = 0x4853_5244; // 'DRSH' little-endian
pub const VERSION: u16 = 2;

pub const HEADER_BYTES: usize = 80;
pub const CELL_BYTES: usize = 20;

/// How many rows the dirty bitmap can name.
///
/// One bit per captured row, so a bitmap of thirty-two bytes covers a screen
/// of two hundred and fifty rows — more than any phone shows, even on a
/// desktop with the keyboard open.
pub const DIRTY_ROW_BYTES: usize = 32;

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
/// The cell is part of one of the search's *other* matches — not the active
/// one — and is drawn in the weaker of the two highlight tints.
pub const CELL_FLAG_HIGHLIGHT_ALL: u8 = 1 << 4;

pub const DIRTY_NONE: u16 = 0;
pub const DIRTY_FULL: u16 = 1;
pub const DIRTY_PARTIAL: u16 = 2;

pub const CURSOR_VISIBLE: u16 = 1 << 0;
pub const CURSOR_BLINKING: u16 = 1 << 1;
/// The cursor is a thin rectangle (bar or underline), not a filled cell.
pub const CURSOR_IS_RECTANGLE: u16 = 1 << 2;

/// A colour as the frame carries it: R in the low byte.
///
/// Little-endian that is the byte sequence R, G, B, A — which is what
/// `GL_UNSIGNED_BYTE` normalised hands the fragment shader as `vec4`. The
/// previous packing put R in the high byte, so the renderer read green as
/// red, blue as green, and the real alpha byte as a colour channel.
pub(super) fn rgba(color: crate::ghostty::GhosttyColorRgb) -> u32 {
    u32::from(color.r) | (u32::from(color.g) << 8) | (u32::from(color.b) << 16) | (0xff << 24)
}

pub(super) fn push_u8(out: &mut Vec<u8>, value: u8) {
    out.push(value);
}

pub(super) fn push_u32(out: &mut Vec<u8>, value: u32) {
    out.extend_from_slice(&value.to_le_bytes());
}

pub(super) fn push_u64(out: &mut Vec<u8>, value: u64) {
    out.extend_from_slice(&value.to_le_bytes());
}

pub(super) fn push_i32(out: &mut Vec<u8>, value: i32) {
    out.extend_from_slice(&value.to_le_bytes());
}

pub(super) fn put_u16(out: &mut [u8], at: usize, value: u16) {
    out[at..at + 2].copy_from_slice(&value.to_le_bytes());
}

pub(super) fn put_i16(out: &mut [u8], at: usize, value: i16) {
    out[at..at + 2].copy_from_slice(&value.to_le_bytes());
}

pub(super) fn put_u32(out: &mut [u8], at: usize, value: u32) {
    out[at..at + 4].copy_from_slice(&value.to_le_bytes());
}