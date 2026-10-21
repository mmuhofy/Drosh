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
use harfrust::{Buffer, Direction, Feature, ShapeOptions, ShaperFont, Tag};

/// The parsed face, re-exported so the stack can name it without depending
/// on the shaper's own vocabulary.
pub use harfrust::font::Font;

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

/// Shape `text` with the given face.
///
/// `ligatures` is the application's setting, and the font's own tables decide
/// what it means. Turning them off is what keeps the font from ligating
/// behind the user's back: a terminal cell must show the character the guest
/// wrote when the setting says so.
pub fn shape_text(font: &Font, text: &str, ligatures: bool) -> Option<Vec<ShapedGlyph>> {
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

    let features = features(ligatures);
    let options = ShapeOptions::new().features(&features);
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

    shape_text(font, text, false)?.first().map(|glyph| glyph.x_advance as f32)
}

/// Font units per em, i.e. the scale factor of the shaped values.
pub fn units_per_em(font: &Font) -> Option<f32> {
    let units = font.units_per_em();
    if units == 0 {
        return None;
    }
    Some(f32::from(units))
}

fn features(ligatures: bool) -> Vec<Feature> {
    // Four-byte OpenType tags; the array literal makes the length a compile-time
    // fact, so `Tag::new` cannot fail.
    const LIGATURES: [&[u8; 4]; 4] = [b"liga", b"clig", b"calt", b"rlig"];

    // The value is the feature's own switch: 1 asks for it, 0 refuses it.
    //
    // Asking for them is not the same as leaving them alone. HarfBuzz — and
    // HarfRust with it — applies a font's *common* features by default, and
    // `calt` is not one of them: it is asked for by name. JetBrains Mono keeps
    // its ligatures in `calt`, so a font that has them and a shaper that says
    // nothing ligate nothing at all.
    let value = if ligatures { 1 } else { 0 };
    LIGATURES.iter().map(|name| Feature::new(Tag::new(name), value, ..)).collect()
}
