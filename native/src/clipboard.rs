//! OSC 52 clipboard writes: the engine's callback, and Android's clipboard.
//!
//! ## Why a bridge
//!
//! The engine answers a clipboard write where the bytes arrive: on the thread
//! that feeds the terminal, which is the session's PTY reader. The clipboard is
//! the platform's, and the platform wants the write on the UI thread. The two
//! cannot be joined inside the callback — it is synchronous, the VT stream is
//! paused while it runs, and blocking on the UI thread's queue would stall the
//! terminal behind it. So the callback copies the text out, hands it to Kotlin,
//! and Kotlin posts the write to the main thread.
//!
//! ## Why a global
//!
//! The callback signature carries a terminal and a userdata pointer and nothing
//! else: there is no place for a JVM in it. The VM and the sink class are
//! therefore captured once — in `nativeCreate`, where a `JNIEnv` is in hand —
//! and read back by the callbacks that arrive on the reader thread later.
//!
//! ## Why it never panics
//!
//! A panic here unwinds into C and aborts the process: a stray escape sequence
//! would take the whole terminal down. Every branch is checked and every
//! failure is a log line. Returning without answering is safe by the engine's
//! own contract — an unanswered write is a denied one — so a broken bridge
//! refuses writes rather than crashing.

use std::ffi::c_void;
use std::sync::OnceLock;

use jni::objects::{GlobalRef, JObject, JValue};
use jni::JavaVM;

use crate::ghostty;

/// The Kotlin object the clipboard text is handed to.
const SINK_CLASS: &str = "dev/drosh/ClipboardSink";

/// `ClipboardSink.write(String): Boolean` — true when the write was accepted.
const SINK_METHOD: &str = "write";
const SINK_SIGNATURE: &str = "(Ljava/lang/String;)Z";

/// The JVM and the sink, captured once.
struct Bridge {
    vm: JavaVM,
    sink: GlobalRef,
}

/// Set by the first session; every clipboard write after it reads this.
static BRIDGE: OnceLock<Bridge> = OnceLock::new();

/// Capture the VM and the sink class. Idempotent, and never fatal.
pub fn install(env: &mut jni::JNIEnv) {
    if BRIDGE.get().is_some() {
        return;
    }
    match capture(env) {
        Ok(bridge) => {
            // A second session on another thread may win the race; the loser's
            // bridge is dropped and the winner is what the callbacks use.
            let _ = BRIDGE.set(bridge);
        }
        Err(error) => {
            log::warn!("drosh-native: the clipboard sink could not be captured: {error}")
        }
    }
}

fn capture(env: &mut jni::JNIEnv) -> Result<Bridge, jni::errors::Error> {
    let vm = env.get_java_vm()?;
    let class = env.find_class(SINK_CLASS)?;
    let sink = env.new_global_ref(&class)?;
    Ok(Bridge { vm, sink })
}

/// The engine's `GHOSTTY_TERMINAL_OPT_CLIPBOARD_WRITE` callback.
///
/// # Safety
/// Called by the engine during a VT write; `write` is borrowed and valid only
/// for the duration of this call.
pub unsafe extern "C" fn on_clipboard_write(
    _terminal: ghostty::GhosttyTerminal,
    _userdata: *mut c_void,
    write: *const ghostty::GhosttyClipboardWrite,
) {
    let Some(write) = write.as_ref() else {
        return;
    };

    // The struct is sized, and a field may only be read when `size` covers it.
    // The header this was built against is the whole struct, so a shorter one
    // is a version whose layout is not known here — and guessing at it is worse
    // than refusing the write.
    if write.size < std::mem::size_of::<ghostty::GhosttyClipboardWrite>() {
        deny(write);
        return;
    }

    // A write with no representations asks to *clear* the clipboard. That is
    // still a write, and it still needs the user's say-so; it is refused rather
    // than handled, because there is nothing to hand to the sink.
    let Some(text) = representation(write) else {
        deny(write);
        return;
    };

    if text.is_empty() || !deliver(&text) {
        deny(write);
        return;
    }
    allow(write);
}

/// The text of a write, preferring a `text/*` representation.
unsafe fn representation(write: &ghostty::GhosttyClipboardWrite) -> Option<String> {
    if write.contents_len == 0 || write.contents.is_null() {
        return None;
    }

    let entries = std::slice::from_raw_parts(write.contents, write.contents_len);

    // The first entry is remembered, not used: a producer that offers
    // `text/plain` alongside something else may order either way, and the text
    // is the representation a terminal's clipboard can hold.
    let mut fallback: Option<&ghostty::GhosttyClipboardContent> = None;
    for entry in entries {
        if is_text(&entry.mime) {
            if let Some(text) = text_of(&entry.data) {
                return Some(text);
            }
        }
        if fallback.is_none() {
            fallback = Some(entry);
        }
    }
    fallback.and_then(|entry| text_of(&entry.data))
}

/// True when a representation's MIME type names text.
fn is_text(mime: &ghostty::GhosttyString) -> bool {
    if mime.ptr.is_null() {
        return false;
    }
    let bytes = unsafe { std::slice::from_raw_parts(mime.ptr, mime.len) };
    bytes.eq_ignore_ascii_case(b"text/plain")
        || (bytes.len() >= b"text/".len() && bytes[..b"text/".len()].eq_ignore_ascii_case(b"text/"))
}

/// A representation's bytes as text, lossily. None when it is empty.
fn text_of(data: &ghostty::GhosttyString) -> Option<String> {
    if data.ptr.is_null() || data.len == 0 {
        return None;
    }
    let bytes = unsafe { std::slice::from_raw_parts(data.ptr, data.len) };
    Some(String::from_utf8_lossy(bytes).into_owned())
}

/// Hand the text to the Kotlin sink. True when the sink accepted it.
///
/// The sink's answer is the *policy* answer as much as the write's: it refuses
/// OSC 52 unless the user has opted in, so a `false` here is the clipboard
/// policy and not a failure to deliver. The write itself is posted to the UI
/// thread by the sink, so this returns before the clipboard is actually set —
/// the reply cannot wait, because the VT stream is paused for it.
fn deliver(text: &str) -> bool {
    let Some(bridge) = BRIDGE.get() else {
        return false;
    };

    // The callback runs on the PTY reader, which the JVM already knows; this
    // attaches only if it somehow does not, and a nested guard detaches nothing.
    let Ok(mut env) = bridge.vm.attach_current_thread() else {
        log::warn!("drosh-native: the clipboard thread could not reach the JVM");
        return false;
    };

    let Ok(java_text) = env.new_string(text) else {
        log::warn!("drosh-native: the clipboard text could not be made a Java string");
        return false;
    };
    let argument = JObject::from(java_text);

    let accepted = match env.call_static_method(
        &bridge.sink,
        SINK_METHOD,
        SINK_SIGNATURE,
        &[JValue::Object(&argument)],
    ) {
        Ok(answer) => answer.z().unwrap_or(false),
        Err(error) => {
            log::warn!("drosh-native: the clipboard sink refused the call: {error}");
            false
        }
    };

    // The local reference is released here rather than left to the reader
    // thread's frame: a program that writes the clipboard in a tight loop would
    // otherwise fill the local reference table of a single read that carries
    // many writes at once.
    let _ = env.delete_local_ref(argument);
    accepted
}

/// Answer the write as done.
unsafe fn allow(write: &ghostty::GhosttyClipboardWrite) {
    reply(write, ghostty::GHOSTTY_CLIPBOARD_WRITE_RESULT_SUCCESS);
}

/// Answer the write as refused.
unsafe fn deny(write: &ghostty::GhosttyClipboardWrite) {
    reply(write, ghostty::GHOSTTY_CLIPBOARD_WRITE_RESULT_DENIED);
}

/// Answer the request, if the engine gave a way to.
///
/// Not answering at all denies: the engine's own contract says a callback that
/// returns without replying refuses the write, and for OSC 52 the reply is
/// discarded anyway.
unsafe fn reply(
    write: &ghostty::GhosttyClipboardWrite,
    result: ghostty::GhosttyClipboardWriteResult,
) {
    let Some(answer_fn) = write.reply else {
        return;
    };
    let mut answer: ghostty::GhosttyClipboardWriteReply = std::mem::zeroed();
    answer.size = std::mem::size_of::<ghostty::GhosttyClipboardWriteReply>();
    answer.result = result;
    // Nothing is remembered. A session grant would let a program write the
    // clipboard again without asking, and that is the app's decision to make,
    // not the engine's to infer.
    answer.remember = false;
    answer_fn(write as *const _, &answer);
}
