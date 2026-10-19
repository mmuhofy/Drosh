//! Ambient tint: the average background colour of the visible grid.
//!
//! The Android view tints its chrome (status bar, nav bar) to match the terminal
//! so the app reads as one surface. Computing it here, over the cells the
//! renderer already iterates, avoids copying the grid to Kotlin just to average
//! a few colours.

use alacritty_terminal::term::Term;
use alacritty_terminal::vte::ansi::{Color, Rgb};

use crate::EventProxy;

/// The average RGB of every visible cell whose background is an explicit colour.
///
/// Cells using the default palette background are skipped — they carry no
/// information about the running program's theme. Returns `None` when nothing on
/// screen sets a custom background (the view falls back to its own neutral tint).
pub fn ambient_tint(term: &Term<EventProxy>) -> Option<[u8; 3]> {
    let mut red: u64 = 0;
    let mut green: u64 = 0;
    let mut blue: u64 = 0;
    let mut count: u64 = 0;

    for indexed in term.grid().display_iter() {
        if let Color::Spec(rgb) = indexed.cell.bg {
            red += rgb.r as u64;
            green += rgb.g as u64;
            blue += rgb.b as u64;
            count += 1;
        }
    }

    let red = red.checked_div(count)?;
    let green = green.checked_div(count)?;
    let blue = blue.checked_div(count)?;
    Some([red as u8, green as u8, blue as u8])
}

/// Like [`ambient_tint`], but always returns a value: the default palette
/// background is treated as `#1e1e1e`, which is the app's fallback tint.
pub fn ambient_tint_defaulting(term: &Term<EventProxy>, default: Rgb) -> [u8; 3] {
    ambient_tint(term).unwrap_or([default.r, default.g, default.b])
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn none_when_no_custom_background() {
        let session = crate::test_session();
        session.feed(b"plain text");
        let term = session.term.lock();
        assert!(ambient_tint(&term).is_none());
    }

    #[test]
    fn averages_custom_background() {
        // Set the whole screen background to truecolour red (SGR 48;2), then feed
        // plain text so the cells carry that background. A palette code like
        // `[41m` would be `Color::Indexed`, which ambient_tint intentionally
        // skips.
        let session = crate::test_session();
        session.feed(b"\x1b[48;2;192;0;0m");
        session.feed(b"red");

        let term = session.term.lock();
        assert_eq!(ambient_tint(&term), Some([0xc0, 0x00, 0x00]));
    }
}
