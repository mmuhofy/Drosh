//! The frame's images.
//!
//! A program that draws pictures — the kitty graphics protocol — puts them in
//! the terminal, and the renderer has to draw them. The engine knows where each
//! one sits and it knows the pixels; this collects both into the one buffer the
//! other side reads in a single crossing.
//!
//! The work is split out of the frame's own file because it is its own kind of
//! production: the frame carries cells and a bitmap, and the images are a
//! second, separately cached stream.

use std::ffi::c_void;

use crate::ghostty;

use super::wire::{push_i32, push_u8, push_u32, push_u64};

impl crate::renderer::frame::FrameSource {
    /// The frame's images, as one buffer.
    ///
    /// A program that draws pictures — the kitty graphics protocol — puts them
    /// in the terminal, and the renderer has to draw them. The engine knows
    /// where each one sits (the cell, the offset inside it, the part of the
    /// image to show) and it knows the pixels; this collects both into one
    /// buffer the other side can read in a single crossing.
    ///
    /// The format is the app's: a count, then per image its id, its
    /// generation, where it sits and how much of it is shown, and its pixels.
    pub fn images(&mut self) -> Vec<u8> {
        let mut iterator = std::ptr::null_mut();
        if !ghostty::ok(unsafe {
            ghostty::ghostty_render_state_image_iterator_new(std::ptr::null(), &mut iterator)
        }) {
            return Vec::new();
        }

        // The kitty graphics handle: the pixels come from there.
        let mut graphics: ghostty::GhosttyKittyGraphics = std::ptr::null_mut();
        let _ = unsafe {
            ghostty::ghostty_terminal_get(
                self.terminal,
                ghostty::GHOSTTY_TERMINAL_DATA_KITTY_GRAPHICS,
                &mut graphics as *mut _ as *mut c_void,
            )
        };

        // The iterator is a factory; the render state *populates* it with the
        // placements of the last update. Without this call the iterator is
        // empty and no picture is ever drawn.
        unsafe {
            ghostty::ghostty_render_state_get(
                self.state,
                ghostty::GHOSTTY_RENDER_STATE_DATA_IMAGE_ITERATOR,
                &mut iterator as *mut _ as *mut c_void,
            );
        }

        let mut out: Vec<u8> = Vec::new();
        let mut count: u32 = 0;

        unsafe {
            while ghostty::ghostty_render_state_image_iterator_next(iterator) {
                let mut protocol: u32 = 0;
                let mut image_id: u32 = 0;
                let mut generation: u64 = 0;
                let mut layer: u32 = 0;
                let mut z: i32 = 0;
                let mut col: i32 = 0;
                let mut row: i32 = 0;
                let mut offset_x: u32 = 0;
                let mut offset_y: u32 = 0;
                let mut width: u32 = 0;
                let mut height: u32 = 0;
                let mut src_x: u32 = 0;
                let mut src_y: u32 = 0;
                let mut src_width: u32 = 0;
                let mut src_height: u32 = 0;

                let keys = [
                    ghostty::GHOSTTY_RENDER_STATE_IMAGE_DATA_PROTOCOL,
                    ghostty::GHOSTTY_RENDER_STATE_IMAGE_DATA_IMAGE_ID,
                    ghostty::GHOSTTY_RENDER_STATE_IMAGE_DATA_IMAGE_GENERATION,
                    ghostty::GHOSTTY_RENDER_STATE_IMAGE_DATA_LAYER,
                    ghostty::GHOSTTY_RENDER_STATE_IMAGE_DATA_Z,
                    ghostty::GHOSTTY_RENDER_STATE_IMAGE_DATA_VIEWPORT_COL,
                    ghostty::GHOSTTY_RENDER_STATE_IMAGE_DATA_VIEWPORT_ROW,
                    ghostty::GHOSTTY_RENDER_STATE_IMAGE_DATA_CELL_OFFSET_X,
                    ghostty::GHOSTTY_RENDER_STATE_IMAGE_DATA_CELL_OFFSET_Y,
                    ghostty::GHOSTTY_RENDER_STATE_IMAGE_DATA_PIXEL_WIDTH,
                    ghostty::GHOSTTY_RENDER_STATE_IMAGE_DATA_PIXEL_HEIGHT,
                    ghostty::GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_X,
                    ghostty::GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_Y,
                    ghostty::GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_WIDTH,
                    ghostty::GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_HEIGHT,
                ];
                let mut values: [*mut c_void; 15] = [
                    &mut protocol as *mut u32 as *mut c_void,
                    &mut image_id as *mut u32 as *mut c_void,
                    &mut generation as *mut u64 as *mut c_void,
                    &mut layer as *mut u32 as *mut c_void,
                    &mut z as *mut i32 as *mut c_void,
                    &mut col as *mut i32 as *mut c_void,
                    &mut row as *mut i32 as *mut c_void,
                    &mut offset_x as *mut u32 as *mut c_void,
                    &mut offset_y as *mut u32 as *mut c_void,
                    &mut width as *mut u32 as *mut c_void,
                    &mut height as *mut u32 as *mut c_void,
                    &mut src_x as *mut u32 as *mut c_void,
                    &mut src_y as *mut u32 as *mut c_void,
                    &mut src_width as *mut u32 as *mut c_void,
                    &mut src_height as *mut u32 as *mut c_void,
                ];
                let mut written: usize = 0;
                if !ghostty::ok(ghostty::ghostty_render_state_image_get_multi(
                    iterator,
                    15,
                    keys.as_ptr(),
                    values.as_mut_ptr(),
                    &mut written,
                )) {
                    continue;
                }

                // Only the kitty protocol: the others are not implemented here,
                // and an image the renderer cannot draw is better left out than
                // drawn wrong.
                if protocol != ghostty::GHOSTTY_IMAGE_PROTOCOL_KITTY as u32 {
                    continue;
                }

                // The pixels.
                let image = ghostty::ghostty_kitty_graphics_image(graphics, image_id);
                if graphics.is_null() || image.is_null() {
                    continue;
                }

                let mut data_ptr: *const u8 = std::ptr::null();
                let mut data_len: usize = 0;
                let mut format: u32 = 0;
                let mut compression: u32 = 0;
                if !ghostty::ok(ghostty::ghostty_kitty_graphics_image_get_multi(
                    image,
                    5,
                    [
                        ghostty::GHOSTTY_KITTY_IMAGE_DATA_DATA_PTR,
                        ghostty::GHOSTTY_KITTY_IMAGE_DATA_DATA_LEN,
                        ghostty::GHOSTTY_KITTY_IMAGE_DATA_FORMAT,
                        ghostty::GHOSTTY_KITTY_IMAGE_DATA_COMPRESSION,
                        ghostty::GHOSTTY_KITTY_IMAGE_DATA_GENERATION,
                    ]
                    .as_ptr(),
                    [
                        &mut data_ptr as *mut *const u8 as *mut c_void,
                        &mut data_len as *mut usize as *mut c_void,
                        &mut format as *mut u32 as *mut c_void,
                        &mut compression as *mut u32 as *mut c_void,
                        &mut generation as *mut u64 as *mut c_void,
                    ]
                    .as_mut_ptr(),
                    &mut written,
                )) {
                    continue;
                }

                // A compressed image would have to be inflated here, and the
                // renderer does not do that: the kitty protocol sends its
                // pictures uncompressed unless the program asks otherwise.
                if compression != ghostty::GHOSTTY_KITTY_IMAGE_COMPRESSION_NONE as u32
                    || data_ptr.is_null()
                    || data_len == 0
                {
                    continue;
                }

                // The pixels: read once per generation, and only carried
                // when they are new.
                let cached = self.image_cache.get(&(image_id, generation)).cloned();
                let (format, pixels) = match cached {
                    Some(entry) => (entry.0, Vec::new()),
                    None => {
                        let pixels = std::slice::from_raw_parts(data_ptr, data_len).to_vec();
                        self.image_cache
                            .insert((image_id, generation), (format, pixels.clone()));
                        (format, pixels)
                    }
                };
                let has_pixels = !pixels.is_empty();

                count += 1;
                push_u32(&mut out, image_id);
                push_u64(&mut out, generation);
                // The layer and the z: the pictures are drawn in Ghostty's
                // order — some under the cell backgrounds, some between the
                // backgrounds and the text, some over it.
                push_u32(&mut out, layer);
                push_i32(&mut out, z);
                push_i32(&mut out, col);
                push_i32(&mut out, row);
                push_u32(&mut out, offset_x);
                push_u32(&mut out, offset_y);
                push_u32(&mut out, width);
                push_u32(&mut out, height);
                push_u32(&mut out, src_x);
                push_u32(&mut out, src_y);
                push_u32(&mut out, src_width);
                push_u32(&mut out, src_height);
                push_u32(&mut out, format);
                push_u32(&mut out, data_len as u32);
                push_u8(&mut out, if has_pixels { 1 } else { 0 });
                if has_pixels {
                    out.extend_from_slice(&pixels);
                }
            }
            ghostty::ghostty_render_state_image_iterator_free(iterator);
        }

        // The count, at the front.
        let mut framed: Vec<u8> = Vec::with_capacity(out.len() + 4);
        push_u32(&mut framed, count);
        framed.extend_from_slice(&out);
        framed
    }
}