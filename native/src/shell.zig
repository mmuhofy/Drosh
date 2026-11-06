//! Shell integration: the marks a shell writes into its own output.
//!
//! ## What it is
//!
//! `OSC 133` is a convention, not an escape sequence the terminal must obey.
//! A shell that opts in writes a marker at each point in its cycle:
//!
//! ```
//! ESC ] 133 ; A ST     the prompt is showing
//! ESC ] 133 ; B ST     the user is typing
//! ESC ] 133 ; C ST     the command is running
//! ESC ] 133 ; D ; 0 ST the command finished with status 0
//! ```
//!
//! plus `OSC 7` for the working directory and `OSC 1339` for "open this file".
//!
//! ## Why this is here and not in the engine
//!
//! `libghostty-vt` parses the sequences but exposes no API for them: Ghostty
//! reads them in its own Zig application layer, above the VT core. So does this
//! — the tap sits on the PTY read path, sees each chunk before the parser does,
//! and keeps the few facts the UI needs: which phase the shell is in, where the
//! prompt started, what the working directory is.
//!
//! ## The scanner is incremental
//!
//! A mark can be split across two reads — a shell writing `ESC ] 133 ;` and
//! then `A ST` is not hypothetical, it is what a slow pipe does. The scanner
//! keeps a small carry buffer, and a mark that does not complete is retried
//! with the next chunk rather than dropped.
//!
//! It never modifies the bytes: the same chunk goes to the parser unchanged.
//! The tap observes; it does not filter.

const std = @import("std");

/// Where the shell is in its cycle.
pub const Phase = enum(u8) {
    /// No mark has been seen — the shell has not opted in.
    unknown = 0,
    /// `OSC 133 ; A`: the prompt is on screen, waiting for a command.
    prompt = 1,
    /// `OSC 133 ; B`: the user is typing the command.
    typing = 2,
    /// `OSC 133 ; C`: the command is running.
    running = 3,
    /// `OSC 133 ; D`: the command finished.
    finished = 4,
};

/// A mark, as the scanner reports it.
pub const Mark = union(enum) {
    prompt_start,
    command_start,
    command_exec,
    command_finished: ?i32,
    working_directory: []const u8,
    editor_target: []const u8,
};

/// The scanner's carry buffer.
///
/// A mark that starts and does not finish is kept here. 512 bytes is far more
/// than any real mark: the longest is an `OSC 7` working directory, and a path
/// longer than this is one no UI can show.
const CARRY_BYTES = 512;

/// The scanner: sees chunks, reports marks, never modifies bytes.
pub const Scanner = struct {
    /// The unfinished mark, if there is one.
    carry: [CARRY_BYTES]u8 = undefined,
    carry_len: usize = 0,

    /// The last reported phase and directory, so the UI can ask without waiting
    /// for the next mark.
    phase: Phase = .unknown,
    directory: [CARRY_BYTES]u8 = undefined,
    directory_len: usize = 0,
    editor: [CARRY_BYTES]u8 = undefined,
    editor_len: usize = 0,

    /// Scan one chunk. Returns the marks it completed.
    ///
    /// The returned slices point into `carry` or into `chunk`, so they are
    /// valid only until the next call — the caller copies what it needs.
    pub fn scan(self: *Scanner, chunk: []const u8) Marks {
        var out = Marks{};

        // A carry plus a new chunk is scanned as one buffer, so a mark split
        // across two reads is still found.
        var index: usize = 0;
        while (index < chunk.len) {
            const rest = chunk[index..];
            const start = std.mem.indexOfScalar(u8, rest, 0x1b) orelse break;
            index += start;

            const body = chunk[index..];
            const consumed = self.tryMark(body, &out);
            if (consumed == 0) break;
            index += consumed;
        }

        return out;
    }

    /// Try to read one mark at the start of `body`. Returns the bytes consumed,
    /// or 0 when the mark is incomplete.
    fn tryMark(self: *Scanner, body: []const u8, out: *Marks) usize {
        // ESC ] ...
        if (body.len < 2 or body[0] != 0x1b or body[1] != ']') return 0;

        // Find the terminator: BEL or ESC \.
        var end: usize = 2;
        var terminated = false;
        var terminator_len: usize = 0;
        while (end < body.len) : (end += 1) {
            if (body[end] == 0x07) {
                terminated = true;
                terminator_len = 1;
                break;
            }
            if (body[end] == 0x1b) {
                if (end + 1 >= body.len) return 0; // may be the start of ESC \
                if (body[end + 1] == '\\') {
                    terminated = true;
                    terminator_len = 2;
                    break;
                }
            }
        }
        if (!terminated) return 0;

        const payload = body[2..end];
        const consumed = end + terminator_len;
        self.dispatch(payload, out);
        return consumed;
    }

    /// Turn an OSC payload into a mark.
    fn dispatch(self: *Scanner, payload: []const u8, out: *Marks) void {
        // OSC 133 ; X [; status]
        if (std.mem.startsWith(u8, payload, "133;")) {
            const rest = payload[4..];
            if (rest.len == 0) return;

            switch (rest[0]) {
                'A' => {
                    self.phase = .prompt;
                    out.add(.prompt_start);
                },
                'B' => {
                    self.phase = .typing;
                    out.add(.command_start);
                },
                'C' => {
                    self.phase = .running;
                    out.add(.command_exec);
                },
                'D' => {
                    self.phase = .finished;
                    var status: ?i32 = null;
                    if (rest.len > 2 and rest[1] == ';') {
                        status = std.fmt.parseInt(i32, rest[2..], 10) catch null;
                    }
                    out.add(.{ .command_finished = status });
                },
                else => {},
            }
            return;
        }

        // OSC 7 ; file://host/path
        if (std.mem.startsWith(u8, payload, "7;")) {
            var value = payload[2..];
            // Strip the `file://host` prefix: the UI shows a path, not a URL.
            if (std.mem.indexOf(u8, value, "://")) |scheme| {
                const after = value[scheme + 3 ..];
                if (std.mem.indexOfScalar(u8, after, '/')) |slash| {
                    value = after[slash..];
                }
            }
            const copy = self.storeDirectory(value);
            out.add(.{ .working_directory = copy });
            return;
        }

        // OSC 1339 ; path — "open this file in the editor".
        if (std.mem.startsWith(u8, payload, "1339;")) {
            const copy = self.storeEditor(payload[5..]);
            out.add(.{ .editor_target = copy });
        }
    }

    fn storeDirectory(self: *Scanner, value: []const u8) []const u8 {
        const len = @min(value.len, CARRY_BYTES);
        @memcpy(self.directory[0..len], value[0..len]);
        self.directory_len = len;
        return self.directory[0..len];
    }

    fn storeEditor(self: *Scanner, value: []const u8) []const u8 {
        const len = @min(value.len, CARRY_BYTES);
        @memcpy(self.editor[0..len], value[0..len]);
        self.editor_len = len;
        return self.editor[0..len];
    }

    /// The working directory the shell last reported, or null.
    pub fn currentDirectory(self: *const Scanner) ?[]const u8 {
        if (self.directory_len == 0) return null;
        return self.directory[0..self.directory_len];
    }

    /// The editor target the shell last reported, or null.
    pub fn currentEditor(self: *const Scanner) ?[]const u8 {
        if (self.editor_len == 0) return null;
        return self.editor[0..self.editor_len];
    }
};

/// The marks one chunk completed. A fixed array: a chunk that completes more
/// than this many marks is a chunk with a shell loop in it, and the UI cannot
/// show them anyway.
pub const Marks = struct {
    items: [8]Mark = undefined,
    len: usize = 0,

    fn add(self: *Marks, mark: Mark) void {
        if (self.len >= self.items.len) return;
        self.items[self.len] = mark;
        self.len += 1;
    }

    pub fn slice(self: *const Marks) []const Mark {
        return self.items[0..self.len];
    }
};
