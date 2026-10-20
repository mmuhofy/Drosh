//! Bridged sessions: an engine with no PTY, fed by the host (SSH, …), plus the
//! PTY mark tap that feeds them.

use std::io::{ErrorKind, Read};
use std::time::{Duration, Instant};

use alacritty_terminal::tty::EventedReadWrite;
use drosh_bridge::cell::CELL_BYTES;
use drosh_bridge::events::{EventQueue, SessionEvent, ShellMark};
use drosh_bridge::snapshot::state_index;
use drosh_bridge::snapshot::STATE_LEN;
use drosh_pty::{LocalPty, PtyConfig};
use drosh_session::tap::TapPty;
use drosh_session::transcript::{OutputLog, DEFAULT_MAX_LINES};
use drosh_session::{manager, GridSize, SessionError};
use std::sync::{Arc, Mutex};

const COLUMNS: usize = 20;
const ROWS: usize = 4;

fn spawn_bridged() -> u64 {
    manager::spawn_bridged(GridSize::new(COLUMNS as u16, ROWS as u16), 8, 16).expect("bridged")
}

#[test]
fn fed_text_reaches_the_grid_and_the_state() {
    let id = spawn_bridged();
    let session = manager::get(id).expect("session");

    session.feed(b"hi");
    let state = session.state();
    assert_eq!(state[state_index::COLUMNS], COLUMNS as i32);
    assert_eq!(state[state_index::CURSOR_COL], 2);

    let mut buffer = vec![0u8; COLUMNS * ROWS * CELL_BYTES];
    session.take_snapshot(&mut buffer).expect("snapshot");
    // The cell record starts with a little-endian u32 codepoint.
    assert_eq!(buffer[0], u32::from(b'h') as u8);
    assert_eq!(buffer[CELL_BYTES], u32::from(b'i') as u8);

    manager::close(id).expect("close");
}

#[test]
fn device_replies_surface_as_pty_write_events() {
    let id = spawn_bridged();
    let session = manager::get(id).expect("session");

    // Primary device attributes: the engine must answer, and with no PTY the
    // answer has to travel through the event queue to the host's transport.
    session.feed(b"\x1b[c");
    let events = session.drain_events();
    let reply = events
        .iter()
        .find_map(|event| match event {
            SessionEvent::PtyWrite { text } => Some(text.clone()),
            _ => None,
        })
        .expect("the engine answers a DA query");
    assert!(reply.starts_with("\x1b[?"), "unexpected reply: {reply:?}");

    manager::close(id).expect("close");
}

#[test]
fn write_on_a_bridged_session_is_an_error() {
    let id = spawn_bridged();
    let session = manager::get(id).expect("session");

    // Input routing belongs to the host: it owns the transport.
    assert!(matches!(session.write(b"x"), Err(SessionError::Internal(_))));

    manager::close(id).expect("close");
}

#[test]
fn resize_and_snapshot_agree_after_a_feed() {
    let id = spawn_bridged();
    let session = manager::get(id).expect("session");

    session.feed(b"before");
    session.resize(GridSize::new(30, 5), 8, 16).expect("resize");
    let state: [i32; STATE_LEN] = session.state();
    assert_eq!(state[state_index::COLUMNS], 30);
    assert_eq!(state[state_index::ROWS], 5);

    manager::close(id).expect("close");
}

#[test]
fn the_pty_tap_turns_marks_into_events() {
    let queue = Arc::new(EventQueue::new(64));
    let pty = LocalPty::spawn(&PtyConfig {
        program: "/bin/sh".to_owned(),
        args: vec![
            "-c".to_owned(),
            "printf '\\033]133;A\\007after\\033]7;file:///tmp\\007'".to_owned(),
        ],
        env: Vec::new(),
        cwd: None,
        columns: COLUMNS as u16,
        rows: ROWS as u16,
        cell_width: 8,
        cell_height: 16,
    })
    .expect("pty");

    let output = Arc::new(Mutex::new(OutputLog::new(DEFAULT_MAX_LINES)));
    let mut tap = TapPty::new(pty, queue.clone(), output.clone()).expect("tap");
    let reader = EventedReadWrite::reader(&mut tap);

    // Drain the master until the child is gone (EOF, then EIO on Linux); the
    // tap scans every chunk on the way through. The master is non-blocking, so
    // `WouldBlock` means "child has not written yet" — the deadline bounds it.
    let deadline = Instant::now() + Duration::from_secs(5);
    let mut buffer = [0u8; 512];
    loop {
        match reader.read(&mut buffer) {
            Ok(0) => break,
            Ok(_) => continue,
            Err(err) if err.kind() == ErrorKind::WouldBlock => {
                if Instant::now() > deadline {
                    break;
                }
                std::thread::sleep(Duration::from_millis(10));
            }
            Err(_) => break,
        }
    }

    let events = queue.drain();
    assert!(events.contains(&SessionEvent::ShellMark(ShellMark::PromptStart)));
    assert!(events.contains(&SessionEvent::ShellMark(ShellMark::WorkingDirectory {
        url: "file:///tmp".to_owned(),
    })));
}
