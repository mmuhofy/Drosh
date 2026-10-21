//! What to draw for a codepoint.
//!
//! Phase B ships exactly one font (JetBrains Mono), which covers Latin,
//! Greek, Cyrillic and the box-drawing range. Everything outside it — CJK,
//! emoji, historic scripts — would come from a fallback chain, and that chain is
//! not built yet: the grid must stay aligned, so such a cell gets the
//! replacement glyph instead of a hole, and the caller can see that it happened.
//!
//! The chain itself is a Phase B/C task (`font/fallback.rs` in `PLAN.md` §5 lists
//! ASCII → Latin → CJK → emoji, with colour glyphs as a separate atlas pass).

use swash::FontRef;

/// The glyph a codepoint resolves to.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Resolution {
    /// The font has a glyph for this codepoint.
    Direct(u16),
    /// The font has none; this is the replacement glyph standing in for it.
    Substituted(u16),
    /// Neither the codepoint nor the replacement is in the font.
    Missing,
}

/// What the terminal draws when a codepoint cannot be rendered as itself.
pub const REPLACEMENT: char = '?';

/// Resolve `codepoint` against the first face in `data`.
pub fn resolve(data: &[u8], codepoint: char) -> Resolution {
    let Some(font) = FontRef::from_index(data, 0) else {
        return Resolution::Missing;
    };
    let charmap = font.charmap();

    let glyph_id = charmap.map(codepoint);
    if glyph_id != 0 {
        return Resolution::Direct(glyph_id);
    }

    // Glyph 0 is `.notdef`; a font that maps the replacement character has
    // given us something better than the empty box.
    let replacement = charmap.map(REPLACEMENT);
    if replacement != 0 {
        Resolution::Substituted(replacement)
    } else {
        Resolution::Missing
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn garbage_data_resolves_to_nothing() {
        assert_eq!(resolve(&[0, 1, 2, 3], 'a'), Resolution::Missing);
    }
}
