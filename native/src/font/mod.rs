//! Font shaping and glyph rasterization for the terminal.
//!
//! Inspired by: github.com/ghostty-org/ghostty — `src/font/shaper/harfbuzz.zig`
//! and `src/font/shaper/feature.zig` (the shaper's run iterator and the feature
//! list).
//! Adapted for Drosh — dev.drosh.
//!
//! Changes: Ghostty calls HarfBuzz and FreeType through Zig. Here the same two
//! jobs are done by two pure-Rust crates — `harfrust` for shaping and
//! `ab_glyph` for rasterization — so the Android build needs no extra native
//! library beside the engine.
//!
//! ## Why HarfRust and not RustyBuzz
//!
//! `rustybuzz` is archived (the repository was archived on 2026-07-26 and its
//! README points at the fork). HarfRust is that fork: the same shaping
//! algorithm, kept in step with HarfBuzz (v15.1.0 at the version pinned here),
//! and the one that will still take fixes. A terminal's shaping is not a place
//! to carry a dependency nobody maintains.
//!
//! ## Why a font module at all
//!
//! Drawing one glyph per cell is enough for ASCII and most of a shell's
//! output. It is not enough for a ligature (`!=` becoming one glyph), for a
//! combining sequence that changes the base's shape, or for a script such as
//! Arabic or Devanagari where a character's shape depends on its neighbours.
//! Those are the font's decision, made by shaping a whole run of text at once,
//! and they cannot be made from a single codepoint at a time.
//!
//! This module is deliberately free of the terminal and of JNI: it takes text
//! and font bytes, and gives back glyph ids, advances and coverage masks. The
//! renderer decides where the glyphs land on the grid.

use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

use ab_glyph::{Font as _, FontRef, GlyphId, PxScale, ScaleFont};
use harfrust::{
    font::Font, shape, Buffer, ClusterLevel, Direction, Feature, ShapeOptions, ShaperFont, Tag,
};

/// The one feature a terminal asks the font for.
///
/// Ghostty's `default_features` is exactly this: standard ligatures on. The
/// font's own defaults are applied by the shaper besides it, which is what
/// makes kerning and the script's required features work.
const FEATURES: [Feature; 1] = [Feature::new(Tag::new(b"liga"), 1, ..)];

/// A glyph the renderer can draw: its id, its place in the run, and its size.
///
/// The positions are in pixels at the size the run was shaped at; the renderer
/// places the glyph on the grid using the cluster its codepoint started in.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ShapedGlyph {
    /// The glyph's id in the face.
    pub glyph_id: u32,
    /// The index of the codepoint in the input this glyph starts at.
    pub cluster: u32,
    /// The glyph's x position: the pen before it, plus its own offset.
    pub offset: f32,
    /// How far the pen advances after the glyph, in pixels.
    pub advance: f32,
}

/// An eight-bit coverage mask.
#[derive(Clone, Debug)]
pub struct GlyphMask {
    /// The mask's width in texels.
    pub width: u32,
    /// The mask's height in texels.
    pub height: u32,
    /// The mask's left edge relative to the pen, in texels.
    pub left: i32,
    /// The mask's top edge relative to the cell's top, in texels.
    pub top: i32,
    /// One byte per texel, row-major from the top-left.
    pub coverage: Vec<u8>,
}

/// A font that can shape runs and rasterize their glyphs.
pub struct FontBook {
    /// The font's bytes. Leaked on purpose: both handles below borrow them for
    /// the process's lifetime, and a font loaded once and used for every frame
    /// is exactly the case where a leak is bounded and simpler than a
    /// self-referential struct.
    bytes: &'static [u8],
    /// The shaping face, at the font's default instance.
    font: Font,
    /// The same bytes as an outline font, for rasterization.
    outlines: FontRef<'static>,
}

impl FontBook {
    /// Parse a font.
    pub fn load(bytes: &[u8]) -> Result<Self, FontError> {
        let owned: &'static [u8] = Box::leak(bytes.to_vec().into_boxed_slice());
        // `Blob::Static` borrows the slice rather than copying it, so the font
        // and the outlines are two views of the one allocation.
        let base = Font::new(owned, 0).ok_or(FontError::Face)?;
        let font = base.instance_builder().build();
        let outlines =
            FontRef::try_from_slice(owned).map_err(|_| FontError::Face)?;
        Ok(Self {
            bytes: owned,
            font,
            outlines,
        })
    }

    /// The face's design size, in font units.
    pub fn units_per_em(&self) -> u16 {
        self.font.units_per_em()
    }

    /// Shape one run of text at [size_px].
    ///
    /// A run is one line of the viewport: shaping across a line break would
    /// let a ligature join two screen rows, which the grid cannot draw.
    ///
    /// The shaper works in font units and has no size of its own, so the
    /// advances and offsets are scaled by the caller — the same manual step
    /// Ghostty does with HarfBuzz's 26.6 fixed point.
    pub fn shape(&self, text: &str, size_px: f32) -> Vec<ShapedGlyph> {
        if size_px <= 0f32 || text.is_empty() {
            return Vec::new();
        }

        let shaper = ShaperFont::new(&self.font);
        let mut buffer = Buffer::new();
        // The cluster is the codepoint's index in the run, not its byte offset:
        // the renderer uses it as the cell the glyph belongs to, and a byte
        // offset would put every glyph after a multi-byte character one cell
        // too far right.
        for (index, codepoint) in text.chars().enumerate() {
            buffer.push(codepoint as u32, index as u32);
        }
        // A terminal's grid is strictly increasing in X. Ghostty forces LTR
        // for the same reason and says so: RTL in a terminal is not drawn.
        // Setting it before the guess means the guess leaves it alone.
        buffer.set_direction(Direction::LeftToRight);
        buffer.set_cluster_level(ClusterLevel::Characters);
        buffer.guess_segment_properties();

        let options = ShapeOptions::new().features(&FEATURES);
        if let Err(error) = shape(&shaper, &mut buffer, options) {
            log::warn!("drosh-native: shaping refused a run: {error}");
            return Vec::new();
        }

        let infos = buffer.glyph_infos();
        let positions = buffer.glyph_positions();
        let scale = size_px / self.font.units_per_em() as f32;

        let mut out = Vec::with_capacity(infos.len());
        let mut pen = 0f32;
        for (info, position) in infos.iter().zip(positions.iter()) {
            let advance = position.x_advance as f32 * scale;
            let offset = (pen + position.x_offset as f32 * scale).round();
            out.push(ShapedGlyph {
                glyph_id: info.glyph_id,
                cluster: info.cluster,
                offset,
                advance,
            });
            pen += advance;
        }
        out
    }

    /// Rasterize one glyph at [size_px] into an alpha mask.
    ///
    /// The size is rounded to whole pixels: a mask rasterised at a fractional
    /// size and then drawn at that size is sampled between texels, which is a
    /// blur rather than a smaller glyph.
    pub fn rasterize(&self, glyph_id: u32, size_px: f32) -> Option<GlyphMask> {
        if glyph_id > u16::MAX as u32 || size_px <= 0f32 {
            return None;
        }
        let size = PxScale::from(size_px.round());
        let scaled = self.outlines.as_scaled(size);
        let glyph = ab_glyph::Glyph {
            id: GlyphId(glyph_id as u16),
            position: ab_glyph::point(0f32, 0f32),
            scale: size,
        };
        let outlined = scaled.outline_glyph(glyph)?;
        let bounds = outlined.px_bounds();
        let width = (bounds.max.x - bounds.min.x).ceil().max(0f32) as u32;
        let height = (bounds.max.y - bounds.min.y).ceil().max(0f32) as u32;
        if width == 0 || height == 0 || width > MAX_MASK || height > MAX_MASK {
            return None;
        }

        let mut coverage = vec![0u8; (width * height) as usize];
        outlined.draw(|x, y, alpha| {
            // ab_glyph's `draw` gives texel-space coordinates as f32; the
            // bounds are in the same units, and the conversions are explicit
            // so the mask's integer grid cannot be off by a half texel.
            let px = (x - bounds.min.x).floor() as i32;
            // ab_glyph's y grows upward; the mask's row zero is the top.
            let py = ((bounds.max.y - y).floor() as i32) - 1;
            if px >= 0 && py >= 0 && (px as u32) < width && (py as u32) < height {
                let index = (py as usize) * width as usize + px as usize;
                coverage[index] = (alpha.clamp(0f32, 1f32) * 255f32).round() as u8;
            }
        });
        Some(GlyphMask {
            width,
            height,
            left: bounds.min.x.floor() as i32,
            top: bounds.min.y.floor() as i32,
            coverage,
        })
    }
}

/// What went wrong while loading a font.
#[derive(Debug)]
pub enum FontError {
    /// The bytes are not a font the parser understands.
    Face,
}

/// The process-wide font.
///
/// A Drosh session always draws with the same face, so one book is enough.
static FONT: OnceLock<Mutex<Option<FontBook>>> = OnceLock::new();

fn book() -> &'static Mutex<Option<FontBook>> {
    FONT.get_or_init(|| Mutex::new(None))
}

/// Replace the terminal's font, or clear it with an empty byte slice.
pub fn set_font(bytes: &[u8]) -> bool {
    let loaded = if bytes.is_empty() {
        None
    } else {
        match FontBook::load(bytes) {
            Ok(book) => Some(book),
            Err(error) => {
                log::warn!("drosh-native: the terminal font could not be loaded: {error:?}");
                return false;
            }
        }
    };
    let mut guard = book().lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    *guard = loaded;
    // A new face is a new set of glyph ids; the old answers are wrong now.
    clear_cache();
    true
}

/// Whether a font has been installed.
pub fn has_font() -> bool {
    book()
        .lock()
        .map(|guard| guard.is_some())
        .unwrap_or(false)
}

/// Shape a line with the installed font.
pub fn shape(text: &str, size_px: f32) -> Vec<ShapedGlyph> {
    match book().lock() {
        Ok(guard) => guard
            .as_ref()
            .map(|font| font.shape(text, size_px))
            .unwrap_or_default(),
        Err(_) => Vec::new(),
    }
}

/// Rasterize a glyph with the installed font.
pub fn rasterize(glyph_id: u32, size_px: f32) -> Option<GlyphMask> {
    book().lock().ok()?.as_ref()?.rasterize(glyph_id, size_px)
}

/// A guard against a font whose outline would need an unreasonable mask.
const MAX_MASK: u32 = 512;

/// A small shaping cache: the same rows are drawn every frame, and shaping a
/// run is more expensive than looking up its answer.
static SHAPE_CACHE: OnceLock<Mutex<HashMap<(u32, String), Vec<ShapedGlyph>>>> = OnceLock::new();

/// Shape a line, remembering the answer until [clear_cache].
pub fn shape_cached(text: &str, size_px: f32) -> Vec<ShapedGlyph> {
    let key = (size_px.to_bits(), text.to_string());
    let cache = SHAPE_CACHE.get_or_init(|| Mutex::new(HashMap::new()));
    if let Ok(guard) = cache.lock() {
        if let Some(found) = guard.get(&key) {
            return found.clone();
        }
    }
    let shaped = shape(text, size_px);
    if let Ok(mut guard) = cache.lock() {
        // A bounded cache: a terminal that shows a lot of different lines
        // must not grow one entry per line forever.
        if guard.len() > 4096 {
            guard.clear();
        }
        guard.insert(key, shaped.clone());
    }
    shaped
}

/// Drop the shaping cache after a font or a size change.
pub fn clear_cache() {
    if let Some(cache) = SHAPE_CACHE.get() {
        if let Ok(mut guard) = cache.lock() {
            guard.clear();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // The app's bundled font, reached from the Rust crate through the shared
    // workspace. It is only embedded in the test binary, never in the APK.
    const FONT_BYTES: &[u8] =
        include_bytes!("../../../android/terminal/src/main/res/font/jetbrains_mono_nerd.ttf");

    #[test]
    fn a_font_loads_and_reports_its_design_size() {
        let book = FontBook::load(FONT_BYTES).expect("the bundled font must load");
        assert!(book.units_per_em() > 0);
    }

    #[test]
    fn shaping_a_single_character_gives_one_glyph() {
        let book = FontBook::load(FONT_BYTES).unwrap();
        let shaped = book.shape("A", 16f32);
        assert_eq!(shaped.len(), 1);
        assert_eq!(shaped[0].cluster, 0);
        assert!(shaped[0].advance > 0f32);
    }

    #[test]
    fn shaping_a_longer_run_advances_the_pen() {
        let book = FontBook::load(FONT_BYTES).unwrap();
        let shaped = book.shape("hello", 16f32);
        assert_eq!(shaped.len(), 5);
        assert!(shaped[4].offset > shaped[0].offset);
    }

    #[test]
    fn a_cluster_is_the_codepoint_index_not_the_byte_offset() {
        // `push_str` would give clusters 0, 1, 3 — the byte offsets of
        // "a", "→", "b". The renderer reads a cluster as a cell, so the
        // difference is a glyph drawn one column too far right.
        let book = FontBook::load(FONT_BYTES).unwrap();
        let shaped = book.shape("a\u{2192}b", 16f32);
        assert_eq!(shaped.len(), 3);
        let clusters: Vec<u32> = shaped.iter().map(|glyph| glyph.cluster).collect();
        assert_eq!(clusters, vec![0, 1, 2]);
    }

    #[test]
    fn rasterizing_a_glyph_gives_a_mask() {
        let book = FontBook::load(FONT_BYTES).unwrap();
        let shaped = book.shape("A", 20f32);
        let mask = book
            .rasterize(shaped[0].glyph_id, 20f32)
            .expect("an A has an outline");
        assert!(mask.width > 0 && mask.height > 0);
        assert_eq!(
            mask.coverage.len(),
            mask.width as usize * mask.height as usize
        );
        assert!(mask.coverage.iter().any(|byte| *byte > 0));
    }
}
