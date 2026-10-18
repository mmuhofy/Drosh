//! The font stack — decision D4: `rustybuzz` shapes, `swash` rasterises.
//!
//! Three pieces, each in its own file:
//!
//! - [`shape`]: text → glyphs, advances and offsets (harfbuzz semantics).
//! - [`raster`]: glyph → alpha coverage bitmap plus its bearings, and the cell
//!   metrics the renderer lays the grid out with.
//! - [`fallback`]: what to draw for a codepoint the font has no glyph for.
//!
//! Phase B ships one font inside the APK (JetBrains Mono NL, OFL-1.1 — see
//! `android/app/src/main/assets/fonts/README.md`), so a cell has the same pixel
//! size on every device and nothing here reads `/system/fonts`. The renderer
//! only ever receives coverage masks; colour glyphs (emoji) are a recorded
//! decision, not implemented yet (`PLAN.md` §8).

pub mod fallback;
pub mod raster;
pub mod shape;

use std::fmt;

/// Which face of the stack to use.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Weight {
    Regular,
    /// Falls back to the regular face when the stack was built without one.
    Bold,
}

/// Cell metrics in pixels, as the renderer needs them.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct FontMetrics {
    /// Advance width of one cell.
    pub cell_width: f32,
    /// One grid row: ascent + descent.
    pub cell_height: f32,
    /// Baseline to the top of the em box (positive, pixels).
    pub ascent: f32,
    /// Baseline downwards (positive, pixels).
    pub descent: f32,
    /// Distance from the baseline to the underline's centre.
    pub underline_offset: f32,
}

/// One rasterised glyph: alpha coverage only, tinted by the renderer.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GlyphBitmap {
    pub glyph_id: u16,
    /// Bitmap size; `0×0` for a glyph without ink (a space, for instance).
    pub width: u32,
    pub height: u32,
    /// Where the bitmap's left edge sits relative to the pen position.
    pub left: i32,
    /// Where the bitmap's top edge sits relative to the baseline, up positive.
    pub top: i32,
    /// `width * height` alpha values, row-major from the top row.
    pub coverage: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FontError {
    /// No bytes were handed over.
    Empty,
    /// The bytes do not parse as a font (in either library's eyes).
    NotAFont,
    /// The metrics could not be produced for the requested size.
    MetricsUnavailable,
}

impl fmt::Display for FontError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Empty => write!(formatter, "no font data"),
            Self::NotAFont => write!(formatter, "the data is not a font"),
            Self::MetricsUnavailable => write!(formatter, "font metrics are unavailable"),
        }
    }
}

impl std::error::Error for FontError {}

/// The faces the renderer can ask for, plus the rasteriser's cache context.
///
/// The font bytes are owned here. `rustybuzz::Face` and `swash::FontRef` borrow
/// their data, so they are constructed per call — both only parse what they
/// need, and the render maps each codepoint once into its atlas (TODO.md, Phase
/// B), so this is not on the frame path.
pub struct FontStack {
    regular: Vec<u8>,
    bold: Option<Vec<u8>>,
    scale: swash::scale::ScaleContext,
}

impl FontStack {
    /// Build a stack from the bundled faces.
    ///
    /// A missing bold face is not an error: bold text then uses the regular
    /// face and the renderer's own emboldening (dropping it would fail the
    /// whole session over a missing file).
    pub fn from_bytes(regular: Vec<u8>, bold: Option<Vec<u8>>) -> Result<Self, FontError> {
        if regular.is_empty() {
            return Err(FontError::Empty);
        }

        let stack = Self { regular, bold, scale: swash::scale::ScaleContext::new() };

        // Fail here, once, rather than on every draw call.
        if !stack.is_usable(Weight::Regular) {
            return Err(FontError::NotAFont);
        }
        if let Some(bold) = stack.bold.as_ref() {
            if !Self::bytes_are_a_font(bold) {
                log::warn!("drosh-core: the bold face is not a font, falling back to regular");
            }
        }

        Ok(stack)
    }

    /// Bytes of the face to use for `weight` (bold falls back to regular).
    pub fn bytes(&self, weight: Weight) -> &[u8] {
        face(&self.regular, self.bold.as_deref(), weight)
    }

    /// Cell metrics at `px`, from the regular face.
    pub fn metrics(&self, px: f32) -> Result<FontMetrics, FontError> {
        raster::metrics(&self.regular, px).ok_or(FontError::MetricsUnavailable)
    }

    /// Shape `text`; ligatures are off for the shipped font either way (the NL
    /// variant carries no ligature lookups), and are also disabled explicitly
    /// so a future standard font cannot ligate behind our back.
    pub fn shape(&self, text: &str, weight: Weight) -> Option<Vec<shape::ShapedGlyph>> {
        shape::shape_text(self.bytes(weight), text, false)
    }

    /// Rasterise one glyph at `px`, hinting on (crisp stems at small sizes).
    pub fn rasterize(&mut self, glyph_id: u16, px: f32, weight: Weight) -> Option<GlyphBitmap> {
        // The fields are destructured on purpose: `self.bytes(..)` borrows all of
        // `self` immutably, which would collide with the mutable borrow of the
        // rasteriser's context.
        let Self { regular, bold, scale } = self;
        let bytes = face(regular.as_slice(), bold.as_deref(), weight);
        raster::rasterize(scale, bytes, glyph_id, px, true)
    }

    /// What to draw for `codepoint`, including the substitution policy.
    pub fn resolve(&self, codepoint: char, weight: Weight) -> fallback::Resolution {
        fallback::resolve(self.bytes(weight), codepoint)
    }

    fn is_usable(&self, weight: Weight) -> bool {
        Self::bytes_are_a_font(self.bytes(weight))
    }

    fn bytes_are_a_font(data: &[u8]) -> bool {
        swash::FontRef::from_index(data, 0).is_some()
            && rustybuzz::Face::from_slice(data, 0).is_some()
    }
}

/// Pick the face for `weight`; bold falls back to the regular face.
fn face<'a>(regular: &'a [u8], bold: Option<&'a [u8]>, weight: Weight) -> &'a [u8] {
    match weight {
        Weight::Bold => bold.unwrap_or(regular),
        Weight::Regular => regular,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_data_is_rejected() {
        assert_eq!(FontStack::from_bytes(Vec::new(), None).err(), Some(FontError::Empty));
    }

    #[test]
    fn garbage_is_rejected() {
        let garbage = vec![0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07];
        assert_eq!(FontStack::from_bytes(garbage, None).err(), Some(FontError::NotAFont));
    }

    #[test]
    fn error_messages_say_what_happened() {
        assert_eq!(FontError::Empty.to_string(), "no font data");
        assert_eq!(FontError::NotAFont.to_string(), "the data is not a font");
    }
}
