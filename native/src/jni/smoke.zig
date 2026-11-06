//! The self-test: does the released `libghostty-vt.so` actually work here?
//!
//! This is the gate for the whole migration. Before a single line of renderer
//! or session code is written, the library has to prove it can be created,
//! fed a byte stream, and read back as cells — on the device, with the ABI we
//! ship. If it cannot, everything above it is speculation.
//!
//! The test returns a report rather than a bool, because a failure has to say
//! *which* step failed on a device whose log may be the only channel out.

const std = @import("std");
const ghostty = @import("../ghostty.zig");
const c = ghostty.c;
const jni = @import("types.zig");

/// A fixed report buffer.
///
/// A `std.ArrayList` would do, but its API moved in 0.16 and this file has no
/// need of a growing buffer: the report is a dozen short lines, and a bound is
/// a better guarantee than a growth policy.
const Report = struct {
    buffer: [2048]u8 = undefined,
    len: usize = 0,

    fn print(self: *Report, comptime fmt: []const u8, args: anytype) void {
        const written = std.fmt.bufPrint(self.buffer[self.len..], fmt, args) catch return;
        self.len += written.len;
    }

    fn text(self: *const Report) []const u8 {
        return self.buffer[0..self.len];
    }
};

/// Run the whole pipeline and return a report.
///
/// A failure is reported *in* the string, not as a thrown exception: this
/// function exists to be read on a device, and an exception would lose the
/// step that failed.
export fn Java_dev_drosh_terminal_engine_native_NativeSmoke_nativeSelfTest(
    env: jni.JNIEnv,
    _: jni.jclass,
) jni.jstring {
    var report = Report{};
    run(&report);
    report.print("\n", .{});

    const new_string = jni.call(
        env,
        *const fn (jni.JNIEnv, [*:0]const u8) callconv(.c) jni.jstring,
        jni.Index.NewStringUTF,
    );

    const allocator = @import("../main.zig").allocator;
    const z = allocator.dupeZ(u8, report.text()) catch return null;
    defer allocator.free(z);
    return new_string(env, z.ptr);
}

fn run(report: *Report) void {
    // --- 1. the library answers -------------------------------------------
    var version: c.GhosttyString = std.mem.zeroes(c.GhosttyString);
    const info = c.ghostty_build_info(c.GHOSTTY_BUILD_INFO_VERSION_STRING, @ptrCast(&version));
    if (info != ghostty.SUCCESS) {
        report.print("FAIL: ghostty_build_info -> {d}", .{info});
        return;
    }
    report.print("ghostty-vt: {s}", .{span(version)});

    // --- 2. a terminal can be created -------------------------------------
    var terminal: ghostty.Terminal = null;
    const created = c.ghostty_terminal_new(null, &terminal, 20, 4);
    if (created != ghostty.SUCCESS) {
        report.print("FAIL: ghostty_terminal_new -> {d}", .{created});
        return;
    }
    defer c.ghostty_terminal_free(terminal);
    report.print("terminal: created 20x4", .{});

    // --- 3. it parses a VT stream -----------------------------------------
    const text = "hi";
    c.ghostty_terminal_vt_write(terminal, text.ptr, text.len);

    // --- 4. a render state can be updated from it -------------------------
    var state: ghostty.RenderState = null;
    const state_result = c.ghostty_render_state_new(null, &state);
    if (state_result != ghostty.SUCCESS) {
        report.print("FAIL: ghostty_render_state_new -> {d}", .{state_result});
        return;
    }
    defer c.ghostty_render_state_free(state);

    const updated = c.ghostty_render_state_update(state, terminal);
    if (updated != ghostty.SUCCESS) {
        report.print("FAIL: ghostty_render_state_update -> {d}", .{updated});
        return;
    }

    var cols: u16 = 0;
    var rows: u16 = 0;
    _ = c.ghostty_render_state_get(state, c.GHOSTTY_RENDER_STATE_DATA_COLS, @ptrCast(&cols));
    _ = c.ghostty_render_state_get(state, c.GHOSTTY_RENDER_STATE_DATA_ROWS, @ptrCast(&rows));
    report.print("grid: {d}x{d}", .{ cols, rows });

    // --- 5. the cells can be read back ------------------------------------
    var iterator: ghostty.RowIterator = null;
    if (c.ghostty_render_state_row_iterator_new(null, &iterator) != ghostty.SUCCESS) {
        report.print("FAIL: row_iterator_new", .{});
        return;
    }
    defer c.ghostty_render_state_row_iterator_free(iterator);

    var cells: ghostty.RowCells = null;
    if (c.ghostty_render_state_row_cells_new(null, &cells) != ghostty.SUCCESS) {
        report.print("FAIL: row_cells_new", .{});
        return;
    }
    defer c.ghostty_render_state_row_cells_free(cells);

    // The iterator is fed by the render state, not by the terminal.
    const bound = c.ghostty_render_state_get(
        state,
        c.GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR,
        @ptrCast(&iterator),
    );
    if (bound != ghostty.SUCCESS) {
        report.print("FAIL: bind row iterator -> {d}", .{bound});
        return;
    }

    if (!c.ghostty_render_state_row_iterator_next(iterator)) {
        report.print("FAIL: no rows in the iterator", .{});
        return;
    }

    if (c.ghostty_render_state_row_get(
        iterator,
        c.GHOSTTY_RENDER_STATE_ROW_DATA_CELLS,
        @ptrCast(&cells),
    ) != ghostty.SUCCESS) {
        report.print("FAIL: row_get(cells)", .{});
        return;
    }

    var h: u32 = 0;
    var i: u32 = 0;
    if (c.ghostty_render_state_row_cells_select(cells, 0) != ghostty.SUCCESS) {
        report.print("FAIL: row_cells_select(0)", .{});
        return;
    }
    _ = c.ghostty_render_state_row_cells_get(
        cells,
        c.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF,
        @ptrCast(&h),
    );
    if (c.ghostty_render_state_row_cells_select(cells, 1) != ghostty.SUCCESS) {
        report.print("FAIL: row_cells_select(1)", .{});
        return;
    }
    _ = c.ghostty_render_state_row_cells_get(
        cells,
        c.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF,
        @ptrCast(&i),
    );

    report.print("cells: '{c}' '{c}'", .{
        @as(u8, @truncate(h)),
        @as(u8, @truncate(i)),
    });
    if (h == 'h' and i == 'i') {
        report.print("PASS", .{});
    } else {
        report.print("FAIL: expected 'h','i'", .{});
    }
}

/// A `GhosttyString` as a Zig slice. The bytes are borrowed.
fn span(value: c.GhosttyString) []const u8 {
    if (value.ptr == null) return "";
    return value.ptr[0..value.len];
}
