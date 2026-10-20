//! `native*` functions for the URL capability.
//!
//! The scan lives in the engine, where the grid already is: the app's Kotlin
//! overlay used to walk the transcript itself, which meant a second copy of the
//! text and a second opinion about where a URL starts.
//!
//! Results are newline-separated `row:col:len:text` records. The position
//! parts are the same shape the search capability uses, and the text is the
//! whole link — a URL the shell soft-wrapped across two rows is reported once
//! per row, and every row carries the whole link, because a tap on any of them
//! opens the whole thing.

use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong};
use jni::JNIEnv;

use crate::ffi::{fail, session_or, ErrorCode};
use drosh_session::url::UrlHit;

/// Find URLs on the visible grid; returns `row:col:len:text` records.
///
/// An unknown handle answers a single `error:CODE` record, so a bug is visible
/// instead of silent.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_core_NativeCore_nativeFindUrls<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: jlong,
    limit: jint,
) -> JString<'local> {
    let hits = match (|| -> Result<Vec<UrlHit>, ErrorCode> {
        let session = session_or(id)?;
        let limit = usize::try_from(limit).map_err(|_| ErrorCode::InvalidArgument)?;
        Ok(session.find_urls(limit))
    })() {
        Ok(hits) => hits,
        Err(code) => {
            fail(code);
            return env.new_string(format!("error:{}", code.code())).unwrap_or_default();
        }
    };

    let records: Vec<String> = hits.iter().map(format_hit).collect();
    env.new_string(records.join("\n")).unwrap_or_default()
}

/// The record shape: `row:col:len:text`.
///
/// The text is the rest of the record, not a fourth field: a URL holds colons
/// of its own (`https://example.com`), so a field split would cut the link in
/// half.
fn format_hit(hit: &UrlHit) -> String {
    format!("{}:{}:{}:{}", hit.row, hit.col, hit.len, hit.text)
}

/// The URL at a visible (row, col), or an empty string when the cell is not
/// inside one.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_core_NativeCore_nativeUrlAt<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: jlong,
    row: jint,
    col: jint,
) -> JString<'local> {
    let url = match session_or(id) {
        Ok(session) => session.url_at(row as usize, col as usize).unwrap_or_default(),
        Err(code) => {
            fail(code);
            String::new()
        }
    };

    env.new_string(url).unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_record_holds_the_position_and_the_whole_link() {
        let hit = UrlHit { row: 2, col: 10, len: 19, text: "https://example.com".to_owned() };
        assert_eq!(format_hit(&hit), "2:10:19:https://example.com");
    }

    #[test]
    fn a_link_with_colons_stays_whole() {
        // The text is the rest of the record, so the link's own colons do not
        // cut it in half.
        let hit = UrlHit { row: 0, col: 0, len: 21, text: "https://a.b/c:d".to_owned() };
        assert_eq!(format_hit(&hit), "0:0:21:https://a.b/c:d");
    }
}
