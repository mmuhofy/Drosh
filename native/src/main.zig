//! libdrosh — the Drosh terminal's native half.
//!
//! Two things live here and nothing else:
//!
//! 1. **The VT terminal.** `libghostty-vt` owns the grid, the parser, the
//!    scrollback, the keyboard protocol and the graphics protocol. This crate
//!    does not reimplement any of it; it drives it.
//! 2. **The PTY.** A child process and the pseudoterminal it talks to, taken
//!    from Ghostty's own `src/pty.zig` — the same code that runs in Ghostty on
//!    Linux and macOS, which is the reason it is here rather than rewritten.
//!
//! The Kotlin side never sees a grid. It sees a frame: a flat, fixed-layout
//! buffer of cells and a small header, which it turns into quads. That split
//! is deliberate — every screen bug of the previous engine came from two
//! languages disagreeing about the grid.

const std = @import("std");
const builtin = @import("builtin");

pub const ghostty = @import("ghostty.zig");
pub const frame = @import("frame.zig");
pub const session = @import("session.zig");
pub const pty = @import("pty.zig");

comptime {
    _ = @import("jni/entry.zig");
}

/// The allocator every allocation in this library goes through.
///
/// A single c_allocator: the library is called from Java, its lifetime is the
/// process's, and a general-purpose allocator's bookkeeping would buy nothing
/// for buffers that are allocated once and freed at exit.
pub const allocator = std.heap.c_allocator;

test {
    std.testing.refAllDecls(@This());
}
