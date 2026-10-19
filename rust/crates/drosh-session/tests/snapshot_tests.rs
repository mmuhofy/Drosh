//! Grid → buffer correctness, driven through the engine directly (no PTY).

use alacritty_terminal::event::VoidListener;
use alacritty_terminal::term::{Config, Term};
use alacritty_terminal::vte::ansi::{Processor, StdSyncHandler};

use drosh_bridge::cell::CellRecord;
use drosh_bridge::cell::CELL_BYTES;
use drosh_bridge::snapshot::color_at;
use drosh_bridge::snapshot::colors_fingerprint;
use drosh_bridge::snapshot::cursor_shape;
use drosh_bridge::snapshot::damage_out_len;
use drosh_bridge::snapshot::mode;
use drosh_bridge::snapshot::state_index;
use drosh_bridge::snapshot::take_snapshot;
use drosh_bridge::snapshot::write_state;
use drosh_bridge::snapshot::SnapshotError;
use drosh_bridge::snapshot::DAMAGE_FULL;
use drosh_bridge::snapshot::STATE_LEN;
use drosh_session::GridSize;

const COLUMNS: usize = 20;
const ROWS: usize = 4;

fn new_term() -> Term<VoidListener> {
    let size = GridSize::new(COLUMNS as u16, ROWS as u16);
    Term::new(Config::default(), &size, VoidListener)
}

fn feed(term: &mut Term<VoidListener>, bytes: &[u8]) {
    // The type parameter's default does not help inference in `Processor::new()`.
    let mut parser: Processor<StdSyncHandler> = Processor::new();
    parser.advance(term, bytes);
}

fn snapshot(term: &mut Term<VoidListener>, buffer: &mut [u8], damage: &mut [i32]) -> i32 {
    let mut scratch = Vec::new();
    take_snapshot(term, buffer, damage, &mut scratch).expect("snapshot");
    damage[0]
}

fn new_buffers(columns: usize, rows: usize) -> (Vec<u8>, Vec<i32>) {
    (vec![0u8; columns * rows * CELL_BYTES], vec![0i32; damage_out_len(rows)])
}

fn record(buffer: &[u8], columns: usize, row: usize, column: usize) -> CellRecord {
    let at = (row * columns + column) * CELL_BYTES;
    CellRecord::decode(&buffer[at..at + CELL_BYTES]).expect("record")
}

#[test]
fn first_snapshot_is_full_and_encodes_the_grid() {
    let mut term = new_term();
    feed(&mut term, b"hi");

    let (mut buffer, mut damage) = new_buffers(COLUMNS, ROWS);
    assert_eq!(snapshot(&mut term, &mut buffer, &mut damage), DAMAGE_FULL);

    assert_eq!(record(&buffer, COLUMNS, 0, 0).codepoint, u32::from(b'h'));
    assert_eq!(record(&buffer, COLUMNS, 0, 1).codepoint, u32::from(b'i'));
    assert_eq!(record(&buffer, COLUMNS, 0, 2).codepoint, u32::from(b' '));
    // The last cell of the viewport is part of the snapshot, too.
    assert_eq!(record(&buffer, COLUMNS, ROWS - 1, COLUMNS - 1).codepoint, u32::from(b' '));
}

#[test]
fn partial_snapshot_copies_and_reports_the_damaged_span() {
    let mut term = new_term();
    feed(&mut term, b"hi");

    let (mut buffer, mut damage) = new_buffers(COLUMNS, ROWS);
    assert_eq!(snapshot(&mut term, &mut buffer, &mut damage), DAMAGE_FULL);

    feed(&mut term, b"XY");
    assert_eq!(snapshot(&mut term, &mut buffer, &mut damage), 1, "damage is on the first line");

    assert_eq!(damage[1], 0, "damaged row");
    assert!(damage[2] <= 2, "left edge covers the old cursor position: {}", damage[2]);
    assert!(damage[3] >= 4, "right edge covers the new cursor position: {}", damage[3]);

    assert_eq!(record(&buffer, COLUMNS, 0, 2).codepoint, u32::from(b'X'));
    assert_eq!(record(&buffer, COLUMNS, 0, 3).codepoint, u32::from(b'Y'));
}

#[test]
fn idle_terminal_only_damages_the_cursor_cell() {
    let mut term = new_term();
    feed(&mut term, b"hi");

    let (mut buffer, mut damage) = new_buffers(COLUMNS, ROWS);
    snapshot(&mut term, &mut buffer, &mut damage);

    assert_eq!(snapshot(&mut term, &mut buffer, &mut damage), 1);
    assert_eq!(damage[1], 0);
    assert_eq!(damage[2], damage[3], "a cursor is a single cell");
}

#[test]
fn resize_forces_a_full_redraw_and_reflows() {
    let mut term = new_term();
    feed(&mut term, b"hello");

    let (mut buffer, mut damage) = new_buffers(COLUMNS, ROWS);
    snapshot(&mut term, &mut buffer, &mut damage);

    term.resize(GridSize::new(30, 5));
    let (mut buffer, mut damage) = new_buffers(30, 5);

    assert_eq!(snapshot(&mut term, &mut buffer, &mut damage), DAMAGE_FULL);
    assert_eq!(record(&buffer, 30, 0, 0).codepoint, u32::from(b'h'));
    assert_eq!(record(&buffer, 30, 0, 4).codepoint, u32::from(b'o'));
}

#[test]
fn undersized_buffers_are_rejected() {
    let mut term = new_term();
    let mut scratch = Vec::new();

    let mut damage = vec![0i32; damage_out_len(ROWS)];
    let mut too_small = vec![0u8; COLUMNS * ROWS * CELL_BYTES - 1];
    assert!(matches!(
        take_snapshot(&mut term, &mut too_small, &mut damage, &mut scratch),
        Err(SnapshotError::BufferTooSmall { .. })
    ));

    let mut buffer = vec![0u8; COLUMNS * ROWS * CELL_BYTES];
    let mut too_few = vec![0i32; 1];
    assert!(matches!(
        take_snapshot(&mut term, &mut buffer, &mut too_few, &mut scratch),
        Err(SnapshotError::DamageOutTooSmall { .. })
    ));
}

#[test]
fn state_reports_size_cursor_and_offset() {
    let mut term = new_term();
    feed(&mut term, b"abc\r\ndef");

    let mut out = [0i32; STATE_LEN];
    write_state(&term, &mut out);

    assert_eq!(out[state_index::COLUMNS], COLUMNS as i32);
    assert_eq!(out[state_index::ROWS], ROWS as i32);
    assert_eq!(out[state_index::CURSOR_ROW], 1);
    assert_eq!(out[state_index::CURSOR_COL], 3);
    assert_eq!(out[state_index::CURSOR_SHAPE], cursor_shape::BLOCK);
    assert_eq!(out[state_index::DISPLAY_OFFSET], 0);
    assert_ne!(out[state_index::MODE_BITS], 0, "the default mode set is not empty");
}

#[test]
fn dectcem_hides_the_cursor() {
    // `?25l`/`?25h` is DECTCEM. The engine resolves it into the cursor shape,
    // so the renderer must not need a mode bit of its own.
    let mut term = new_term();
    let mut out = [0i32; STATE_LEN];

    feed(&mut term, b"\x1b[?25l");
    write_state(&term, &mut out);
    assert_eq!(out[state_index::CURSOR_SHAPE], cursor_shape::HIDDEN);

    feed(&mut term, b"\x1b[?25h");
    write_state(&term, &mut out);
    assert_eq!(out[state_index::CURSOR_SHAPE], cursor_shape::BLOCK);
}

#[test]
fn mouse_focus_and_scroll_modes_reach_the_wire() {
    // 1000 click, 1002 drag, 1006 SGR, 1004 focus, 1007 alternate scroll.
    let mut term = new_term();
    feed(&mut term, b"\x1b[?1000h\x1b[?1002h\x1b[?1006h\x1b[?1004h\x1b[?1007h\x1b[?25l");

    let mut out = [0i32; STATE_LEN];
    write_state(&term, &mut out);
    let bits = out[state_index::MODE_BITS] as u32;

    // The engine makes the three mouse protocols mutually exclusive, so 1002
    // replaced 1000 rather than adding to it (`set_private_mode` removes
    // `TermMode::MOUSE_MODE` first).
    assert_eq!(bits & mode::MOUSE_REPORT_CLICK, 0, "1002 replaced 1000");
    assert_ne!(bits & mode::MOUSE_DRAG, 0);
    assert_eq!(bits & mode::MOUSE_MOTION, 0, "1002 is drag tracking, not any-motion");
    assert_ne!(bits & mode::SGR_MOUSE, 0);
    assert_ne!(bits & mode::FOCUS_IN_OUT, 0);
    assert_ne!(bits & mode::ALTERNATE_SCROLL, 0);
    assert_eq!(bits & mode::SHOW_CURSOR, 0, "DECTCEM clears the mode bit");

    // 1003 switches to any-motion and drops drag in turn.
    feed(&mut term, b"\x1b[?1003h");
    write_state(&term, &mut out);
    let bits = out[state_index::MODE_BITS] as u32;
    assert_ne!(bits & mode::MOUSE_MOTION, 0);
    assert_eq!(bits & mode::MOUSE_DRAG, 0);

    // 1005 and 1006 are mutually exclusive coordinate encodings, too.
    feed(&mut term, b"\x1b[?1005h");
    write_state(&term, &mut out);
    let bits = out[state_index::MODE_BITS] as u32;
    assert_ne!(bits & mode::UTF8_MOUSE, 0);
    assert_eq!(bits & mode::SGR_MOUSE, 0);

    // Back to click tracking; the SGR bit survives because 1006 was only
    // cleared by 1005, not by 1000.
    feed(&mut term, b"\x1b[?1000h\x1b[?1006h");
    write_state(&term, &mut out);
    let bits = out[state_index::MODE_BITS] as u32;
    assert_ne!(bits & mode::MOUSE_REPORT_CLICK, 0);
    assert_ne!(bits & mode::SGR_MOUSE, 0);
    assert_eq!(bits & mode::UTF8_MOUSE, 0, "1006 replaced 1005");
    assert_eq!(bits & mode::MOUSE_MOTION, 0);
}

#[test]
fn osc_overrides_change_the_fingerprint_and_are_readable() {
    let mut term = new_term();
    let before = colors_fingerprint(term.colors());

    feed(&mut term, b"\x1b]4;1;rgb:12/34/56\x07");
    assert_eq!(color_at(term.colors(), 1), Some(0x123456));
    assert_ne!(colors_fingerprint(term.colors()), before);

    // OSC 10 addresses the foreground slot (256), not an ANSI entry.
    feed(&mut term, b"\x1b]10;rgb:ffff/ffff/ffff\x07");
    assert_eq!(color_at(term.colors(), 256), Some(0xffffff));

    // OSC 110 resets the foreground to the default palette.
    feed(&mut term, b"\x1b]110\x07");
    assert_eq!(color_at(term.colors(), 256), None);
    assert_ne!(colors_fingerprint(term.colors()), before, "ANSI 1 is still overridden");

    // OSC 104;1 resets that ANSI entry, so the table is the default again.
    feed(&mut term, b"\x1b]104;1\x07");
    assert_eq!(color_at(term.colors(), 1), None);
    assert_eq!(colors_fingerprint(term.colors()), before);
}
