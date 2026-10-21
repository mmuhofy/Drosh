//! Rasterisation with `swash`: outlines → alpha coverage bitmaps, plus the cell
//! metrics the renderer lays the grid out with.
//!
//! Only [`Source::Outline`] is requested, so the result is a single-channel
//! mask the renderer tints with the cell's foreground colour. Colour sources
//! (`Source::ColorBitmap`, `Source::ColorOutline`) exist in swash and are what
//! an emoji pass would use; that decision is recorded in `PLAN.md` and not
//! implemented yet.

use swash::scale::{Render, ScaleContext, Source};
use swash::FontRef;

use super::{shape, FontMetrics, GlyphBitmap};

const SOURCES: &[Source] = &[Source::Outline];

/// Cell metrics of the first face in `data`, at `px`.
///
/// The cell width is a shaped advance rather than an average: for a monospace
/// face it is the one advance every glyph has, and taking it from shaping keeps
/// the number identical to the advances the renderer positions glyphs with.
pub fn metrics(data: &[u8], px: f32) -> Option<FontMetrics> {
    let font = FontRef::from_index(data, 0)?;
    let scaled = font.metrics(&[]).scale(px);

    let shaper_font = shape::font(data)?;
    let units_per_em = shape::units_per_em(&shaper_font)?;
    let advance_units = shape::advance_units(&shaper_font, 'M')?;
    let cell_width = advance_units / units_per_em * px;

    if cell_width <= 0. || !scaled.ascent.is_finite() || !scaled.descent.is_finite() {
        return None;
    }

    Some(FontMetrics {
        cell_width,
        cell_height: scaled.ascent + scaled.descent,
        ascent: scaled.ascent,
        descent: scaled.descent,
        underline_offset: scaled.underline_offset,
    })
}

/// Rasterise `glyph_id` at `px`.
///
/// Returns `None` when the glyph has no outline at all (a space, for instance)
/// or when the font cannot produce one; the caller draws nothing in that case.
pub fn rasterize(
    context: &mut ScaleContext,
    data: &[u8],
    glyph_id: u16,
    px: f32,
    hint: bool,
) -> Option<GlyphBitmap> {
    let font = FontRef::from_index(data, 0)?;
    let mut scaler = context.builder(font).size(px).hint(hint).build();
    let image = Render::new(SOURCES).render(&mut scaler, glyph_id)?;

    let placement = image.placement;
    Some(GlyphBitmap {
        glyph_id,
        width: placement.width,
        height: placement.height,
        left: placement.left,
        top: placement.top,
        coverage: image.data,
    })
}
