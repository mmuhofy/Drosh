//! PNG decoding for the kitty graphics protocol.
//!
//! Inspired by: github.com/ghostty-org/ghostty —
//! `example/c-vt-kitty-graphics/src/main.c` (`kitty-graphics-decode-png`).
//! Adapted for Drosh — dev.drosh.
//!
//! Changes: the example hardcodes a 1x1 red pixel for the demonstration. Here
//! the callback decodes the actual PNG with the `png` crate and converts every
//! colour type to the RGBA the engine asks for, and a panic can never unwind
//! across the C boundary.
//!
//! ## Why this exists
//!
//! `libghostty-vt` accepts a PNG from a program only when the app installs a
//! decode callback at startup (`GHOSTTY_SYS_OPT_DECODE_PNG`). Without one, a
//! `f=100` kitty image is rejected before it ever reaches the renderer — the
//! protocol is on, but the pixels have nowhere to come from.

use std::ffi::c_void;
use std::panic::{catch_unwind, AssertUnwindSafe};

use crate::ghostty;
use std::sync::Once;

use png::{BitDepth, ColorType, Decoder, Transformations};

/// The engine gives a decode callback no pixel budget of its own; the
/// allocator does that. This is a second, cheap guard against a PNG whose
/// header claims a size far larger than the bytes it carries — the allocator
/// would refuse it, but decoding it into a scratch buffer first would not.
const MAX_PIXELS: usize = 16 * 1024 * 1024;

/// Install the callback once, before any session is created.
///
/// The sys interface is process-global, so repeated installs are harmless but
/// pointless; `Once` makes the intent explicit.
pub fn install() {
    static ONCE: Once = Once::new();
    ONCE.call_once(|| {
        unsafe {
            let result = ghostty::ghostty_sys_set(
                ghostty::GHOSTTY_SYS_OPT_DECODE_PNG,
                decode_png as *const c_void,
            );
            if !ghostty::ok(result) {
                // A build of the library without PNG support reports this;
                // raw RGB/RGBA images still work.
                log::warn!("drosh-native: the engine refused the PNG decoder: {result}");
            }
        }
    });
}

/// The callback.
///
/// # Safety
///
/// Called from C with raw pointers. The data pointer is valid for `data_len`
/// bytes only for this call; the output is zeroed by the engine. Nothing here
/// retains either.
unsafe extern "C" fn decode_png(
    _userdata: *mut c_void,
    allocator: *const ghostty::GhosttyAllocator,
    data: *const u8,
    data_len: usize,
    out: *mut ghostty::GhosttySysImage,
) -> bool {
    // A panic must not unwind into C: that is undefined behaviour, and on
    // Android it takes the process down. Everything that can fail returns
    // false instead, with the engine freeing nothing on failure.
    match catch_unwind(AssertUnwindSafe(|| unsafe {
        decode(data, data_len, allocator, out)
    })) {
        Ok(ok) => ok,
        Err(_) => {
            log::warn!("drosh-native: a PNG decode panicked; the image is rejected");
            false
        }
    }
}

unsafe fn decode(
    data: *const u8,
    data_len: usize,
    allocator: *const ghostty::GhosttyAllocator,
    out: *mut ghostty::GhosttySysImage,
) -> bool {
    if data.is_null() || out.is_null() || allocator.is_null() || data_len == 0 {
        return false;
    }
    let bytes = std::slice::from_raw_parts(data, data_len);

    let mut decoder = Decoder::new(bytes);
    // Palettes and low-bit-depth greys become bytes; 16-bit samples become
    // 8-bit ones. EXPAND alone leaves a paletted image without alpha, so ALPHA
    // is added: the renderer always wants four channels.
    decoder.set_transformations(
        Transformations::EXPAND | Transformations::STRIP_16 | Transformations::ALPHA,
    );
    let mut reader = match decoder.read_info() {
        Ok(reader) => reader,
        Err(error) => {
            log::debug!("drosh-native: PNG header refused: {error}");
            return false;
        }
    };

    // Decoded rows are gathered in a Rust scratch buffer first; the engine's
    // allocator owns only the RGBA image we hand over. This keeps the buffer
    // we must free through the engine exactly the size the engine is told.
    let mut decoded = vec![0u8; reader.output_buffer_size()];
    let output = match reader.next_frame(&mut decoded) {
        Ok(output) => output,
        Err(error) => {
            log::debug!("drosh-native: PNG frame refused: {error}");
            return false;
        }
    };
    if output.buffer_size() > decoded.len() {
        return false;
    }

    let (width, height) = (output.width, output.height);
    let pixels_count = match (width as usize).checked_mul(height as usize) {
        Some(count) if count > 0 && count <= MAX_PIXELS => count,
        _ => return false,
    };
    let rgba_len = match pixels_count.checked_mul(4) {
        Some(len) => len,
        None => return false,
    };

    let colour = output.color_type;
    let depth = output.bit_depth;
    if depth != BitDepth::Eight {
        // STRIP_16 should have made this impossible; a refusal is better than
        // guessing at a pixel layout the renderer would misread.
        return false;
    }
    let samples = colour.samples();
    if decoded.len() < pixels_count * samples {
        return false;
    }

    let image = ghostty::ghostty_alloc(allocator, rgba_len);
    if image.is_null() {
        return false;
    }
    let rgba = std::slice::from_raw_parts_mut(image, rgba_len);

    // Every PNG colour type to the one layout the renderer samples: four
    // bytes per pixel, row-major from the top-left, no row padding.
    for (pixel, chunk) in rgba.chunks_exact_mut(4).enumerate() {
        let src = &decoded[pixel * samples..];
        match colour {
            ColorType::Rgba => {
                chunk.copy_from_slice(&src[..4]);
            }
            ColorType::Rgb => {
                chunk[0] = src[0];
                chunk[1] = src[1];
                chunk[2] = src[2];
                chunk[3] = 0xff;
            }
            ColorType::GrayscaleAlpha => {
                chunk[0] = src[0];
                chunk[1] = src[0];
                chunk[2] = src[0];
                chunk[3] = src[1];
            }
            ColorType::Grayscale => {
                chunk[0] = src[0];
                chunk[1] = src[0];
                chunk[2] = src[0];
                chunk[3] = 0xff;
            }
            // EXPAND turns indexed images into RGB/RGBA, so this is only
            // reachable for a malformed image. Free what we took and refuse.
            ColorType::Indexed => {
                ghostty::ghostty_free(allocator, image, rgba_len);
                return false;
            }
        }
    }

    (*out).width = width;
    (*out).height = height;
    (*out).data = image;
    (*out).data_len = rgba_len;
    true
}
