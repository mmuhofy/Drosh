//! `nativeBlockState` — where the shell is in its cycle.
//!
//! The block engine used to find the boundaries by diffing the whole transcript
//! on every keystroke. The boundaries are already known here: the OSC 133 marks
//! are tapped on the PTY path, and the session's tracker turns them into the
//! phase, the working directory and the editor target.
//!
//! The record is newline-separated `key:value` lines, the same shape the event
//! queue uses, so the Kotlin side has one parser for both.

use jni::objects::{JClass, JString};
use jni::sys::jlong;
use jni::JNIEnv;

use crate::ffi::{fail, session_or};

/// The state of the shell's cycle for one session.
///
/// An unknown session answers an empty record rather than an error string: the
/// caller is asking about a session that may have just gone away, and "no
/// state" is the honest answer for that.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_engine_core_NativeCore_nativeBlockState<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: jlong,
) -> JString<'local> {
    let record = match session_or(id) {
        Ok(session) => {
            let (phase, cwd, editor) = session.block_state();
            let mut record = format!("phase:{}", phase.code());
            if let Some(status) = phase.status() {
                record.push_str(&format!("\nstatus:{status}"));
            }
            if let Some(cwd) = cwd {
                record.push_str(&format!("\ncwd:{}", sanitize(&cwd)));
            }
            if let Some(editor) = editor {
                record.push_str(&format!("\neditor:{}", sanitize(&editor)));
            }
            record
        }
        Err(code) => {
            fail(code);
            String::new()
        }
    };

    env.new_string(record).unwrap_or_default()
}

/// A record is one line per value, so a newline in a value would break the
/// format.
fn sanitize(text: &str) -> String {
    text.replace('\n', " ")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_value_with_a_newline_stays_on_one_line() {
        assert_eq!(sanitize("/home/a\nb"), "/home/a b");
    }
}
