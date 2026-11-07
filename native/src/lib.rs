//! libdrosh — the Drosh terminal's native half.
//!
//! Three things live here and nothing else:
//!
//! 1. **The VT terminal.** `libghostty-vt` owns the grid, the parser, the
//!    scrollback, the keyboard protocol and the graphics protocol. This crate
//!    does not reimplement any of it; it drives it.
//! 2. **The PTY.** A child process and the pseudoterminal it talks to, with
//!    Ghostty's own `forkpty`/`setsid`/`TIOCSCTTY` ordering, which is the part
//!    that is easy to get subtly wrong.
//! 3. **Shell integration.** `OSC 133` and `OSC 7` are parsed by the engine but
//!    not exposed by it, so a tap on the PTY's output keeps the few facts the UI
//!    needs.
//!
//! The Kotlin side never sees a grid. It sees a frame: a flat, fixed-layout
//! buffer of cells and a small header, which it turns into quads. That split is
//! deliberate — every screen bug of the previous engine came from two languages
//! disagreeing about the grid.

#![deny(unsafe_op_in_unsafe_fn)]

pub mod frame;
pub mod ghostty;
pub mod pty;
pub mod session;
pub mod shell;

pub mod jni;

/// Install a panic hook that logs instead of unwinding.
///
/// A panic that reaches the JVM takes the process down with a message nobody
/// reads. This at least names what happened before it does.
pub fn install_panic_hook() {
    std::panic::set_hook(Box::new(|info| {
        log::error!("drosh-native: panic: {info}");
    }));
}