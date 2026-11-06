//! The self-test: does the released `libghostty-vt.so` actually work here?
//!
//! This is the gate for the whole migration. Before a single line of renderer
//! or session code is written, the library has to prove it can be created,
//! fed a byte stream, and read back as cells — on the device, with the ABI we
//! ship. If it cannot, everything above it is speculation.
//!
//! The test is deliberately small and deliberately end-to-end: create a
//! terminal, write `hi`, update a render state, walk the rows, read the two
//! cells, and compare. It returns a human-readable report rather than a bool,
//! because a failure has to say *which* step failed on a device whose log may
//! be the only channel out.

const std = @import("std");
const ghostty = @import("../ghostty.zig");
const c = ghostty.c;
const jni = @import("types.zig");

const allocator = @import("../main.zig").allocator;

/// Run the whole pipeline and return a report.
///
/// Returns a Java string. A failure is reported *in* the string, not as a
/// thrown exception: this function exists to be read on a device, and an
/// exception would lose the step that failed.
export fn Java_dev_drosh_terminal_engine_native_NativeSmoke_nativeSelfTest(
    env: jni.JNIEnv,
    _class: jni.jclass,
) jni.jstring {
    var report = std.ArrayList(u8).init(allocator);
    defer report.deinit();

    run(&report) catch |err| {
        report.writer().print("FAIL: unexpected error {s}", .{@errorName(err)}) catch {};
    };

    const new_string = jni.call(
        env,
        *const fn (jni.JNIEnv, [*:0]const u8) callconv(.c) jni.jstring,
        jni.Index.NewStringUTF,
    );

    const z = allocator.dupeZ(u8, report.items) catch return null;
    defer allocator.free(z);
    return new_string(env, z.ptr);
}

fn run(report: *std.ArrayList(u8)) !void {
    const w = report.writer();

    // --- 1. the library answers -------------------------------------------
    var version: c.GhosttyString = undefined;
    if (c.ghostty_build_info(c.GHOSTTY_BUILD_INFO_VERSION_STRING, &version) == ghostty.SUCCESS) {
        try w.print("ghostty-vt: {s}\n", .{span(version)});
    } else {
        try w.print("FAIL: ghostty_build_info\n", .{});
        return;
    }

    // --- 2. a terminal can be created -------------------------------------
    var terminal: ghostty.Terminal = null;
    const created = c.ghostty_terminal_new(null, &terminal, 20, 4);
    if (created != ghostty.SUCCESS) {
        try w.print("FAIL: ghostty_terminal_new -> {d}\n", .{created});
        return;
    }
    defer c.ghostty_terminal_free(terminal);
    try w.print("terminal: created 20x4\n", .{});

    // --- 3. it parses a VT stream -----------------------------------------
    const text = "hi";
    c.ghostty_terminal_vt_write(terminal, text.ptr, text.len);

    // --- 4. a render state can be updated from it -------------------------
    var state: ghostty.RenderState = null;
    const state_result = c.ghostty_render_state_new(null, &state);
    if (state_result != ghostty.SUCCESS) {
        try w.print("FAIL: ghostty_render_state_new -> {d}\n", .{state_result});
        return;
    }
    defer c.ghostty_render_state_free(state);

    const updated = c.ghostty_render_state_update(state, terminal);
    if (updated != ghostty.SUCCESS) {
        try w.print("FAIL: ghostty_render_state_update -> {d}\n", .{updated});
        return;
    }

    var cols: u16 = 0;
    var rows: u16 = 0;
    _ = c.ghostty_render_state_get(state, c.GHOSTTY_RENDER_STATE_DATA_COLS, &cols);
    _ = c.ghostty_render_state_get(state, c.GHOSTTY_RENDER_STATE_DATA_ROWS, &rows);
    try w.print("grid: {d}x{d}\n", .{ cols, rows });

    // --- 5. the cells can be read back ------------------------------------
    var iterator: ghostty.RowIterator = null;
    if (c.ghostty_render_state_row_iterator_new(null, &iterator) != ghostty.SUCCESS) {
        try w.print("FAIL: row_iterator_new\n", .{});
        return;
    }
    defer c.ghostty_render_state_row_iterator_free(iterator);

    var cells: ghostty.RowCells = null;
    if (c.ghostty_render_state_row_cells_new(null, &cells) != ghostty.SUCCESS) {
        try w.print("FAIL: row_cells_new\n", .{});
        return;
    }
    defer c.ghostty_render_state_row_cells_free(cells);

    // The iterator is fed by the render state, not by the terminal.
    const iterator_bound = c.ghostty_render_state_get(
        state,
        c.GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR,
        @ptrCast(&iterator),
    );
    if (iterator_bound != ghostty.SUCCESS) {
        try w.print("FAIL: bind row iterator -> {d}\n", .{iterator_bound});
        return;
    }

    if (!c.ghostty_render_state_row_iterator_next(iterator)) {
        try w.print("FAIL: no rows in the iterator\n", .{});
        return;
    }

    const row_cells = c.ghostty_render_state_row_get(
        iterator,
        c.GHOSTTY_RENDER_STATE_ROW_DATA_CELLS,
        @ptrCast(&cells),
    );
    if (row_cells != ghostty.SUCCESS) {
        try w.print("FAIL: row_get(cells) -> {d}\n", .{row_cells});
        return;
    }

    var h: u32 = 0;
    var i: u32 = 0;
    if (c.ghostty_render_state_row_cells_select(cells, 0) != ghostty.SUCCESS) {
        try w.print("FAIL: row_cells_select(0)\n", .{});
        return;
    }
    _ = c.ghostty_render_state_row_cells_get(
        cells,
        c.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF,
        @ptrCast(&h),
    );
    if (c.ghostty_render_state_row_cells_select(cells, 1) != ghostty.SUCCESS) {
        try w.print("FAIL: row_cells_select(1)\n", .{});
        return;
    }
    _ = c.ghostty_render_state_row_cells_get(
        cells,
        c.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF,
        @ptrCast(&i),
    );

    try w.print("cells: '{c}' '{c}'\n", .{ @as(u8, @truncate(h)), @as(u8, @truncate(i)) });
    if (h == 'h' and i == 'i') {
        try w.print("PASS\n", .{});
    } else {
        try w.print("FAIL: expected 'h','i'\n", .{});
    }
}

/// A `GhosttyString` as a Zig slice. The bytes are borrowed.
fn span(value: c.GhosttyString) []const u8 {
    if (value.ptr == null) return "";
    return value.ptr[0..value.len];
}
