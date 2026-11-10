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

pub mod cell;
