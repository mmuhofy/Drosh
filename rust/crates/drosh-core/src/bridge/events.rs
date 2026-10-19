//! Engine events → the queue Kotlin drains.
//!
//! Two kinds of events arrive from [`alacritty_terminal::event::EventListener`]:
//!
//! - Replies that only need the proxy (`PtyWrite`, text-area size) are handled
//!   on the spot: an application waiting for an answer must not wait for a
//!   Kotlin frame.
//! - Colour and clipboard queries are queued, because only the session (which
//!   owns the engine's colour table) or Kotlin (which owns the palette and the
//!   clipboard) can answer them. `Session::drain_events` answers the colour
//!   queries the engine has an override for and passes the rest on.
//! - Everything else (title, bell, exit, …) is queued and drained by the app.

use std::collections::VecDeque;
use std::os::unix::process::ExitStatusExt;
use std::process::ExitStatus;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};

use alacritty_terminal::event::{Event, EventListener, WindowSize};
use alacritty_terminal::event_loop::{EventLoopSender, Msg};
use alacritty_terminal::term::ClipboardType;
use alacritty_terminal::vte::ansi::Rgb;

/// Default queue capacity. Events are rare (title/bell/exit); the cap exists so
/// that a program spamming titles cannot grow the queue without bound.
pub const DEFAULT_QUEUE_CAPACITY: usize = 256;

/// The terminator an application used for its OSC query; the reply must use the
/// same one (`BEL` or `ST`), which is what alacritty's own formatters do.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Terminator {
    /// `\x07`
    Bell,
    /// `\x1b\\`
    St,
}

impl Terminator {
    /// The bytes, as they appear in a reply.
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Bell => "\x07",
            Self::St => "\x1b\\",
        }
    }

    /// Short name used in the event records Kotlin reads.
    pub fn code(self) -> &'static str {
        match self {
            Self::Bell => "bel",
            Self::St => "st",
        }
    }
}

/// A shell-integration mark extracted from the output stream.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ShellMark {
    /// `OSC 133 ; A` — the prompt starts.
    PromptStart,
    /// `OSC 133 ; B` — the prompt ends, command input begins.
    CommandStart,
    /// `OSC 133 ; C` — the command starts running.
    CommandExec,
    /// `OSC 133 ; D ; <status>` — the command finished. The status is absent
    /// when the shell sends a bare `D`.
    CommandFinished { status: Option<i32> },
    /// `OSC 7 ; <file url>` — the working directory.
    WorkingDirectory { url: String },
    /// `OSC 1339 ; <path>` — the editor target.
    EditorTarget { path: String },
}

impl ShellMark {
    /// Short code used in the event record Kotlin reads (`shell_mark:<code>`).
    pub fn code(&self) -> &'static str {
        match self {
            Self::PromptStart => "a",
            Self::CommandStart => "b",
            Self::CommandExec => "c",
            Self::CommandFinished { .. } => "d",
            Self::WorkingDirectory { .. } => "cwd",
            Self::EditorTarget { .. } => "edt",
        }
    }

    /// The payload part of the record; empty for the flag-like marks.
    pub fn payload(&self) -> String {
        match self {
            Self::PromptStart | Self::CommandStart | Self::CommandExec => String::new(),
            Self::CommandFinished { status } => {
                status.map_or(String::new(), |code| code.to_string())
            }
            Self::WorkingDirectory { url } => url.clone(),
            Self::EditorTarget { path } => path.clone(),
        }
    }
}

/// Low-frequency session events, in the order the engine produced them.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SessionEvent {
    /// `OSC 0`/`OSC 2`: the window title changed.
    Title(String),
    /// The title was reset to the application default.
    ResetTitle,
    /// `BEL`.
    Bell,
    /// The terminal asked to shut down (`Term::exit`).
    Exit,
    /// The child process exited; `128 + signal` when it was signalled.
    ChildExit(i32),
    /// `OSC 4`/`OSC 10`/`OSC 11`/`OSC 12` query for a colour the engine has
    /// not been told to override. Kotlin owns the default palette, so it owns
    /// the answer; `prefix` is the OSC prefix alacritty itself formatted
    /// (`4;1`, `10`, …) and is passed through unchanged.
    ColorQuery { index: i32, prefix: String, terminator: Terminator },
    /// `OSC 52` store: the application put `text` on the clipboard.
    ClipboardStore { selection: bool, text: String },
    /// `OSC 52` load: the application asked for the clipboard.
    ClipboardLoad { selection: bool, terminator: Terminator },
    /// Cursor blink state changed.
    CursorBlinkingChange,
    /// A shell-integration mark (`OSC 133/7/1339`) tapped off the PTY output
    /// before the engine's parser — see `session/shell_mark.rs`.
    ShellMark(ShellMark),
    /// An escape-sequence reply the engine produced for a session that has no
    /// PTY of its own (bridged, e.g. over SSH): device-attribute and mode
    /// replies travel to the remote host through the host application.
    PtyWrite { text: String },
}

/// Events plus the "something changed" flag the render loop polls.
#[derive(Debug)]
pub struct EventQueue {
    events: Mutex<VecDeque<SessionEvent>>,
    wakeup: AtomicBool,
    capacity: usize,
}

impl EventQueue {
    pub fn new(capacity: usize) -> Self {
        Self { events: Mutex::new(VecDeque::new()), wakeup: AtomicBool::new(false), capacity }
    }

    /// A frame's worth of change happened (`Event::Wakeup`).
    pub fn set_wakeup(&self) {
        self.wakeup.store(true, Ordering::Release);
    }

    /// Take and clear the wakeup flag.
    pub fn take_wakeup(&self) -> bool {
        self.wakeup.swap(false, Ordering::AcqRel)
    }

    /// Append an event, keeping the queue bounded.
    pub fn push(&self, event: SessionEvent) {
        let mut events = self.lock();

        if events.len() >= self.capacity {
            // Exit and ChildExit are never dropped: losing them would leave
            // Kotlin with a dead session it does not know about.
            let victim = events.iter().position(|queued| {
                !matches!(queued, SessionEvent::Exit | SessionEvent::ChildExit(_))
            });

            match victim {
                Some(index) => {
                    events.remove(index);
                }
                None => {
                    log::warn!("drosh-core: event queue full of exit events");
                    events.pop_front();
                }
            }
        }

        events.push_back(event);
    }

    /// Remove and return everything queued so far.
    pub fn drain(&self) -> Vec<SessionEvent> {
        self.lock().drain(..).collect()
    }

    fn lock(&self) -> MutexGuard<'_, VecDeque<SessionEvent>> {
        self.events.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

/// The engine's event listener.
///
/// Cloned into the [`alacritty_terminal::Term`] and into the event loop: the
/// Term needs it to report events while parsing, the loop to report
/// `Wakeup`/`ChildExit`.
#[derive(Clone)]
pub struct EventProxy {
    queue: Arc<EventQueue>,
    pty: Arc<Mutex<Option<EventLoopSender>>>,
    size: Arc<Mutex<WindowSize>>,
}

impl EventProxy {
    /// Build a proxy.
    ///
    /// `pty` is filled in after the event loop exists: the loop needs the
    /// `Term`, the `Term` needs the proxy, so the sender can only be wired up
    /// once the loop has been created.
    pub fn new(
        queue: Arc<EventQueue>,
        pty: Arc<Mutex<Option<EventLoopSender>>>,
        size: Arc<Mutex<WindowSize>>,
    ) -> Self {
        Self { queue, pty, size }
    }

    /// Write an escape-sequence reply to the PTY.
    fn write_pty(&self, text: String) {
        if text.is_empty() {
            return;
        }

        let guard = self.pty.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        match guard.as_ref() {
            Some(sender) => {
                if let Err(err) = sender.send(Msg::Input(text.into_bytes().into())) {
                    log::warn!("drosh-core: PTY reply failed: {err}");
                }
            }
            // No event loop means no PTY (a bridged session): the reply must
            // still reach the remote side, so it goes out as an event.
            None => self.queue.push(SessionEvent::PtyWrite { text }),
        }
    }

    fn window_size(&self) -> WindowSize {
        *self.size.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

impl EventListener for EventProxy {
    fn send_event(&self, event: Event) {
        match event {
            Event::Wakeup => self.queue.set_wakeup(),
            Event::Title(title) => self.queue.push(SessionEvent::Title(title)),
            Event::ResetTitle => self.queue.push(SessionEvent::ResetTitle),
            Event::Bell => self.queue.push(SessionEvent::Bell),
            Event::Exit => self.queue.push(SessionEvent::Exit),
            Event::ChildExit(status) => self.queue.push(SessionEvent::ChildExit(exit_code(status))),
            Event::PtyWrite(text) => self.write_pty(text),
            Event::TextAreaSizeRequest(format) => {
                let size = self.window_size();
                self.write_pty(format(size));
            }
            Event::ColorRequest(index, format) => {
                // alacritty's formatter is the only place that knows which OSC
                // the query came from, so the reply is probed with a black
                // colour and split into prefix + terminator. Kotlin supplies the
                // colour; the format stays alacritty's.
                match parse_color_template(&format(Rgb { r: 0, g: 0, b: 0 })) {
                    Some((prefix, terminator)) => self.queue.push(SessionEvent::ColorQuery {
                        index: index as i32,
                        prefix,
                        terminator,
                    }),
                    None => log::warn!("drosh-core: unrecognised colour reply format"),
                }
            }
            Event::ClipboardStore(clipboard, text) => {
                self.queue.push(SessionEvent::ClipboardStore {
                    selection: is_selection(clipboard),
                    text,
                });
            }
            Event::ClipboardLoad(clipboard, format) => {
                // `\x1b]52;{c};{base64}{terminator}`: probe the terminator, the
                // rest of the reply is built where the clipboard lives.
                match terminator_suffix(&format("")) {
                    Some(terminator) => self.queue.push(SessionEvent::ClipboardLoad {
                        selection: is_selection(clipboard),
                        terminator,
                    }),
                    None => log::warn!("drosh-core: unrecognised clipboard reply format"),
                }
            }
            Event::CursorBlinkingChange => self.queue.push(SessionEvent::CursorBlinkingChange),
            Event::MouseCursorDirty => {}
        }
    }
}

fn is_selection(clipboard: ClipboardType) -> bool {
    matches!(clipboard, ClipboardType::Selection)
}

/// Split alacritty's colour reply — `\x1b]{prefix};rgb:{rr}{rr}/{gg}{gg}/{bb}{bb}{terminator}`
/// — into its prefix and terminator.
pub fn parse_color_template(template: &str) -> Option<(String, Terminator)> {
    let rest = template.strip_prefix("\x1b]")?;
    let at = rest.find(";rgb:")?;
    let prefix = &rest[..at];
    let tail = &rest[at + ";rgb:".len()..];
    // Three four-digit hex components and two slashes.
    let terminator = terminator_of(tail.get(14..)?)?;
    Some((prefix.to_owned(), terminator))
}

fn terminator_of(text: &str) -> Option<Terminator> {
    match text {
        "\x07" => Some(Terminator::Bell),
        "\x1b\\" => Some(Terminator::St),
        _ => None,
    }
}

fn terminator_suffix(text: &str) -> Option<Terminator> {
    if text.ends_with("\x1b\\") {
        Some(Terminator::St)
    } else if text.ends_with('\x07') {
        Some(Terminator::Bell)
    } else {
        None
    }
}

/// Map a child's exit status to a plain code (`128 + signal` when signalled).
pub fn exit_code(status: ExitStatus) -> i32 {
    match status.code() {
        Some(code) => code,
        None => status.signal().map_or(1, |signal| 128 + signal),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn colour_templates_split_like_alacritty_formats_them() {
        assert_eq!(
            parse_color_template("\x1b]4;1;rgb:0000/0000/0000\x07"),
            Some(("4;1".to_owned(), Terminator::Bell))
        );
        assert_eq!(
            parse_color_template("\x1b]10;rgb:0000/0000/0000\x1b\\"),
            Some(("10".to_owned(), Terminator::St))
        );
        assert_eq!(parse_color_template("garbage"), None);
        // A template that lost its terminator must not be answered blindly.
        assert_eq!(parse_color_template("\x1b]11;rgb:0000/0000/0000"), None);
    }

    #[test]
    fn clipboard_terminators_are_recognised() {
        assert_eq!(terminator_suffix("\x1b]52;c;\x07"), Some(Terminator::Bell));
        assert_eq!(terminator_suffix("\x1b]52;c;\x1b\\"), Some(Terminator::St));
        assert_eq!(terminator_suffix("\x1b]52;c;"), None);
    }
}
