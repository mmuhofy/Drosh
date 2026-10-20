//! `nativeCellColors` — the colours of the cells in a rectangle of the
//! visible grid.
//!
//! The app draws chrome over the terminal and wants it in the terminal's own
//! colours. With the Termux renderer it could read the pixels back; with the
//! GL renderer there are no pixels to read, so the engine reports the colours
//! of the cells themselves.
//!
//! The answer is newline-separated `row:column:foreground:background`
//! records. The colours are encoded the way the cell records encode them, so
//! the Kotlin side resolves them with the palette table it already has.

use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong};
use jni::JNIEnv;

use crate::ffi::{fail, session_or, ErrorCode};
use drosh_session::cells::{CellColor, CellRegion};

/// The colours of the cells in a rectangle of the visible grid.
///
/// The rectangle is clipped to the grid by the engine: a region that hangs
/// over the edge reports the cells that are there. The rows are viewport rows
/// — row 0 is the top of the screen, whatever the viewport is scrolled to.
///
/// An unknown handle answers a single `error:CODE` record, so a bug is
/// visible instead of silent.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_core_NativeCore_nativeCellColors<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: jlong,
    top: jint,
    left: jint,
    rows: jint,
    columns: jint,
) -> JString<'local> {
    let cells = match (|| -> Result<Vec<CellColor>, ErrorCode> {
        let session = session_or(id)?;
        let region = CellRegion {
            top: usize::try_from(top).map_err(|_| ErrorCode::InvalidArgument)?,
            left: usize::try_from(left).map_err(|_| ErrorCode::InvalidArgument)?,
            rows: usize::try_from(rows).map_err(|_| ErrorCode::InvalidArgument)?,
            columns: usize::try_from(columns).map_err(|_| ErrorCode::InvalidArgument)?,
        };
        Ok(session.cell_colors(region))
    })() {
        Ok(cells) => cells,
        Err(code) => {
            fail(code);
            return env.new_string(format!("error:{}", code.code())).unwrap_or_default();
        }
    };

    let records: Vec<String> = cells.iter().map(cell_record).collect();
    env.new_string(records.join("\n")).unwrap_or_default()
}

/// One cell as a `row:column:foreground:background` record.
///
/// The colours are the engine's own 32-bit wire values, written as the
/// unsigned decimal they are: a colour with the "truecolour" tag set is
/// larger than a signed 32-bit number, and Kotlin narrows it back.
fn cell_record(cell: &CellColor) -> String {
    format!("{}:{}:{}:{}", cell.row, cell.column, cell.foreground, cell.background)
}
