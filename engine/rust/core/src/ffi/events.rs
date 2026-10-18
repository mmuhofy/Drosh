//! `native*` functions for the event queue.
//!
//! `Wakeup` (the frame trigger) is a flag, not a queue entry: it arrives with
//! every PTY read. The rest are rare and cross as newline-separated records.

use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine as _;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jlong};
use jni::JNIEnv;

use crate::bridge::events::SessionEvent;
use crate::ffi::session_or;

/// Was there a `Wakeup` since the last call?
///
/// An unknown handle answers `false`: a stale handle must not make the render
/// loop spin.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_engine_core_NativeCore_nativePollWakeup(
    _env: JNIEnv,
    _class: JClass,
    id: jlong,
) -> jboolean {
    match session_or(id) {
        Ok(session) => u8::from(session.poll_wakeup()),
        Err(_) => 0,
    }
}

/// Drain the queued events as newline-separated records.
///
/// Frozen record format (mirrored in `NativeCore.kt` / `MainActivity.kt`):
/// `title:…` (newlines replaced by spaces), `reset_title`, `bell`, `exit`,
/// `child_exit:CODE`, `color_query:INDEX:PREFIX:bel|st`,
/// `clipboard_store:c|p:BASE64`, `clipboard_load:c|p:bel|st`,
/// `cursor_blink_change`. An unknown handle yields a single `error:CODE`
/// record, so a bug is visible instead of silent.
///
/// `clipboard_store` carries base64 because a record is one line and clipboard
/// text is arbitrary: raw text would break the record format.
#[no_mangle]
pub extern "system" fn Java_dev_drosh_engine_core_NativeCore_nativeDrainEvents<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: jlong,
) -> JString<'local> {
    let text = match session_or(id) {
        Ok(session) => {
            let records: Vec<String> = session.drain_events().iter().map(encode_event).collect();
            records.join("\n")
        }
        Err(code) => format!("error:{}", code.code()),
    };

    env.new_string(text).unwrap_or_default()
}

fn encode_event(event: &SessionEvent) -> String {
    match event {
        SessionEvent::Title(title) => format!("title:{}", sanitize(title)),
        SessionEvent::ResetTitle => "reset_title".to_owned(),
        SessionEvent::Bell => "bell".to_owned(),
        SessionEvent::Exit => "exit".to_owned(),
        SessionEvent::ChildExit(code) => format!("child_exit:{code}"),
        SessionEvent::ColorQuery { index, prefix, terminator } => {
            format!("color_query:{index}:{prefix}:{}", terminator.code())
        }
        SessionEvent::ClipboardStore { selection, text } => {
            format!(
                "clipboard_store:{}:{}",
                clipboard_code(*selection),
                BASE64.encode(text.as_bytes())
            )
        }
        SessionEvent::ClipboardLoad { selection, terminator } => {
            format!("clipboard_load:{}:{}", clipboard_code(*selection), terminator.code())
        }
        SessionEvent::CursorBlinkingChange => "cursor_blink_change".to_owned(),
        SessionEvent::ShellMark(mark) => {
            let payload = mark.payload();
            if payload.is_empty() {
                format!("shell_mark:{}", mark.code())
            } else {
                format!("shell_mark:{}:{}", mark.code(), sanitize(&payload))
            }
        }
        SessionEvent::PtyWrite { text } => {
            format!("pty_write:{}", BASE64.encode(text.as_bytes()))
        }
    }
}

/// OSC 52 names the system clipboard `c` and the selection `p` (alacritty also
/// accepts `s` on input; its replies use `p`, so this does too).
fn clipboard_code(selection: bool) -> char {
    if selection {
        'p'
    } else {
        'c'
    }
}

/// Records are newline-separated, so a title must not contain one.
fn sanitize(text: &str) -> String {
    text.replace('\n', " ")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bridge::events::Terminator;
    use crate::ffi::ErrorCode;

    #[test]
    fn records_are_line_safe_and_stable() {
        assert_eq!(encode_event(&SessionEvent::Bell), "bell");
        assert_eq!(encode_event(&SessionEvent::Exit), "exit");
        assert_eq!(encode_event(&SessionEvent::ResetTitle), "reset_title");
        assert_eq!(encode_event(&SessionEvent::CursorBlinkingChange), "cursor_blink_change");
        assert_eq!(encode_event(&SessionEvent::ChildExit(7)), "child_exit:7");
        assert_eq!(
            encode_event(&SessionEvent::ColorQuery {
                index: 11,
                prefix: "11".to_owned(),
                terminator: Terminator::Bell,
            }),
            "color_query:11:11:bel"
        );
        assert_eq!(
            encode_event(&SessionEvent::ClipboardStore {
                selection: false,
                text: "hi\nthere".to_owned(),
            }),
            // Base64, because a record is one line.
            "clipboard_store:c:aGkKdGhlcmU="
        );
        assert_eq!(
            encode_event(&SessionEvent::ClipboardLoad {
                selection: true,
                terminator: Terminator::St,
            }),
            "clipboard_load:p:st"
        );
        assert_eq!(encode_event(&SessionEvent::Title("a\nb".to_owned())), "title:a b");
    }

    #[test]
    fn shell_marks_carry_their_payload() {
        use crate::bridge::events::ShellMark;

        assert_eq!(encode_event(&SessionEvent::ShellMark(ShellMark::PromptStart)), "shell_mark:a");
        assert_eq!(
            encode_event(&SessionEvent::ShellMark(ShellMark::CommandExec)),
            "shell_mark:c"
        );
        assert_eq!(
            encode_event(&SessionEvent::ShellMark(ShellMark::CommandFinished { status: Some(2) })),
            "shell_mark:d:2"
        );
        assert_eq!(
            encode_event(&SessionEvent::ShellMark(ShellMark::CommandFinished { status: None })),
            "shell_mark:d"
        );
        assert_eq!(
            encode_event(&SessionEvent::ShellMark(ShellMark::WorkingDirectory {
                url: "file:///home/u".to_owned(),
            })),
            "shell_mark:cwd:file:///home/u"
        );
        assert_eq!(
            encode_event(&SessionEvent::ShellMark(ShellMark::EditorTarget {
                path: "/etc/hosts".to_owned(),
            })),
            "shell_mark:edt:/etc/hosts"
        );
    }

    #[test]
    fn a_pty_write_record_is_base64() {
        assert_eq!(
            encode_event(&SessionEvent::PtyWrite { text: "\x1b[?1;2c".to_owned() }),
            format!("pty_write:{}", BASE64.encode("\x1b[?1;2c".as_bytes()))
        );
    }

    #[test]
    fn error_record_carries_the_code() {
        assert_eq!(format!("error:{}", ErrorCode::InvalidHandle.code()), "error:-1");
    }
}
