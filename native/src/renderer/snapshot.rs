//! A frame as a picture.
//!
//! The session grid draws every session as a card, and the card needs the
//! terminal's own picture — not a screenshot of a Surface that only exists
//! while the session is on screen. This module turns the wire frame the
//! renderer already produces into RGBA pixels, so a session that is in the
//! background still has a preview of its last screenful.
//!
//! ## Why it is here and not in Kotlin
//!
//! The colours in the frame are already resolved, the glyphs are the same
//! face, and the rules that place them are the renderer's. Drawing the preview
//! a second time in Kotlin would be a second renderer, and the two would drift
//! — which is exactly the failure the wire format exists to prevent.
//!
//! ## What it is not
//!
//! This is not the on-screen renderer. It draws one still frame, at a size a
//! card can show, with no images, no blink phase and no overscan. It is
//! deliberately engine-free: `frame` is the flat buffer, and the result is
//! plain RGBA, so the rules can be tested without a device.

use std::collections::HashMap;

use super::wire::{
    ATTR_STRIKETHROUGH, ATTR_UNDERLINE_SHIFT, CELL_BYTES, CURSOR_VISIBLE, HEADER_BYTES, MAGIC,
};

/// The widest picture a preview can be. A card is a few hundred dp; more than
/// this is memory spent on pixels that are scaled back down.
pub const MAX_WIDTH: u32 = 720;

/// A hard ceiling on the pixels one preview may allocate.
///
/// A tall grid with a pathological `cols` must not turn a card into a
/// multi-megabyte allocation on the IO thread.
const MAX_PIXELS: u32 = 4_000_000;

/// The smallest and largest cell a preview is drawn with, in pixels.
const MIN_CELL_WIDTH: u32 = 4;
const MAX_CELL_WIDTH: u32 = 18;

/// One rendered preview: RGBA, row-major, no padding.
pub struct Snapshot {
    pub width: u32,
    pub height: u32,
    pub rgba: Vec<u8>,
}

/// One decoded cell of the wire frame.
struct Cell {
    codepoint: u32,
    foreground: u32,
    background: u32,
    attributes: u32,
    width: u8,
}

/// Render the frame in `frame` at [`MAX_WIDTH`] or `width_budget` pixels wide.
///
/// Returns `None` when the buffer is not a frame this build understands, when
/// the grid is empty, or when the picture would be larger than [`MAX_PIXELS`].
pub fn render(frame: &[u8], width_budget: u32) -> Option<Snapshot> {
    let header = Header::parse(frame)?;
    if header.columns == 0 || header.rows == 0 || header.cell_stride < CELL_BYTES as u32 {
        return None;
    }

    let budget = width_budget.clamp(1, MAX_WIDTH);
    let cell_width = (budget / u32::from(header.columns))
        .clamp(MIN_CELL_WIDTH, MAX_CELL_WIDTH);
    // A terminal cell is about twice as tall as it is wide, and a preview that
    // used square cells would letterbox the text into something that is not a
    // terminal at all.
    let cell_height = (cell_width * 2).max(MIN_CELL_WIDTH * 2);

    let width = cell_width * u32::from(header.columns);
    let height = cell_height * u32::from(header.rows);
    if width == 0 || height == 0 || width.saturating_mul(height) > MAX_PIXELS {
        return None;
    }

    // The card's own corner is the picture's background, so a grid whose rows
    // end before the viewport does not leave transparent bands.
    let mut rgba = vec![0u8; (width * height * 4) as usize];
    let origin = header.cell_at(0, 0, frame)?;
    let base = Cell::parse(frame, origin, header.cell_stride)?;
    fill(&mut rgba, base.background);

    let mut glyphs: HashMap<(u32, u32), Option<crate::font::GlyphMask>> = HashMap::new();
    let font_size = (cell_height as f32 * 0.82).round();

    for row in 0..u32::from(header.rows) {
        // The buffer carries overscan rows above the viewport. A card shows
        // the viewport, so the first drawn row is the one just after them.
        let captured = header.overscan_above.max(0) as u32 + row;
        if captured >= u32::from(header.row_count) {
            break;
        }
        for column in 0..u32::from(header.columns) {
            let at = match header.cell_at(captured, column, frame) {
                Some(at) => at,
                None => continue,
            };
            let Some(cell) = Cell::parse(frame, at, header.cell_stride) else {
                continue;
            };
            let x = column * cell_width;
            let y = row * cell_height;
            fill_rect(&mut rgba, width, x, y, cell_width, cell_height, cell.background);

            if cell.width == 0 || cell.codepoint == 0 {
                continue;
            }
            let span = if cell.width >= 2 { cell_width * 2 } else { cell_width };
            let Some(character) = char::from_u32(cell.codepoint) else {
                continue;
            };
            if character.is_control() || character == ' ' {
                continue;
            }
            let Some(glyph_id) = crate::font::glyph_id(character) else {
                continue;
            };
            let key = (glyph_id, font_size.to_bits());
            let mask = glyphs
                .entry(key)
                .or_insert_with(|| crate::font::rasterize(glyph_id, font_size));
            let Some(mask) = mask.as_ref() else {
                continue;
            };

            // The mask is positioned from the pen, so the baseline is the
            // cell's bottom minus the face's descent. Centring the glyph in
            // the cell horizontally is the same rule the on-screen renderer
            // applies to a narrow cell.
            let baseline = y as f32 + cell_height as f32 * 0.78;
            let pen_x = x as f32 + ((span as f32 - mask.width as f32) / 2.0).max(0.0);
            blend_mask(
                &mut rgba,
                width,
                height,
                mask,
                pen_x.round() as i32,
                (baseline + mask.top as f32).round() as i32,
                cell.foreground,
            );

            // A line the program drew is part of what it meant to show; a
            // preview that dropped it would disagree with the screen.
            let underline = (cell.attributes >> ATTR_UNDERLINE_SHIFT) & 0xff != 0;
            let struck = cell.attributes & ATTR_STRIKETHROUGH != 0;
            if underline {
                let line_y = (baseline + 1.5).round() as u32;
                fill_rect(&mut rgba, width, x, line_y, span, 1, cell.foreground);
            } else if struck {
                let line_y = (baseline - cell_height as f32 * 0.25).round() as u32;
                fill_rect(&mut rgba, width, x, line_y, span, 1, cell.foreground);
            }
        }
    }

    // The cursor is where the user's attention is; a card without it looks
    // like a session that has finished. A block cursor is drawn as a bar so it
    // cannot hide the character under it at this size.
    if header.cursor_flags & CURSOR_VISIBLE != 0 && header.cursor_y < header.rows {
        let x = u32::from(header.cursor_x) * cell_width;
        let y = u32::from(header.cursor_y) * cell_height;
        if x < width && y < height {
            blend_rect(
                &mut rgba,
                width,
                height,
                x,
                y,
                cell_width.max(2),
                cell_height,
                header.cursor_color,
            );
        }
    }

    Some(Snapshot {
        width,
        height,
        rgba,
    })
}

/// The parts of the wire header a preview needs.
struct Header {
    columns: u16,
    rows: u16,
    row_count: u16,
    overscan_above: i16,
    cursor_x: u16,
    cursor_y: u16,
    cursor_flags: u16,
    cursor_color: u32,
    cell_stride: u32,
}

impl Header {
    fn parse(frame: &[u8]) -> Option<Self> {
        if frame.len() < HEADER_BYTES || u32_at(frame, 0)? != MAGIC {
            return None;
        }
        let header_bytes = usize::from(u16_at(frame, 6)?);
        if header_bytes < HEADER_BYTES || header_bytes > frame.len() {
            return None;
        }
        Some(Self {
            columns: u16_at(frame, 12)?,
            rows: u16_at(frame, 14)?,
            row_count: u16_at(frame, 16)?,
            overscan_above: i16_at(frame, 18)?,
            cursor_x: u16_at(frame, 22)?,
            cursor_y: u16_at(frame, 24)?,
            cursor_flags: u16_at(frame, 28)?,
            cursor_color: u32_at(frame, 44)?,
            cell_stride: u32_at(frame, 36)?,
        })
    }

    /// The offset of a captured cell, or `None` when the frame is short.
    fn cell_at(&self, row: u32, column: u32, frame: &[u8]) -> Option<usize> {
        if row >= u32::from(self.row_count) || column >= u32::from(self.columns) {
            return None;
        }
        let header = usize::from(u16_at(frame, 6)?);
        let at = header
            + (row * u32::from(self.columns) + column) as usize * self.cell_stride as usize;
        if at + CELL_BYTES > frame.len() {
            return None;
        }
        Some(at)
    }
}

impl Cell {
    fn parse(frame: &[u8], at: usize, stride: u32) -> Option<Self> {
        if at + CELL_BYTES > frame.len() || stride < CELL_BYTES as u32 {
            return None;
        }
        Some(Self {
            codepoint: u32_at(frame, at)?,
            foreground: u32_at(frame, at + 4)?,
            background: u32_at(frame, at + 8)?,
            attributes: u32_at(frame, at + 12)?,
            width: *frame.get(at + 16)?,
        })
    }
}

/// Paint every pixel with `color`.
fn fill(rgba: &mut [u8], color: u32) {
    let pixel = color.to_le_bytes();
    for chunk in rgba.chunks_exact_mut(4) {
        chunk.copy_from_slice(&pixel);
    }
}

/// Paint an opaque rectangle, clipped to the picture.
fn fill_rect(rgba: &mut [u8], width: u32, x: u32, y: u32, w: u32, h: u32, color: u32) {
    let height = rgba.len() as u32 / 4 / width.max(1);
    let pixel = color.to_le_bytes();
    for row in y..(y + h).min(height) {
        for column in x..(x + w).min(width) {
            let at = ((row * width + column) * 4) as usize;
            rgba[at..at + 4].copy_from_slice(&pixel);
        }
    }
}

/// Blend an opaque rectangle over what is already there, at half strength.
///
/// Half strength because the cursor must not erase the glyph it sits on: at a
/// card's size a solid block is a black square where the answer was.
fn blend_rect(rgba: &mut [u8], width: u32, height: u32, x: u32, y: u32, w: u32, h: u32, color: u32) {
    for row in y..(y + h).min(height) {
        for column in x..(x + w).min(width) {
            let at = ((row * width + column) * 4) as usize;
            for channel in 0..3 {
                let source = ((color >> (channel * 8)) & 0xff) as u32;
                let behind = rgba[at + channel] as u32;
                rgba[at + channel] = ((source + behind) / 2) as u8;
            }
        }
    }
}

/// Blend one glyph's coverage mask into the picture over what is there.
fn blend_mask(
    rgba: &mut [u8],
    width: u32,
    height: u32,
    mask: &crate::font::GlyphMask,
    origin_x: i32,
    origin_y: i32,
    color: u32,
) {
    let foreground = [
        (color & 0xff) as u32,
        ((color >> 8) & 0xff) as u32,
        ((color >> 16) & 0xff) as u32,
    ];
    for mask_y in 0..mask.height as i32 {
        let y = origin_y + mask_y;
        if y < 0 || y >= height as i32 {
            continue;
        }
        for mask_x in 0..mask.width as i32 {
            let x = origin_x + mask_x;
            if x < 0 || x >= width as i32 {
                continue;
            }
            let coverage = mask.coverage[(mask_y as u32 * mask.width + mask_x as u32) as usize] as u32;
            if coverage == 0 {
                continue;
            }
            let at = ((y as u32 * width + x as u32) * 4) as usize;
            for channel in 0..3 {
                let behind = rgba[at + channel] as u32;
                let covered = (foreground[channel] * coverage + behind * (255 - coverage)) / 255;
                rgba[at + channel] = covered as u8;
            }
            rgba[at + 3] = 0xff;
        }
    }
}

fn u16_at(frame: &[u8], at: usize) -> Option<u16> {
    Some(u16::from_le_bytes(frame.get(at..at + 2)?.try_into().ok()?))
}

fn i16_at(frame: &[u8], at: usize) -> Option<i16> {
    Some(i16::from_le_bytes(frame.get(at..at + 2)?.try_into().ok()?))
}

fn u32_at(frame: &[u8], at: usize) -> Option<u32> {
    Some(u32::from_le_bytes(frame.get(at..at + 4)?.try_into().ok()?))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::renderer::wire::{
        CELL_BYTES, DIRTY_ROW_BYTES, HEADER_BYTES, VERSION,
    };

    /// A frame with `columns` × `rows` cells, every cell painted `color`.
    fn frame(columns: u16, rows: u16, color: u32) -> Vec<u8> {
        let cells = usize::from(columns) * usize::from(rows);
        let mut frame = vec![0u8; HEADER_BYTES + cells * CELL_BYTES];
        frame[0..4].copy_from_slice(&MAGIC.to_le_bytes());
        frame[4..6].copy_from_slice(&VERSION.to_le_bytes());
        frame[6..8].copy_from_slice(&(HEADER_BYTES as u16).to_le_bytes());
        frame[12..14].copy_from_slice(&columns.to_le_bytes());
        frame[14..16].copy_from_slice(&rows.to_le_bytes());
        frame[16..18].copy_from_slice(&rows.to_le_bytes());
        let cell_count = cells as u32;
        frame[32..36].copy_from_slice(&cell_count.to_le_bytes());
        frame[36..40].copy_from_slice(&(CELL_BYTES as u32).to_le_bytes());
        frame[40..44].copy_from_slice(&(cell_count * CELL_BYTES as u32).to_le_bytes());
        frame[48..48 + DIRTY_ROW_BYTES].fill(0xff);
        for cell in 0..cells {
            let at = HEADER_BYTES + cell * CELL_BYTES;
            frame[at..at + 4].copy_from_slice(&u32::from(b' ').to_le_bytes());
            frame[at + 4..at + 8].copy_from_slice(&0xffff_ffffu32.to_le_bytes());
            frame[at + 8..at + 12].copy_from_slice(&color.to_le_bytes());
            frame[at + 16] = 1;
        }
        frame
    }

    #[test]
    fn a_frame_renders_to_its_cell_colours() {
        let snapshot = render(&frame(4, 2, 0xff11_2233), 480).expect("a frame renders");
        assert_eq!(snapshot.rgba.len(), (snapshot.width * snapshot.height * 4) as usize);
        assert_eq!(&snapshot.rgba[0..4], &[0x33, 0x22, 0x11, 0xff]);
    }

    #[test]
    fn the_picture_fits_the_width_budget() {
        let snapshot = render(&frame(40, 20, 0xff00_0000), 480).unwrap();
        assert!(snapshot.width <= 480);
        // 40 columns on a 480px budget is a 12px cell, and a cell is twice as
        // tall as it is wide: twenty rows of that is the picture's height.
        assert_eq!(snapshot.width, 480);
        assert_eq!(snapshot.height, 20 * 24);
    }

    #[test]
    fn a_narrow_budget_gives_a_narrower_picture() {
        let wide = render(&frame(40, 20, 0xff00_0000), 480).unwrap();
        let narrow = render(&frame(40, 20, 0xff00_0000), 240).unwrap();
        assert!(narrow.width < wide.width);
        assert!(narrow.rgba.len() < wide.rgba.len());
    }

    #[test]
    fn junk_is_refused_rather_than_drawn() {
        assert!(render(&[], 480).is_none());
        assert!(render(&[0u8; 200], 480).is_none());
        // A header whose grid is empty has nothing to show.
        assert!(render(&frame(0, 0, 0), 480).is_none());
    }

    #[test]
    fn a_cut_short_frame_stops_at_its_last_cell() {
        let mut short = frame(4, 2, 0xff10_1010);
        short.truncate(HEADER_BYTES + CELL_BYTES);
        let snapshot = render(&short, 480).expect("a short frame still renders its rows");
        assert!(snapshot.width > 0 && snapshot.height > 0);
    }
}
