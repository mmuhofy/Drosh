//! The `libghostty-vt` C ABI, as Zig sees it.
//!
//! `@cImport` of the released headers. Nothing is declared by hand: the
//! headers that ship next to the library are the contract, and a hand-written
//! mirror would drift from them silently.
//!
//! The import is split into its own file so that a signature change upstream
//! is one compile error in one place, rather than a mystery at every call site.

pub const c = @cImport({
    @cInclude("ghostty/vt.h");
});

/// The result code the C API returns. `GHOSTTY_SUCCESS` is zero; everything
/// else is a failure this side has to turn into a Java exception.
pub const Result = c.GhosttyResult;
pub const SUCCESS: Result = c.GHOSTTY_SUCCESS;

pub const Terminal = c.GhosttyTerminal;
pub const RenderState = c.GhosttyRenderState;
pub const RowIterator = c.GhosttyRenderStateRowIterator;
pub const RowCells = c.GhosttyRenderStateRowCells;
pub const Style = c.GhosttyStyle;
pub const ColorRgb = c.GhosttyColorRgb;
