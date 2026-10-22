//! Shaping with `harfrust` (the HarfBuzz port), on the bundled font.
//!
//! HarfBuzz's API: build a buffer of text, hand it to the shaper together
//! with the face and the features to switch on, then read the glyph ids,
//! clusters and positions back out.
//!
//! ## Why HarfRust and not rustybuzz
//!
//! The two are the same port of the same algorithm, and the difference
//! matters here: rustybuzz does not implement the *chained context*
//! substitutions (GSUB lookup type 6), and every programming font keeps its
//! ligatures in exactly that kind of lookup. JetBrains Mono's `->` is one:
//! a chain-context lookup under `calt` that rustybuzz skips, so a font with
//! ligatures and a shaper that says nothing ligate nothing at all.
//!
//! HarfRust is the port's successor — rustybuzz is archived and points at
//! it — and it is a complete HarfBuzz, chain contexts included.

use harfrust::font::Blob;
use harfrust::{Buffer, BufferFlags, Direction, Script, ShapeOptions, ShaperFont};

use crate::ligature::Ligatures;

/// The parsed face, re-exported so the stack can name it without depending
/// on the shaper's own vocabulary.
pub use harfrust::font::Font;

/// The shaper's own vocabulary, re-exported: a caller that wants to ask for
/// a feature of its own needs to name it.
pub use harfrust::{Feature, Tag};

/// One shaped glyph, in font units (divide by `units_per_em` and multiply by
/// the pixel size to get pixels).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ShapedGlyph {
    pub glyph_id: u16,
    /// Byte index of the source cluster this glyph belongs to.
    pub cluster: u32,
    pub x_advance: i32,
    pub x_offset: i32,
    pub y_offset: i32,
}

/// The font in `data`, if it is a font at all.
///
/// The bytes are copied into the font's own blob: the font owns its data,
/// and a caller that wants the font to outlive the buffer it came from
/// cannot say so otherwise.
pub fn font(data: &[u8]) -> Option<Font> {
    let blob = Blob::from(data.to_vec());
    Font::new(blob, 0)
}

/// Shape `text` with the given face and features.
///
/// The features are the ones to switch on and off; a shaper applies the
/// font's own defaults on top of them.
pub fn shape_text(font: &Font, text: &str, features: &[Feature]) -> Option<Vec<ShapedGlyph>> {
    shape_text_with_ligatures(font, text, features, &Ligatures::default())
}

/// Shape `text`, replacing the runs the ligatures name with the font's own
/// glyph for them.
///
/// The replacement is how a terminal's ligatures work: the font already drew
/// the arrow, the terminal asks for it by name and puts it where the
/// characters were. The result is one glyph where there were several, which
/// is what the grid expects.
pub fn shape_text_with_ligatures(
    font: &Font,
    text: &str,
    features: &[Feature],
    ligatures: &Ligatures,
) -> Option<Vec<ShapedGlyph>> {
    let glyphs = shape_text_plain(font, text, features)?;
    if ligatures.is_empty() || text.is_empty() {
        return Some(glyphs);
    }

    // The characters, and where each of them starts in the text.
    let mut starts: Vec<usize> = Vec::new();
    let mut chars: Vec<char> = Vec::new();
    for (offset, character) in text.char_indices() {
        starts.push(offset);
        chars.push(character);
    }

    // The runs the ligatures name.
    let mut matches: Vec<(usize, usize, u16)> = Vec::new();
    let mut index = 0;
    while index < chars.len() {
        match ligatures.at(&chars[index..]) {
            Some(ligature) => {
                let last = index + ligature.chars.len();
                let end = if last < chars.len() { starts[last] } else { text.len() };
                matches.push((starts[index], end, ligature.gid));
                index = last;
            }
            None => index += 1,
        }
    }
    if matches.is_empty() {
        return Some(glyphs);
    }

    // And the glyphs they replace.
    let mut shaped: Vec<ShapedGlyph> = Vec::new();
    let mut glyph = 0;
    let mut matched = 0;
    while glyph < glyphs.len() {
        let current = matches.get(matched).copied();
        if let Some((start, end, gid)) = current {
            if glyphs[glyph].cluster as usize == start {
                let mut advance = 0;
                while glyph < glyphs.len() && (glyphs[glyph].cluster as usize) < end {
                    advance += glyphs[glyph].x_advance;
                    glyph += 1;
                }
                shaped.push(ShapedGlyph {
                    glyph_id: gid,
                    cluster: start as u32,
                    x_advance: advance,
                    x_offset: 0,
                    y_offset: 0,
                });
                matched += 1;
                continue;
            }
        }
        shaped.push(glyphs[glyph]);
        glyph += 1;
    }

    Some(shaped)
}

/// Shape `text` with the given face and features, and nothing else.
fn shape_text_plain(font: &Font, text: &str, features: &[Feature]) -> Option<Vec<ShapedGlyph>> {
    let shaper = ShaperFont::new(font);

    let mut buffer = Buffer::new();
    // The cluster of every character is its byte offset in the text, which
    // is what tells the renderer which cell a glyph belongs to.
    buffer.push_str(text);
    buffer.set_direction(Direction::LeftToRight);
    // The script and the language, guessed from the text. A shaper plans
    // its work per script, and a buffer without one is planned for a
    // script nobody wrote in — which is a plan that finds no ligatures.
    buffer.guess_segment_properties();
    // A run of nothing but punctuation guesses the *Common* script, and the
    // font's ligature lookups are registered under its Latin ones. A
    // terminal's face is a Latin face, so a text that says nothing about
    // its script is Latin.
    if matches!(buffer.script(), None | Some(Script::COMMON) | Some(Script::UNKNOWN)) {
        buffer.set_script(Some(Script::LATIN));
    }
    // The text is the whole text: it begins where it begins and ends where
    // it ends. A shaper that is told otherwise looks for the context of a
    // paragraph, and a ligature at the edge of the buffer needs to know
    // that the edge *is* the edge.
    buffer.set_flags(BufferFlags::BEGINNING_OF_TEXT | BufferFlags::END_OF_TEXT);

    let options = ShapeOptions::new().features(features);
    harfrust::shape(&shaper, &mut buffer, options).ok()?;

    let infos = buffer.glyph_infos();
    let positions = buffer.glyph_positions();

    Some(
        infos
            .iter()
            .zip(positions.iter())
            .map(|(info, position)| ShapedGlyph {
                // OpenType glyph ids are 16-bit; a font that says otherwise
                // is broken, and the id is clamped rather than wrapped.
                glyph_id: info.glyph_id.min(u32::from(u16::MAX)) as u16,
                cluster: info.cluster,
                x_advance: position.x_advance,
                x_offset: position.x_offset,
                y_offset: position.y_offset,
            })
            .collect(),
    )
}

/// Advance width of one codepoint in font units — the cell width of a
/// monospace face.
pub fn advance_units(font: &Font, codepoint: char) -> Option<f32> {
    let mut buffer = [0u8; 4];
    let text = codepoint.encode_utf8(&mut buffer);

    shape_text(font, text, &ligature_features(false))?.first().map(|glyph| glyph.x_advance as f32)
}

/// Font units per em, i.e. the scale factor of the shaped values.
pub fn units_per_em(font: &Font) -> Option<f32> {
    let units = font.units_per_em();
    if units == 0 {
        return None;
    }
    Some(f32::from(units))
}

/// The features a terminal asks for.
///
/// `ligatures` is the application's setting, and the font's own tables decide
/// what it means. Turning them off is what keeps the font from ligating
/// behind the user's back: a terminal cell must show the character the guest
/// wrote when the setting says so.
///
/// Asking for them is not the same as leaving them alone. HarfBuzz — and
/// HarfRust with it — applies a font's *common* features by default, and
/// `calt` is not one of them: it is asked for by name. JetBrains Mono keeps
/// its ligatures in `calt`, so a font that has them and a shaper that says
/// nothing ligate nothing at all.
pub fn ligature_features(ligatures: bool) -> Vec<Feature> {
    // Four-byte OpenType tags; the array literal makes the length a compile-time
    // fact, so `Tag::new` cannot fail.
    const LIGATURES: [&[u8; 4]; 4] = [b"liga", b"clig", b"calt", b"rlig"];

    let value = if ligatures { 1 } else { 0 };
    LIGATURES.iter().map(|name| Feature::new(Tag::new(name), value, ..)).collect()
}
