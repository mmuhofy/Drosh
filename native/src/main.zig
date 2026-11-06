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
pub const shell = @import("shell.zig");

comptime {
    _ = @import("jni/entry.zig");
}

pub const allocator = @import("alloc.zig").allocator;

test {
    std.testing.refAllDecls(@This());
}
