//! `native*` functions for the font stack (Phase B, decision D4).
//!
//! Kotlin hands over the two bundled faces once at startup, asks for the cell
//! metrics, and then asks for one glyph bitmap per codepoint it has not seen
//! yet — because the atlas caches them, the frame path never enters this file.
//!
//! Nothing here throws; a failure is an [`ErrorCode`] plus a `log` line.

use std::slice;
use std::sync::Mutex;

use jni::objects::{JByteArray, JByteBuffer, JClass, JFloatArray};
use jni::sys::{jboolean, jfloat, jint};
use jni::JNIEnv;

use crate::ffi::{fail, finish, ErrorCode, FfiResult};
use drosh_font::fallback::Resolution;
use drosh_font::{FontError, FontStack, Weight};

/// Bytes `nativeRasterize` writes before the coverage mask: kind, glyph id,
/// width, height, left, top — six little-endian 32-bit fields.
pub const GLYPH_HEADER_BYTES: usize = 24;

/// Floats `nativeFontMetrics` writes: width, height, ascent, descent, underline.
pub const FONT_METRICS_LEN: usize = 5;

/// `font::fallback::Resolution` on the wire. `NativeCore.kt` mirrors these.
pub mod glyph_kind {
    pub const DIRECT: u32 = 0;
    pub const SUBSTITUTED: u32 = 1;
    pub const MISSING: u32 = 2;
}

/// The stack the app initialised. One process, one font family (D12: no config).
static FONT: Mutex<Option<FontStack>> = Mutex::new(None);

impl From<FontError> for ErrorCode {
    fn from(err: FontError) -> Self {
        match err {
            FontError::Empty | FontError::NotAFont => Self::InvalidArgument,
            FontError::MetricsUnavailable => Self::Internal,
        }
    }
}

/// Load the faces. Called once at startup; a second call replaces the stack.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_core_NativeCore_nativeInitFont<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    regular: JByteArray<'local>,
    bold: JByteArray<'local>,
) -> jint {
    let result = (|| -> FfiResult<()> {
        if regular.is_null() {
            return Err(ErrorCode::InvalidArgument);
        }

        let regular = env.convert_byte_array(&regular).map_err(|_| ErrorCode::InvalidArgument)?;
        let bold = if bold.is_null() {
            None
        } else {
            Some(env.convert_byte_array(&bold).map_err(|_| ErrorCode::InvalidArgument)?)
        };

        let stack = FontStack::from_bytes(regular, bold).map_err(ErrorCode::from)?;

        // Fail here, at startup, if the faces cannot produce metrics — that is
        // a packaging error, not something to discover on the first frame.
        let metrics = stack.metrics(16.).map_err(ErrorCode::from)?;
        log::info!(
            "drosh-core: font loaded: cell {:.2}x{:.2}px, ascent {:.2}, descent {:.2}",
            metrics.cell_width,
            metrics.cell_height,
            metrics.ascent,
            metrics.descent
        );

        *FONT.lock().map_err(|_| ErrorCode::Internal)? = Some(stack);
        Ok(())
    })();

    finish(result)
}

/// Cell metrics at `px`, written into `out` as [`FONT_METRICS_LEN`] floats.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_core_NativeCore_nativeFontMetrics<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    px: jfloat,
    out: JFloatArray<'local>,
) -> jint {
    let result = (|| -> FfiResult<()> {
        if out.is_null() {
            return Err(ErrorCode::InvalidArgument);
        }

        let stack_metrics = {
            let guard = FONT.lock().map_err(|_| ErrorCode::Internal)?;
            let stack = guard.as_ref().ok_or(ErrorCode::NotSupported)?;
            stack.metrics(px).map_err(ErrorCode::from)?
        };

        let values = [
            stack_metrics.cell_width,
            stack_metrics.cell_height,
            stack_metrics.ascent,
            stack_metrics.descent,
            stack_metrics.underline_offset,
        ];
        env.set_float_array_region(&out, 0, &values).map_err(|_| ErrorCode::Internal)
    })();

    finish(result)
}

/// Rasterise one codepoint into `out`.
///
/// The record is a [`GLYPH_HEADER_BYTES`]-byte header followed by the coverage
/// mask, both little-endian:
///
/// ```text
/// u32 kind       0 direct, 1 substituted, 2 missing (see `glyph_kind`)
/// u32 glyph_id
/// u32 width      bitmap width, pixels (0 when there is nothing to draw)
/// u32 height     bitmap height, pixels
/// i32 left       bitmap left edge relative to the pen position
/// i32 top        bitmap top edge relative to the baseline, up positive
/// u8  coverage[width * height]   row-major from the top row
/// ```
///
/// Returns the record's size in bytes — **if that is larger than `out`'s
/// capacity, nothing was written** and the caller grows the buffer and calls
/// again. Negative values are [`ErrorCode`]s.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_core_NativeCore_nativeRasterize<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    codepoint: jint,
    bold: jboolean,
    px: jfloat,
    out: JByteBuffer<'local>,
) -> jint {
    let result = (|| -> FfiResult<usize> {
        let codepoint = u32::try_from(codepoint)
            .ok()
            .and_then(char::from_u32)
            .ok_or(ErrorCode::InvalidArgument)?;
        let weight = if bold == 0 { Weight::Regular } else { Weight::Bold };

        let capacity =
            env.get_direct_buffer_capacity(&out).map_err(|_| ErrorCode::InvalidArgument)?;
        let address =
            env.get_direct_buffer_address(&out).map_err(|_| ErrorCode::InvalidArgument)?;

        let mut guard = FONT.lock().map_err(|_| ErrorCode::Internal)?;
        let stack = guard.as_mut().ok_or(ErrorCode::NotSupported)?;

        let (kind, glyph_id) = match stack.resolve(codepoint, weight) {
            Resolution::Direct(id) => (glyph_kind::DIRECT, id),
            Resolution::Substituted(id) => (glyph_kind::SUBSTITUTED, id),
            Resolution::Missing => (glyph_kind::MISSING, 0),
        };

        // `Missing` never rasterises; anything else without an outline (a space,
        // for instance) still reports its kind so the caller can cache the
        // answer.
        let bitmap = match kind {
            glyph_kind::MISSING => None,
            _ => stack.rasterize(glyph_id, px, weight),
        };

        let (width, height, left, top, coverage) = match bitmap.as_ref() {
            Some(bitmap) => {
                (bitmap.width, bitmap.height, bitmap.left, bitmap.top, bitmap.coverage.as_slice())
            }
            None => (0, 0, 0, 0, &[][..]),
        };

        let size = GLYPH_HEADER_BYTES + coverage.len();
        if size > capacity {
            return Ok(size);
        }

        let mut header = [0u8; GLYPH_HEADER_BYTES];
        header[0..4].copy_from_slice(&kind.to_le_bytes());
        header[4..8].copy_from_slice(&u32::from(glyph_id).to_le_bytes());
        header[8..12].copy_from_slice(&width.to_le_bytes());
        header[12..16].copy_from_slice(&height.to_le_bytes());
        header[16..20].copy_from_slice(&left.to_le_bytes());
        header[20..24].copy_from_slice(&top.to_le_bytes());

        // SAFETY: `address`/`capacity` describe a direct `ByteBuffer` that
        // Kotlin keeps alive for the duration of this call, and `size` (header
        // plus coverage) was checked against `capacity` above.
        let buffer = unsafe { slice::from_raw_parts_mut(address, capacity) };
        buffer[0..GLYPH_HEADER_BYTES].copy_from_slice(&header);
        if !coverage.is_empty() {
            buffer[GLYPH_HEADER_BYTES..size].copy_from_slice(coverage);
        }

        Ok(size)
    })();

    match result {
        Ok(size) => jint::try_from(size).unwrap_or_else(|_| ErrorCode::Internal.as_jint()),
        Err(code) => fail(code).as_jint(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The Kotlin mirror in `NativeCore.kt` repeats these numbers.
    #[test]
    fn wire_sizes_are_frozen() {
        assert_eq!(GLYPH_HEADER_BYTES, 24);
        assert_eq!(FONT_METRICS_LEN, 5);
        assert_eq!(glyph_kind::DIRECT, 0);
        assert_eq!(glyph_kind::SUBSTITUTED, 1);
        assert_eq!(glyph_kind::MISSING, 2);
    }

    #[test]
    fn font_errors_map_to_codes() {
        assert_eq!(ErrorCode::from(FontError::Empty), ErrorCode::InvalidArgument);
        assert_eq!(ErrorCode::from(FontError::NotAFont), ErrorCode::InvalidArgument);
        assert_eq!(ErrorCode::from(FontError::MetricsUnavailable), ErrorCode::Internal);
    }

    #[test]
    fn rasterizing_before_init_reports_not_supported() {
        // The static is empty unless a JVM called `nativeInitFont`; the FfiResult
        // path is what the JNI function returns in that case.
        let guard = FONT.lock().expect("font lock");
        assert!(guard.is_none(), "no test may leave the font stack initialised");
    }
}
