//! The renderer's decisions, with no engine and no Android in them.
//!
//! Ported from Ghostty's own renderer (`src/renderer/`), file for file and
//! rule for rule. The reason is not tidiness: every one of these rules is a
//! decision that is invisible on plain text and obvious on a program that
//! draws a coloured bar, and reading the rule is the only way to be sure the
//! rule is right.
//!
//! Nothing here calls into the engine. The engine's answers come in as
//! plain values, the rules turn them into what is drawn, and the result goes
//! back out as plain values — which is also what makes the rules testable
//! without a device and without the engine's library.

// The same split Ghostty's renderer uses: the wire format, the frame, the
// rows, the images and the cell rules each live in their own file, so a rule
// can be read without the production around it.
pub mod cell;
// The rest reads the engine's render state, so it only exists with the engine.
// The cell rules are the part the tests build, and they stay engine-free.
#[cfg(feature = "engine")]
pub mod frame;
#[cfg(feature = "engine")]
pub mod image;
#[cfg(feature = "engine")]
pub mod row;
#[cfg(feature = "engine")]
pub mod wire;

#[cfg(feature = "engine")]
pub use frame::FrameSource;

/// What went wrong while producing a frame.
#[derive(Debug)]
pub enum FrameError {
    /// The render state refused the update.
    UpdateFailed(i32),
    /// The grid has no size yet.
    EmptyGrid,
    /// The iterator could not be bound to the render state.
    IteratorFailed(i32),
}
