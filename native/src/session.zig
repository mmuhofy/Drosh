//! A session: the terminal, its render state, and the frame it produces.
//!
//! One handle owns all of it. The Kotlin side asks for a frame and gets bytes;
//! it does not hold a terminal, a render state and a row iterator separately,
//! because a frame assembled from three calls is a frame whose parts can come
//! from three different moments.

const std = @import("std");
const ghostty = @import("ghostty.zig");
const c = ghostty.c;
const frame = @import("frame.zig");

const allocator = @import("main.zig").allocator;

/// One session: a terminal and everything needed to draw it.
pub const Session = struct {
    terminal: ghostty.Terminal,
    frames: frame.FrameSource,

    pub fn create(columns: u16, rows: u16) !*Session {
        var terminal: ghostty.Terminal = null;
        if (c.ghostty_terminal_new(null, &terminal, columns, rows) != ghostty.SUCCESS) {
            return error.TerminalFailed;
        }
        errdefer c.ghostty_terminal_free(terminal);

        var frames = try frame.FrameSource.create(terminal);
        errdefer frames.destroy();

        const self = try allocator.create(Session);
        self.* = .{ .terminal = terminal, .frames = frames };
        return self;
    }

    pub fn destroy(self: *Session) void {
        self.frames.destroy();
        c.ghostty_terminal_free(self.terminal);
        allocator.destroy(self);
    }

    /// The next frame, or null when the grid is empty.
    pub fn render(self: *Session) ?[]const u8 {
        return self.frames.render() catch null;
    }
};
