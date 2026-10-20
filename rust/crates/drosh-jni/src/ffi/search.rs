//! `native*` functions for the in-terminal search capability.
//!
//! Results are newline-separated `row:col:len` records — the same shape the
//! view already parses for URL hits, so one parser serves both.

use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong};
use jni::JNIEnv;

use crate::ffi::{fail, required_string, session_or, ErrorCode};
use drosh_session::search::SearchHit;

/// Find occurrences of `query` in the visible grid; returns `row:col:len` records.
///
/// An unknown handle or empty buffer answers a single `error:CODE` record, so a
/// bug is visible instead of silent.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_terminal_engine_core_NativeCore_nativeSearch<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: jlong,
    query: JString<'local>,
    limit: jint,
) -> JString<'local> {
    let hits = match (|| -> Result<Vec<SearchHit>, ErrorCode> {
        let session = session_or(id)?;
        let q = required_string(&mut env, &query)?;
        let limit = usize::try_from(limit).map_err(|_| ErrorCode::InvalidArgument)?;
        Ok(session.search(&q, limit))
    })() {
        Ok(hits) => hits,
        Err(code) => {
            fail(code);
            return env.new_string(format!("error:{}", code.code())).unwrap_or_default();
        }
    };

    let records: Vec<String> =
        hits.iter().map(|hit| format!("{}:{}:{}", hit.row, hit.col, hit.len)).collect();
    env.new_string(records.join("\n")).unwrap_or_default()
}
