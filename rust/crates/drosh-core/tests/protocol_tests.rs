//! Protocol behaviour the bridge depends on, driven through `Term` directly
//! (no PTY): colour queries, OSC 52, and the modes the input layer reads.
//!
//! These pin the engine contract, not our code: if an upgrade changes the reply
//! format or stops emitting an event, this file fails before the device does.

use std::sync::{Arc, Mutex};

use alacritty_terminal::event::{Event, EventListener};
use alacritty_terminal::term::{ClipboardType, Config, Osc52, Term};
use alacritty_terminal::vte::ansi::{Processor, Rgb, StdSyncHandler};

use drosh_core::bridge::events::{parse_color_template, Terminator};
use drosh_core::session::GridSize;

/// Keeps every event the engine sends, so a test can look at them.
#[derive(Clone, Default)]
struct Capture(Arc<Mutex<Vec<Event>>>);

impl EventListener for Capture {
    fn send_event(&self, event: Event) {
        self.0.lock().expect("capture lock").push(event);
    }
}

impl Capture {
    fn take(&self) -> Vec<Event> {
        self.0.lock().expect("capture lock").drain(..).collect()
    }
}

fn new_term() -> (Term<Capture>, Capture) {
    let capture = Capture::default();
    // Paste is enabled so the load path can be observed at all; the default is
    // `OnlyCopy` (see `Session::spawn`).
    let config = Config { osc52: Osc52::CopyPaste, ..Config::default() };
    let term = Term::new(config, &GridSize::new(20, 4), capture.clone());
    (term, capture)
}

fn feed(term: &mut Term<Capture>, bytes: &[u8]) {
    let mut parser: Processor<StdSyncHandler> = Processor::new();
    parser.advance(term, bytes);
}

#[test]
fn osc52_store_decodes_the_payload() {
    let (mut term, capture) = new_term();
    feed(&mut term, b"\x1b]52;c;aGVsbG8=\x07");

    let events = capture.take();
    assert_eq!(events.len(), 1);
    match &events[0] {
        Event::ClipboardStore(ClipboardType::Clipboard, text) => assert_eq!(text, "hello"),
        _ => panic!("expected a clipboard store event"),
    }
}

#[test]
fn osc52_load_formats_the_reply_like_alacritty() {
    let (mut term, capture) = new_term();
    feed(&mut term, b"\x1b]52;c;?\x07");

    let format = capture
        .take()
        .into_iter()
        .find_map(|event| match event {
            Event::ClipboardLoad(ClipboardType::Clipboard, format) => Some(format),
            _ => None,
        })
        .expect("clipboard load event");

    assert_eq!(format("hello"), "\x1b]52;c;aGVsbG8=\x07");
}

#[test]
fn colour_queries_carry_the_prefix_the_reply_needs() {
    let (mut term, capture) = new_term();

    feed(&mut term, b"\x1b]11;?\x07");
    // The event carries the colour *index*, not the OSC number: vte maps 10 to
    // `NamedColor::Foreground` (256), 11 to `Background` (257), 12 to `Cursor`
    // (258). The "11" only lives in the reply prefix.
    let background = capture
        .take()
        .into_iter()
        .find_map(|event| match event {
            Event::ColorRequest(257, format) => Some(format),
            _ => None,
        })
        .expect("background colour request");

    let reply = background(Rgb { r: 0x18, g: 0x18, b: 0x18 });
    assert_eq!(reply, "\x1b]11;rgb:1818/1818/1818\x07");
    // The proxy's split must recover exactly what the reply formatter needs.
    assert_eq!(parse_color_template(&reply), Some(("11".to_owned(), Terminator::Bell)));

    // OSC 4 addresses a palette entry and its prefix carries the index.
    feed(&mut term, b"\x1b]4;1;?\x07");
    let palette = capture
        .take()
        .into_iter()
        .find_map(|event| match event {
            Event::ColorRequest(1, format) => Some(format),
            _ => None,
        })
        .expect("palette colour request");

    let reply = palette(Rgb { r: 0xac, g: 0x42, b: 0x42 });
    assert_eq!(parse_color_template(&reply), Some(("4;1".to_owned(), Terminator::Bell)));
}
