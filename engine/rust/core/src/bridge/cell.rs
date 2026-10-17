//! Cell encoding for the snapshot buffer.
//!
//! One cell is a fixed-size, little-endian record; the buffer is a flat
//! `rows * columns` array of records that Kotlin reads out of a direct
//! `ByteBuffer` (decision D5: a snapshot, not a render-command list).
//!
//! ```text
//! offset  0  u32  codepoint (Unicode scalar value)
//! offset  4  u32  foreground color (tagged, see [`encode_color`])
//! offset  8  u32  background color (tagged)
//! offset 12  u16  cell flags (see [`flags`])
//! offset 14  u16  reserved (0; Phase B: underline color / glyph id)
//! ```
//!
//! The layout is frozen for the prototype — the Kotlin mirror in
//! `android/renderer/` is changed in the same commit as this file.

use alacritty_terminal::term::cell::{Cell, Flags};
use alacritty_terminal::vte::ansi::Color;

/// Bytes per cell record.
pub const CELL_BYTES: usize = 16;

/// Color tag for a named colour (`NamedColor`, payload = its index, 0..=268).
///
/// The payload keeps the *full* discriminant: `NamedColor` runs past 255
/// (`Foreground = 256`, `Background = 257`, `Cursor = 258`, `DimBlack..
/// DimWhite = 259..=266`, `BrightForeground = 267`, `DimForeground = 268`, the
/// last variant in vte 0.15), so truncating it to 8 bits folds `Background`
/// onto `Red`. Only `COLOR_VALUE_MASK` (24 bits) may be applied.
pub const COLOR_TAG_NAMED: u32 = 0;
/// Color tag for a palette index (`Indexed`, payload = 0..=255).
pub const COLOR_TAG_INDEXED: u32 = 1;
/// Color tag for a 24-bit colour (`Spec`, payload = `0x00RRGGBB`).
pub const COLOR_TAG_SPEC: u32 = 2;

const COLOR_TAG_SHIFT: u32 = 30;
const COLOR_VALUE_MASK: u32 = 0x00ff_ffff;

/// Cell flags as they cross the FFI boundary.
///
/// Deliberately *not* alacritty's `Flags` bit layout: the wire format stays
/// stable across engine upgrades, and the Kotlin mirror is written against
/// this table.
pub mod flags {
    pub const INVERSE: u16 = 1 << 0;
    pub const BOLD: u16 = 1 << 1;
    pub const ITALIC: u16 = 1 << 2;
    pub const UNDERLINE: u16 = 1 << 3;
    pub const STRIKEOUT: u16 = 1 << 4;
    pub const DIM: u16 = 1 << 5;
    pub const HIDDEN: u16 = 1 << 6;
    pub const WIDE_CHAR: u16 = 1 << 7;
    pub const WIDE_CHAR_SPACER: u16 = 1 << 8;
    pub const LEADING_WIDE_CHAR_SPACER: u16 = 1 << 9;
    pub const WRAPLINE: u16 = 1 << 10;
    pub const DOUBLE_UNDERLINE: u16 = 1 << 11;
    pub const UNDERCURL: u16 = 1 << 12;
    pub const DOTTED_UNDERLINE: u16 = 1 << 13;
    pub const DASHED_UNDERLINE: u16 = 1 << 14;
}

/// Engine flag → wire flag. Combined engine flags (`BOLD_ITALIC`,
/// `DIM_BOLD`, `ALL_UNDERLINES`) are left out; they are derivable from the
/// atomic ones.
const FLAG_TABLE: &[(Flags, u16)] = &[
    (Flags::INVERSE, flags::INVERSE),
    (Flags::BOLD, flags::BOLD),
    (Flags::ITALIC, flags::ITALIC),
    (Flags::UNDERLINE, flags::UNDERLINE),
    (Flags::STRIKEOUT, flags::STRIKEOUT),
    (Flags::DIM, flags::DIM),
    (Flags::HIDDEN, flags::HIDDEN),
    (Flags::WIDE_CHAR, flags::WIDE_CHAR),
    (Flags::WIDE_CHAR_SPACER, flags::WIDE_CHAR_SPACER),
    (Flags::LEADING_WIDE_CHAR_SPACER, flags::LEADING_WIDE_CHAR_SPACER),
    (Flags::WRAPLINE, flags::WRAPLINE),
    (Flags::DOUBLE_UNDERLINE, flags::DOUBLE_UNDERLINE),
    (Flags::UNDERCURL, flags::UNDERCURL),
    (Flags::DOTTED_UNDERLINE, flags::DOTTED_UNDERLINE),
    (Flags::DASHED_UNDERLINE, flags::DASHED_UNDERLINE),
];

/// One grid cell in wire format.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CellRecord {
    pub codepoint: u32,
    pub fg: u32,
    pub bg: u32,
    pub flags: u16,
    pub reserved: u16,
}

impl CellRecord {
    /// Encode a grid cell.
    ///
    /// Zerowidth (combining) characters are not carried yet: Phase B shapes
    /// text in Rust, so the record gains a glyph reference then (TODO.md).
    pub fn from_cell(cell: &Cell) -> Self {
        Self {
            codepoint: cell.c as u32,
            fg: encode_color(cell.fg),
            bg: encode_color(cell.bg),
            flags: encode_flags(cell.flags),
            reserved: 0,
        }
    }

    /// Write the record as [`CELL_BYTES`] little-endian bytes.
    ///
    /// Returns `false` when `out` is too short; the caller decides whether that
    /// is an error.
    pub fn encode_into(self, out: &mut [u8]) -> bool {
        if out.len() < CELL_BYTES {
            return false;
        }

        out[0..4].copy_from_slice(&self.codepoint.to_le_bytes());
        out[4..8].copy_from_slice(&self.fg.to_le_bytes());
        out[8..12].copy_from_slice(&self.bg.to_le_bytes());
        out[12..14].copy_from_slice(&self.flags.to_le_bytes());
        out[14..16].copy_from_slice(&self.reserved.to_le_bytes());
        true
    }

    /// Decode a record. `None` when `record` is too short.
    pub fn decode(record: &[u8]) -> Option<Self> {
        if record.len() < CELL_BYTES {
            return None;
        }

        Some(Self {
            codepoint: u32::from_le_bytes(record[0..4].try_into().ok()?),
            fg: u32::from_le_bytes(record[4..8].try_into().ok()?),
            bg: u32::from_le_bytes(record[8..12].try_into().ok()?),
            flags: u16::from_le_bytes(record[12..14].try_into().ok()?),
            reserved: u16::from_le_bytes(record[14..16].try_into().ok()?),
        })
    }
}

/// Pack a vte color into the wire format.
pub fn encode_color(color: Color) -> u32 {
    match color {
        // `NamedColor` reaches 268, so the payload must not be masked down to a
        // byte: `Background` (257) became `Red` (1) that way, which painted
        // every default cell red on the device (Phase A acceptance, 2026-10-16).
        Color::Named(named) => (COLOR_TAG_NAMED << COLOR_TAG_SHIFT) | (named as u32),
        Color::Indexed(index) => (COLOR_TAG_INDEXED << COLOR_TAG_SHIFT) | u32::from(index),
        Color::Spec(rgb) => {
            let value = (u32::from(rgb.r) << 16) | (u32::from(rgb.g) << 8) | u32::from(rgb.b);
            (COLOR_TAG_SPEC << COLOR_TAG_SHIFT) | value
        }
    }
}

/// Tag of an encoded color (`COLOR_TAG_*`).
pub fn color_tag(encoded: u32) -> u32 {
    encoded >> COLOR_TAG_SHIFT
}

/// Payload of an encoded color: the named/indexed index, or `0x00RRGGBB`.
pub fn color_payload(encoded: u32) -> u32 {
    encoded & COLOR_VALUE_MASK
}

/// Translate engine cell flags into the wire layout.
pub fn encode_flags(engine: Flags) -> u16 {
    let mut bits = 0u16;
    for (source, wire) in FLAG_TABLE {
        if engine.contains(*source) {
            bits |= wire;
        }
    }
    bits
}

#[cfg(test)]
mod tests {
    use alacritty_terminal::vte::ansi::{NamedColor, Rgb};

    use super::*;

    #[test]
    fn color_encoding_round_trips() {
        let named = encode_color(Color::Named(NamedColor::Red));
        assert_eq!(color_tag(named), COLOR_TAG_NAMED);
        assert_eq!(color_payload(named), 1);

        let indexed = encode_color(Color::Indexed(200));
        assert_eq!(color_tag(indexed), COLOR_TAG_INDEXED);
        assert_eq!(color_payload(indexed), 200);

        let spec = encode_color(Color::Spec(Rgb { r: 0x12, g: 0x34, b: 0x56 }));
        assert_eq!(color_tag(spec), COLOR_TAG_SPEC);
        assert_eq!(color_payload(spec), 0x12_3456);
    }

    #[test]
    fn named_color_payloads_are_not_truncated() {
        // Every named colour has to survive the round trip exactly. Folding them
        // into one byte made `Background` and `Red` the same value, which is
        // what the Phase A device test saw as a red screen (2026-10-16).
        let payload = |named: NamedColor| color_payload(encode_color(Color::Named(named)));

        assert_eq!(payload(NamedColor::Black), 0);
        assert_eq!(payload(NamedColor::White), 7);
        assert_eq!(payload(NamedColor::BrightWhite), 15);
        assert_eq!(payload(NamedColor::Foreground), 256);
        assert_eq!(payload(NamedColor::Background), 257);
        assert_eq!(payload(NamedColor::Cursor), 258);
        assert_eq!(payload(NamedColor::DimBlack), 259);
        assert_eq!(payload(NamedColor::DimWhite), 266);
        assert_eq!(payload(NamedColor::BrightForeground), 267);
        // `DimForeground` (268) is the last variant in vte 0.15 — the enum ends
        // there, so the payload never needs more than 9 bits.
        assert_eq!(payload(NamedColor::DimForeground), 268);

        // The specific collision that shipped: background must not read as red.
        assert_ne!(
            encode_color(Color::Named(NamedColor::Background)),
            encode_color(Color::Named(NamedColor::Red)),
        );
    }

    #[test]
    fn flag_layout_is_frozen() {
        assert_eq!(encode_flags(Flags::INVERSE), flags::INVERSE);
        assert_eq!(encode_flags(Flags::WIDE_CHAR), flags::WIDE_CHAR);
        assert_eq!(encode_flags(Flags::BOLD | Flags::ITALIC), flags::BOLD | flags::ITALIC);
        assert_eq!(encode_flags(Flags::empty()), 0);
    }

    #[test]
    fn record_is_little_endian_and_fixed_size() {
        let record = CellRecord {
            codepoint: 0x41,
            fg: 0x1234_5678,
            bg: 0x9abc_def0,
            flags: 0x0102,
            reserved: 0x0304,
        };

        let mut buffer = [0u8; CELL_BYTES];
        assert!(record.encode_into(&mut buffer));
        assert_eq!(&buffer[0..4], &[0x41, 0x00, 0x00, 0x00]);
        assert_eq!(&buffer[4..8], &[0x78, 0x56, 0x34, 0x12]);
        assert_eq!(&buffer[12..14], &[0x02, 0x01]);
        assert_eq!(CellRecord::decode(&buffer), Some(record));
        assert_eq!(CellRecord::decode(&buffer[..4]), None);
    }
}
