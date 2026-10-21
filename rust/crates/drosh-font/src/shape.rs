//! Shaping with `rustybuzz` (the harfbuzz port), on the bundled font.
//!
//! The API is harfbuzz's: build a [`UnicodeBuffer`], hand it to [`shape`]
//! together with the face and the features to switch off, then read glyph ids,
//! clusters and positions back out.

use rustybuzz::ttf_parser::Tag;
use rustybuzz::{shape, Direction, Face, Feature, UnicodeBuffer};

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

/// Shape `text` with the given face's bytes.
///
/// `ligatures = false` disables `liga`, `clig`, `calt` and `rlig`: a terminal
/// cell must keep the character the guest wrote when the setting says so. The
/// shipped face is the standard JetBrains Mono, whose ligatures live in
/// `calt` — a feature the shaper has to ask for by name, because it is not
/// one of the common features a shaper applies on its own.
pub fn shape_text(data: &[u8], text: &str, ligatures: bool) -> Option<Vec<ShapedGlyph>> {
    let face = Face::from_slice(data, 0)?;

    let mut buffer = UnicodeBuffer::new();
    buffer.push_str(text);
    buffer.set_direction(Direction::LeftToRight);

    let features = features(ligatures);
    let output = shape(&face, &features, buffer);

    let infos = output.glyph_infos();
    let positions = output.glyph_positions();

    Some(
        infos
            .iter()
            .zip(positions)
            .map(|(info, position)| ShapedGlyph {
                // rustybuzz keeps the id in a `u32` field but guarantees it fits
                // a `u16` (OpenType glyph ids are 16-bit).
                glyph_id: info.glyph_id as u16,
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
pub fn advance_units(data: &[u8], codepoint: char) -> Option<f32> {
    let mut buffer = [0u8; 4];
    let text = codepoint.encode_utf8(&mut buffer);

    shape_text(data, text, false)?.first().map(|glyph| glyph.x_advance as f32)
}

/// Font units per em, i.e. the scale factor of the shaped values.
pub fn units_per_em(data: &[u8]) -> Option<f32> {
    let face = Face::from_slice(data, 0)?;
    let units = face.units_per_em();
    if units <= 0 {
        return None;
    }
    Some(units as f32)
}

fn features(ligatures: bool) -> Vec<Feature> {
    // Four-byte OpenType tags; the array literal makes the length a compile-time
    // fact, so `Tag::from_bytes` cannot fail.
    const LIGATURES: [&[u8; 4]; 4] = [b"liga", b"clig", b"calt", b"rlig"];

    // The value is the feature's own switch: 1 asks for it, 0 refuses it.
    //
    // Asking for them is not the same as leaving them alone. HarfBuzz — and
    // rustybuzz with it — applies a font's *common* features by default, and
    // `calt` is not one of them: it is asked for by name. JetBrains Mono keeps
    // its ligatures in `calt`, so a font that has them and a shaper that says
    // nothing ligate nothing at all.
    let value = if ligatures { 1 } else { 0 };
    LIGATURES.iter().map(|name| Feature::new(Tag::from_bytes(name), value, ..)).collect()
}
