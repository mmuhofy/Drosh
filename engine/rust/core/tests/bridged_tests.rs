//! Bridged sessions: an engine with no PTY, fed by the host (SSH, …).

use drosh_core::bridge::cell::CELL_BYTES;
use drosh_core::bridge::events::{SessionEvent, ShellMark};
use drosh_core::bridge::snapshot::state_index;
use drosh_core::bridge::snapshot::STATE_LEN;
use drosh_core::session::{manager, GridSize, SessionError};

const COLUMNS: usize = 20;
const ROWS: usize = 4;

fn spawn_bridged() -> u64 {
    manager::spawn_bridged(GridSize::new(COLUMNS as u16, ROWS as u16), 8, 16).expect("bridged")
}

#[test]
fn fed_marks_reach_the_event_queue() {
    let id = spawn_bridged();
    let session = manager::get(id).expect("session");

    session.feed(b"\x1b]133;A\x07prompt \x1b]7;file:///tmp\x07");
    let events = session.drain_events();
    assert!(events.contains(&SessionEvent::ShellMark(ShellMark::PromptStart)));
    assert!(events.contains(&SessionEvent::ShellMark(ShellMark::WorkingDirectory {
        url: "file:///tmp".to_owned(),
    })));

    manager::close(id).expect("close");
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
    assert_eq!(buffer[0], u32::from(b'h') as u8);
    assert_eq!(buffer[4], u32::from(b'i') as u8);

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
