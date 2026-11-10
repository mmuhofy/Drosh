//! The colours a cell is drawn with.
//!
//! Ported from Ghostty's own renderer: `src/renderer/cell.zig` for the
//! character rules and `src/renderer/generic.zig` for the colour rules.
//!
//! # Why this file exists
//!
//! The colour of a cell is not "the background the style asked for, and the
//! foreground the style asked for". It is a decision with four inputs and
//! three special cases, and getting it wrong is invisible on plain text and
//! obvious on a program that draws a coloured bar: the colours come out
//! swapped, and the bar is the wrong colour.
//!
//! # The rule
//!
//! From Ghostty's `generic.zig`:
//!
//! ```text
//! bg_style = the style's background, or none
//! fg_style = the style's foreground, or the terminal's foreground
//! final_bg = bg_style, or the terminal's background
//!
//! bg = if inverse != is_covering(codepoint) then fg_style else bg_style
//! fg = if inverse then final_bg else fg_style
//! ```
//!
//! The inverse is **not** a swap of the two resolved colours. It is a choice
//! between two of them, and which one it picks depends on whether the cell
//! carries a colour of its own:
//!
//! - a cell with no colour of its own comes out the same either way — the
//!   foreground behind the background;
//! - a cell with a background of its own comes out with that background as
//!   the *text*, which is the opposite of what a swap would do.
//!
//! A status bar drawn with a background colour and the reverse-video flag
//! lives in exactly that second case, which is why the difference matters.

/// A colour, as the engine reports it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Rgb {
    pub r: u8,
    pub g: u8,
    pub b: u8,
}

impl Rgb {
    pub const fn new(r: u8, g: u8, b: u8) -> Self {
        Self { r, g, b }
    }
}

/// The colours of the terminal itself: what a cell with no colour of its own
/// is drawn in.
#[derive(Clone, Copy, Debug)]
pub struct Defaults {
    pub foreground: Rgb,
    pub background: Rgb,
}

/// The style of one cell, as the engine reports it.
#[derive(Clone, Copy, Debug, Default)]
pub struct CellStyle {
    /// The style's own foreground, or none when the style has none.
    pub foreground: Option<Rgb>,
    /// The style's own background, or none when the style has none.
    pub background: Option<Rgb>,
    /// The reverse-video flag.
    pub inverse: bool,
    /// The bold flag.
    pub bold: bool,
    /// The faint flag.
    pub faint: bool,
}

/// The colours a cell is drawn with.
#[derive(Clone, Copy, Debug)]
pub struct CellColors {
    pub foreground: Rgb,
    pub background: Rgb,
    /// The foreground's alpha: a faint cell is drawn translucent.
    pub foreground_alpha: u8,
}

/// The colours of a cell, by Ghostty's rule.
///
/// `codepoint` is the cell's character: a full block covers its cell, and a
/// covering cell takes the foreground as its background so that the padding
/// around it continues the colour.
pub fn colors(style: &CellStyle, codepoint: u32, defaults: &Defaults) -> CellColors {
    let bg_style = style.background;
    let fg_style = style.foreground.or(Some(defaults.foreground));
    let final_bg = bg_style.unwrap_or(defaults.background);

    // Two cases make the cell take the foreground as its background: the
    // inverse flag, and a covering glyph. When exactly one of them is true,
    // the foreground becomes the background; when both are true they cancel
    // out and the style's own background stands.
    let background = if style.inverse != is_covering(codepoint) {
        fg_style
    } else {
        bg_style
    };

    let foreground = if style.inverse { final_bg } else { fg_style.unwrap_or(defaults.foreground) };

    CellColors {
        foreground,
        background: background.unwrap_or(defaults.background),
        foreground_alpha: if style.faint { FAINT_ALPHA } else { 255 },
    }
}

/// The alpha a faint cell is drawn with.
const FAINT_ALPHA: u8 = 128;

/// Whether a codepoint covers its whole cell.
///
/// Ported from Ghostty's `src/renderer/cell.zig`: a full block. A covering
/// cell takes the foreground as its background, which is what makes the
/// padding around a coloured block the same colour as the block.
pub fn is_covering(codepoint: u32) -> bool {
    matches!(codepoint, 0x2588)
}

/// Whether a codepoint is one of the graphics elements.
///
/// Ported from Ghostty's `src/renderer/cell.zig`: the box drawing, the block
/// elements, the legacy computing symbols and the powerline range.
pub fn is_graphics_element(codepoint: u32) -> bool {
    is_box_drawing(codepoint)
        || is_block_element(codepoint)
        || is_legacy_computing(codepoint)
        || is_powerline(codepoint)
}

/// Whether a codepoint is a box drawing character.
pub fn is_box_drawing(codepoint: u32) -> bool {
    (0x2500..=0x257f).contains(&codepoint)
}

/// Whether a codepoint is a block element.
pub fn is_block_element(codepoint: u32) -> bool {
    (0x2580..=0x259f).contains(&codepoint)
}

/// Whether a codepoint is in the symbols for legacy computing block.
pub fn is_legacy_computing(codepoint: u32) -> bool {
    (0x1fb00..=0x1fbff).contains(&codepoint) || (0x1cc00..=0x1cebf).contains(&codepoint)
}

/// Whether a codepoint is in the powerline range.
pub fn is_powerline(codepoint: u32) -> bool {
    (0xe0b0..=0xe0d7).contains(&codepoint)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn defaults() -> Defaults {
        Defaults {
            foreground: Rgb::new(0xd0, 0xd0, 0xd0),
            background: Rgb::new(0x10, 0x10, 0x10),
        }
    }

    #[test]
    fn a_plain_cell_is_the_defaults() {
        let colors = colors(&CellStyle::default(), 'x' as u32, &defaults());
        assert_eq!(colors.foreground, Rgb::new(0xd0, 0xd0, 0xd0));
        assert_eq!(colors.background, Rgb::new(0x10, 0x10, 0x10));
    }

    #[test]
    fn an_inverse_cell_swaps_the_defaults() {
        let style = CellStyle {
            inverse: true,
            ..CellStyle::default()
        };
        let colors = colors(&style, 'x' as u32, &defaults());
        assert_eq!(colors.foreground, Rgb::new(0x10, 0x10, 0x10));
        assert_eq!(colors.background, Rgb::new(0xd0, 0xd0, 0xd0));
    }

    #[test]
    fn an_inverse_cell_with_a_background_puts_that_background_in_the_text() {
        // The case a symmetric swap gets wrong: the cell carries a background
        // of its own, and the inverse flag turns it into the text colour.
        let style = CellStyle {
            background: Some(Rgb::new(0x20, 0x40, 0x80)),
            inverse: true,
            ..CellStyle::default()
        };
        let colors = colors(&style, 'x' as u32, &defaults());
        assert_eq!(colors.foreground, Rgb::new(0x20, 0x40, 0x80));
        assert_eq!(colors.background, Rgb::new(0xd0, 0xd0, 0xd0));
    }

    #[test]
    fn a_covering_cell_takes_the_foreground_as_its_background() {
        // A full block is drawn in the text colour, and the padding around it
        // continues that colour.
        let style = CellStyle {
            foreground: Some(Rgb::new(0xff, 0x00, 0x00)),
            ..CellStyle::default()
        };
        let colors = colors(&style, 0x2588, &defaults());
        assert_eq!(colors.background, Rgb::new(0xff, 0x00, 0x00));
    }

    #[test]
    fn an_inverse_covering_cell_cancels_out() {
        // Both rules say the same thing, so they cancel: the style's own
        // background stands.
        let style = CellStyle {
            background: Some(Rgb::new(0x20, 0x40, 0x80)),
            inverse: true,
            ..CellStyle::default()
        };
        let colors = colors(&style, 0x2588, &defaults());
        assert_eq!(colors.background, Rgb::new(0x20, 0x40, 0x80));
    }

    #[test]
    fn a_faint_cell_is_translucent() {
        let style = CellStyle {
            faint: true,
            ..CellStyle::default()
        };
        let colors = colors(&style, 'x' as u32, &defaults());
        assert_eq!(colors.foreground_alpha, FAINT_ALPHA);
    }

    #[test]
    fn the_graphics_elements_are_the_ones_ghostty_names() {
        assert!(is_graphics_element(0x2502)); // box drawing
        assert!(is_graphics_element(0x2588)); // block element
        assert!(is_graphics_element(0xe0b0)); // powerline
        assert!(!is_graphics_element('a' as u32));
    }
}
