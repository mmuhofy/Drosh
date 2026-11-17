//! Font shaping and glyph rasterization for the terminal.
//!
//! Inspired by: github.com/ghostty-org/ghostty — `src/font/shaper/harfbuzz.zig`
//! and `src/font/Glyph.zig` (the shaper's run iterator and the glyph mask).
//! Adapted for Drosh — dev.drosh.
//!
//! Changes: Ghostty calls HarfBuzz and FreeType through Zig. Here the same two
//! jobs are done by two pure-Rust crates — `rustybuzz` for shaping and
//! `ab_glyph` for rasterization — so the Android build needs no extra native
//! library beside the engine.
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

use ab_glyph::{Font, FontRef, GlyphId, PxScale, ScaleFont};
use rustybuzz::{Face, GlyphBuffer, GlyphInfo, GlyphPosition, UnicodeBuffer};

/// A glyph the renderer can draw: its id, its place in the run, and its size.
///
/// The positions are in font units scaled by the pixel size; the renderer
/// places the glyph on the grid using the cell its codepoint started in.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ShapedGlyph {
    /// The glyph's id in the face.
    pub glyph_id: u32,
    /// The index of the codepoint in the input this glyph starts at.
    pub cluster: u32,
    /// How far the pen advances after the glyph, in pixels.
    pub advance: f32,
    /// The glyph's offset from the pen, in pixels.
    pub offset: f32,
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
    /// Kept alive because both `face` and `font` borrow it.
    bytes: &'static [u8],
    face: Face<'static>,
}

impl FontBook {
    /// Parse a font.
    ///
    /// The bytes are leaked deliberately: both handles borrow them for the
    /// process's lifetime, and a font that is loaded once and used for every
    /// frame is exactly the case where a leak is bounded and simpler than a
    /// self-referential struct.
    pub fn load(bytes: &[u8]) -> Result<Self, FontError> {
        let owned: &'static [u8] = Box::leak(bytes.to_vec().into_boxed_slice());
        let face = Face::from_slice(owned, 0).ok_or(FontError::Face)?;
        Ok(Self {
            bytes: owned,
            face,
        })
    }

    /// The face's design size, in font units.
    pub fn units_per_em(&self) -> u16 {
        self.face.units_per_em()
    }

    /// Shape one run of text at [size_px].
    ///
    /// A run is one line of the viewport: shaping across a line break would
    /// let a ligature join two screen rows, which the grid cannot draw.
    pub fn shape(&self, text: &str, size_px: f32) -> Vec<ShapedGlyph> {
        if size_px <= 0f || text.is_empty() {
            return Vec::new();
        }
        let mut buffer = UnicodeBuffer::new();
        buffer.push_str(text);
        let glyphs: GlyphBuffer = rustybuzz::shape(&self.face, &[], buffer);
        let infos: &[GlyphInfo] = glyphs.glyph_infos();
        let positions: &[GlyphPosition] = glyphs.glyph_positions();
        let scale = size_px / self.face.units_per_em() as f32;

        let mut out = Vec::with_capacity(infos.len());
        let mut pen = 0f32;
        for (info, position) in infos.iter().zip(positions.iter()) {
            let advance = position.x_advance as f32 * scale;
            let offset = (pen + position.x_offset as f32 * scale).round();
            out.push(ShapedGlyph {
                glyph_id: info.glyph_id,
                cluster: info.cluster,
                advance,
                offset,
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
        if glyph_id > u16::MAX as u32 || size_px <= 0f {
            return None;
        }
        let font = FontRef::try_from_slice(self.bytes).ok()?;
        let size = PxScale::from(size_px.round());
        let scaled = font.as_scaled(size);
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
            let px = (x - bounds.min.x).floor() as i32;
            // ab_glyph's y grows upward; the mask's row zero is the top.
            let py = (bounds.max.y - y).floor() as i32 - 1;
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
    match book().lock() {
        Ok(mut guard) => {
            *guard = loaded;
            true
        }
        Err(poisoned) => {
            log::warn!("drosh-native: the font lock was poisoned; recovering");
            *poisoned.into_inner() = loaded;
            true
        }
    }
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
    book()
        .lock()
        .ok()?
        .as_ref()?
        .rasterize(glyph_id, size_px)
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
    const FONT_BYTES: &[u8] = include_bytes!(
        "../../../android/terminal/src/main/res/font/jetbrains_mono_nerd.ttf"
    );

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
    fn rasterizing_a_glyph_gives_a_mask() {
        let book = FontBook::load(FONT_BYTES).unwrap();
        let shaped = book.shape("A", 20f32);
        let mask = book.rasterize(shaped[0].glyph_id, 20f32).expect("an A has an outline");
        assert!(mask.width > 0 && mask.height > 0);
        assert_eq!(mask.coverage.len(), mask.width as usize * mask.height as usize);
        assert!(mask.coverage.iter().any(|byte| *byte > 0));
    }
}
