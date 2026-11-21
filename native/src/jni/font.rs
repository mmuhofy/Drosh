//! The font boundary: the bytes the app ships, shaped and rasterised.
//!
//! The terminal's font work lives in `native/src/font/` and is deliberately
//! free of JNI. This file is the only place that crosses the boundary: it
//! validates the handle, calls the behaviour, and turns a failure into an
//! empty answer. No shaping logic lives here.
//!
//! ## Wire formats
//!
//! `shape` returns a buffer the caller owns and reuses:
//!
//! ```text
//! u32   glyph count
//! for each glyph:
//!   u32   glyph id
//!   u32   cluster (the codepoint's index in the run)
//!   f32   offset, in pixels
//!   f32   advance, in pixels
//! ```
//!
//! `rasterize` returns a mask:
//!
//! ```text
//! u32   width, in texels
//! u32   height, in texels
//! i32   left, in texels
//! i32   top, in texels
//! u8[]  coverage, row-major from the top-left, one byte per texel
//! ```
//!
//! Both are little-endian. A null handle or a failed call returns an empty
//! array, which the caller treats as "nothing to draw" rather than as an
//! error.

use jni::JNIEnv;

use super::{jbyteArray, new_byte_array, read_string};
use crate::font;

/// Load the font the app ships.
///
/// Returns 1 on success and 0 when the bytes are not a font. The font is a
/// process-wide singleton, the same way the engine's is: a terminal draws
/// with one face.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeFont_nativeLoad(
    mut env: JNIEnv,
    _class: jni::sys::jclass,
    data: jni::sys::jbyteArray,
) -> jni::sys::jlong {
    let bytes = match env.convert_byte_array(&data) {
        Ok(bytes) => bytes,
        Err(_) => return 0,
    };
    if font::set_font(&bytes) {
        1
    } else {
        0
    }
}

/// Shape one run of text at [size_px].
///
/// Returns the encoded glyph buffer, or an empty array on failure.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeFont_nativeShape(
    mut env: JNIEnv,
    _class: jni::sys::jclass,
    text: jni::sys::jstring,
    size_px: jni::sys::jfloat,
) -> jbyteArray {
    let text = match read_string(&mut env, &text.into()) {
        s if !s.is_empty() => s,
        _ => String::new(),
    };
    let shaped = font::shape(&text, size_px);
    if shaped.is_empty() {
        return env.new_byte_array(0).unwrap_or(std::ptr::null_mut());
    }

    let mut out = Vec::with_capacity(4 + shaped.len() * 16);
    push_u32(&mut out, shaped.len() as u32);
    for glyph in shaped {
        push_u32(&mut out, glyph.glyph_id);
        push_u32(&mut out, glyph.cluster);
        push_f32(&mut out, glyph.offset);
        push_f32(&mut out, glyph.advance);
    }
    new_byte_array(&mut env, &out)
}

/// Rasterize one glyph at [size_px] into an alpha mask.
///
/// Returns the encoded mask, or an empty array when the glyph has no outline.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_native_NativeFont_nativeRasterize(
    mut env: JNIEnv,
    _class: jni::sys::jclass,
    glyph_id: jni::sys::jint,
    size_px: jni::sys::jfloat,
) -> jbyteArray {
    match font::rasterize(glyph_id as u32, size_px) {
        Some(mask) => {
            let mut out = Vec::with_capacity(16 + mask.coverage.len());
            push_u32(&mut out, mask.width);
            push_u32(&mut out, mask.height);
            push_i32(&mut out, mask.left);
            push_i32(&mut out, mask.top);
            out.extend_from_slice(&mask.coverage);
            new_byte_array(&mut env, &out)
        }
        None => env.new_byte_array(0).unwrap_or(std::ptr::null_mut()),
    }
}

// --- wire helpers -----------------------------------------------------------

fn push_u32(out: &mut Vec<u8>, value: u32) {
    out.extend_from_slice(&value.to_le_bytes());
}

fn push_i32(out: &mut Vec<u8>, value: i32) {
    out.extend_from_slice(&value.to_le_bytes());
}

fn push_f32(out: &mut Vec<u8>, value: f32) {
    out.extend_from_slice(&value.to_le_bytes());
}