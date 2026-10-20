//! Session end-to-end: a real PTY, a real child process, the real event loop.

use std::time::{Duration, Instant};

use alacritty_terminal::grid::Dimensions;

use drosh_bridge::events::SessionEvent;
use drosh_session::manager;
use drosh_session::{GridSize, Session, SessionConfig, SessionError};

fn shell_config(program: &str, args: &[&str]) -> SessionConfig {
    SessionConfig {
        size: GridSize::new(80, 24),
        cell_width: 8,
        cell_height: 16,
        program: program.to_owned(),
        args: args.iter().map(|arg| (*arg).to_owned()).collect(),
        env: vec![
            ("PATH".to_owned(), "/usr/local/bin:/usr/bin:/bin".to_owned()),
            ("HOME".to_owned(), "/tmp".to_owned()),
        ],
        cwd: None,
    }
}

/// The visible grid as text, one line per row.
fn grid_text(session: &Session) -> String {
    session.with_term(|term| {
        let content = term.renderable_content();
        let mut text = String::new();
        let mut previous_line: Option<i32> = None;

        for cell in content.display_iter {
            let line = cell.point.line.0;
            match previous_line {
                Some(previous) if previous == line => {}
                Some(_) => text.push('\n'),
                None => {}
            }
            previous_line = Some(line);
            text.push(cell.c);
        }

        text
    })
}

/// Poll `probe` until it answers, up to a generous timeout.
fn wait_for<T>(mut probe: impl FnMut() -> Option<T>) -> Option<T> {
    let deadline = Instant::now() + Duration::from_secs(15);

    loop {
        if let Some(value) = probe() {
            return Some(value);
        }
        if Instant::now() >= deadline {
            return None;
        }
        std::thread::sleep(Duration::from_millis(20));
    }
}

#[test]
fn shell_runs_a_command_and_the_grid_shows_the_output() {
    let session = Session::spawn(1, &shell_config("/bin/sh", &[])).expect("spawn a shell");

    // `6*7` appears in the echoed input; `42` can only come from the command's
    // output.
    session.write(b"echo $((6*7))\n").expect("write to the pty");

    let seen = wait_for(|| grid_text(&session).contains("42").then_some(()));
    assert!(seen.is_some(), "grid never showed 42:\n{}", grid_text(&session));

    session.shutdown();
}

#[test]
fn child_exit_is_reported_with_its_code() {
    let session =
        Session::spawn(2, &shell_config("/bin/sh", &["-c", "exit 7"])).expect("spawn a shell");

    let code = wait_for(|| {
        session.drain_events().into_iter().find_map(|event| match event {
            SessionEvent::ChildExit(code) => Some(code),
            _ => None,
        })
    });

    assert_eq!(code, Some(7));
    session.shutdown();
}

#[test]
fn a_missing_program_fails_to_spawn() {
    let config = shell_config("/nonexistent/drosh-program", &[]);

    let error = match Session::spawn(3, &config) {
        Ok(_) => panic!("spawning a missing program must fail"),
        Err(error) => error,
    };

    assert!(matches!(error, SessionError::SpawnFailed(_)), "unexpected error: {error}");
}

#[test]
fn resize_reaches_the_engine_and_the_pty() {
    let session = Session::spawn(4, &shell_config("/bin/sh", &[])).expect("spawn a shell");

    session.resize(GridSize::new(100, 30), 9, 18).expect("resize");

    let (columns, rows) = session.with_term(|term| (term.columns(), term.screen_lines()));
    assert_eq!((columns, rows), (100, 30));

    // The PTY itself must have changed size: the shell reports it back.
    session.write(b"stty size\n").expect("write to the pty");
    let seen = wait_for(|| grid_text(&session).contains("30 100").then_some(()));
    assert!(seen.is_some(), "stty size did not report the new size:\n{}", grid_text(&session));

    session.shutdown();
}

#[test]
fn the_line_discipline_accepts_utf8_input() {
    // bionic leaves IUTF8 unset, and without it readline erases single bytes of
    // a multibyte character, so the PTY sets the flag on the master itself.
    // `stty -g` prints the termios as hex fields, c_iflag first.
    let session = Session::spawn(5, &shell_config("/bin/sh", &[])).expect("spawn a shell");

    session
        .write(b"printf 'IUTF8=%d\\n' $(( 0x$(stty -g | cut -d: -f1) & 0x4000 ))\n")
        .expect("write to the pty");

    let seen = wait_for(|| grid_text(&session).contains("IUTF8=16384").then_some(()));
    assert!(seen.is_some(), "stty -g does not have IUTF8 set:\n{}", grid_text(&session));

    session.shutdown();
}

#[test]
fn the_manager_hands_out_and_closes_handles() {
    let handle = manager::spawn(&shell_config("/bin/sh", &[])).expect("spawn a shell");
    assert!(handle >= 1, "handles start at 1, got {handle}");
    assert!(manager::get(handle).is_some());

    manager::close(handle).expect("close");
    assert!(manager::get(handle).is_none());
    assert!(matches!(manager::close(handle), Err(SessionError::InvalidHandle)));
}
