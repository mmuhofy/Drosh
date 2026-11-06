//! Selection and search, through the engine's own API.
//!
//! ## Why the engine answers
//!
//! Word and line boundaries are the engine's, not ours: they depend on the
//! semantic escape characters it was configured with and on the cell widths it
//! computed. A second implementation would disagree with what the renderer
//! highlights, and the disagreement shows as a selection that copies one word
//! and highlights two.
//!
//! Search is the same argument: the engine walks its own grid, including the
//! scrollback it holds, and returns matches in its own coordinates.
//!
//! ## The shape of the API
//!
//! Selection is *snapshot based*. `select_word` takes a grid reference and
//! returns a `GhosttySelection` the caller owns; it is not installed as the
//! terminal's current selection. That is why every entry point here takes a
//! row/column, resolves it to a reference, and formats the result in one go:
//! the snapshot's lifetime is the call's.

const std = @import("std");
const ghostty = @import("../ghostty.zig");
const c = ghostty.c;
const jni = @import("types.zig");

const allocator = @import("../alloc.zig").allocator;
const Session = @import("../session.zig").Session;

/// Select the word under (`row`, `column`) and return the text.
export fn Java_dev_drosh_terminal_engine_native_NativeSelection_nativeSelectWord(
    env: jni.JNIEnv,
    _: jni.jclass,
    handle: jni.jlong,
    row: jni.jint,
    column: jni.jint,
) jni.jstring {
    return withSelection(env, handle, .word, row, column);
}

/// Select the whole line under (`row`, `column`) and return the text.
export fn Java_dev_drosh_terminal_engine_native_NativeSelection_nativeSelectLine(
    env: jni.JNIEnv,
    _: jni.jclass,
    handle: jni.jlong,
    row: jni.jint,
    column: jni.jint,
) jni.jstring {
    return withSelection(env, handle, .line, row, column);
}

/// Select everything the engine holds, and return the text.
export fn Java_dev_drosh_terminal_engine_native_NativeSelection_nativeSelectAll(
    env: jni.JNIEnv,
    _: jni.jclass,
    handle: jni.jlong,
) jni.jstring {
    const session = Session.fromHandle(handle) orelse return emptyString(env);

    var selection: c.GhosttySelection = null;
    if (c.ghostty_terminal_select_all(session.terminal, &selection) != ghostty.SUCCESS) {
        return emptyString(env);
    }
    return format(env, session.terminal, selection);
}

/// The currently selected text, or an empty string.
///
/// The terminal does not keep a "current selection" in this API — selection is
/// a snapshot the caller owns — so this returns an empty string. It exists for
/// the host's interface, which asks the same question of both engines.
export fn Java_dev_drosh_terminal_engine_native_NativeSelection_nativeText(
    env: jni.JNIEnv,
    _: jni.jclass,
    _: jni.jlong,
) jni.jstring {
    return emptyString(env);
}

// --- search -----------------------------------------------------------------

/// Search the grid for `query`; returns the match count.
///
/// Results are written into `out` as `row, column, length` triples, oldest
/// first. The caller allocates for the count it expects and gets as many as
/// fit.
export fn Java_dev_drosh_terminal_engine_native_NativeSelection_nativeSearch(
    env: jni.JNIEnv,
    _: jni.jclass,
    handle: jni.jlong,
    query: jni.jstring,
    out: jni.jintArray,
    capacity: jni.jint,
) jni.jint {
    const session = Session.fromHandle(handle) orelse return -1;
    if (query == null or out == null or capacity <= 0) return -1;

    var arena = std.heap.ArenaAllocator.init(allocator);
    defer arena.deinit();

    const text = readString(env, query, arena.allocator()) catch return -1;
    if (text.len == 0) return 0;

    var search: c.GhosttySearch = null;
    if (c.ghostty_search_new(null, &search) != ghostty.SUCCESS) return -1;
    defer c.ghostty_search_free(search);

    // The needle is copied by the engine, so the arena's bytes only have to
    // live for the call.
    var needle = c.GhosttyString{ .ptr = text.ptr, .len = text.len };
    if (c.ghostty_search_set(
        search,
        c.GHOSTTY_SEARCH_OPTION_NEEDLE,
        @ptrCast(&needle),
    ) != ghostty.SUCCESS) {
        return -1;
    }

    if (c.ghostty_search_run(search, session.terminal) != ghostty.SUCCESS) {
        return -1;
    }

    var total: usize = 0;
    if (c.ghostty_search_get(
        search,
        c.GHOSTTY_SEARCH_DATA_TOTAL_MATCHES,
        @ptrCast(&total),
    ) != ghostty.SUCCESS or total == 0) {
        return 0;
    }

    // `VIEWPORT_MATCHES` rather than `MATCHES`: the latter is in the engine's
    // own grid coordinates (which include the scrollback), while the viewport
    // list is already resolved to what the renderer draws. The host's `find`
    // highlights rows on screen, so that is the one it wants.
    const wanted = @min(total, @as(usize, @intCast(capacity)));
    var results = allocator.alloc(i32, wanted * 3) catch return -1;
    defer allocator.free(results);

    var matches = std.mem.zeroes(c.GhosttySelectionBuffer);
    matches.cap = wanted;
    matches.ptr = allocator.alloc(c.GhosttySelection, wanted) catch return -1;
    defer allocator.free(matches.ptr[0..wanted]);

    if (c.ghostty_search_get(
        search,
        c.GHOSTTY_SEARCH_DATA_VIEWPORT_MATCHES,
        @ptrCast(&matches),
    ) != ghostty.SUCCESS) {
        return 0;
    }

    var columns: u16 = 0;
    var rows: u16 = 0;
    _ = c.ghostty_terminal_get(session.terminal, c.GHOSTTY_TERMINAL_DATA_COLS, @ptrCast(&columns));
    _ = c.ghostty_terminal_get(session.terminal, c.GHOSTTY_TERMINAL_DATA_ROWS, @ptrCast(&rows));

    var written: usize = 0;
    for (matches.ptr[0..matches.len]) |selection| {
        const bounds = viewportBounds(session.terminal, selection, columns, rows) orelse continue;
        if (written >= wanted) break;
        results[written * 3] = bounds.row;
        results[written * 3 + 1] = bounds.column;
        results[written * 3 + 2] = bounds.length;
        written += 1;
    }

    if (written == 0) return 0;

    const set_region = jni.call(
        env,
        *const fn (jni.JNIEnv, jni.jintArray, jni.jsize, jni.jsize, [*]const i32) callconv(.c) void,
        jni.Index.SetIntArrayRegion,
    );
    set_region(env, out, 0, @intCast(written * 3), results.ptr);
    return @intCast(written);
}

// --- helpers ----------------------------------------------------------------

const SelectionKind = enum { word, line };

/// A match, as the host's `find` reports it.
const Bounds = struct { row: i32, column: i32, length: i32 };

fn withSelection(
    env: jni.JNIEnv,
    handle: jni.jlong,
    kind: SelectionKind,
    row: jni.jint,
    column: jni.jint,
) jni.jstring {
    const session = Session.fromHandle(handle) orelse return emptyString(env);
    if (row < 0 or column < 0 or column > 0xffff) return emptyString(env);

    // A viewport point is what the caller's coordinates are: the renderer draws
    // the viewport, and the host's `row` is a screen row.
    var point = std.mem.zeroes(c.GhosttyPoint);
    point.tag = c.GHOSTTY_POINT_TAG_VIEWPORT;
    point.value.coordinate.x = @intCast(column);
    point.value.coordinate.y = @intCast(row);

    var ref: c.GhosttyGridRef = null;
    if (c.ghostty_terminal_grid_ref(session.terminal, point, &ref) != ghostty.SUCCESS) {
        return emptyString(env);
    }

    var selection: c.GhosttySelection = null;
    const result = switch (kind) {
        .word => blk: {
            var options = std.mem.zeroes(c.GhosttyTerminalSelectWordOptions);
            options.size = @sizeOf(c.GhosttyTerminalSelectWordOptions);
            options.ref = ref;
            break :blk c.ghostty_terminal_select_word(session.terminal, &options, &selection);
        },
        .line => blk: {
            var options = std.mem.zeroes(c.GhosttyTerminalSelectLineOptions);
            options.size = @sizeOf(c.GhosttyTerminalSelectLineOptions);
            options.ref = ref;
            break :blk c.ghostty_terminal_select_line(session.terminal, &options, &selection);
        },
    };

    if (result != ghostty.SUCCESS or selection == null) return emptyString(env);
    return format(env, session.terminal, selection);
}

/// Format a selection snapshot into a Java string.
fn format(
    env: jni.JNIEnv,
    terminal: ghostty.Terminal,
    selection: c.GhosttySelection,
) jni.jstring {
    var options = std.mem.zeroes(c.GhosttyTerminalSelectionFormatOptions);
    options.size = @sizeOf(c.GhosttyTerminalSelectionFormatOptions);
    options.selection = selection;

    var out_ptr: ?[*]u8 = null;
    var out_len: usize = 0;
    if (c.ghostty_terminal_selection_format_alloc(
        terminal,
        null,
        options,
        &out_ptr,
        &out_len,
    ) != ghostty.SUCCESS) {
        return emptyString(env);
    }
    defer if (out_ptr) |ptr| c.ghostty_free(null, ptr, out_len);

    if (out_ptr == null or out_len == 0) return emptyString(env);

    const z = allocator.dupeZ(u8, out_ptr.?[0..out_len]) catch return emptyString(env);
    defer allocator.free(z);

    const new_string = jni.call(
        env,
        *const fn (jni.JNIEnv, [*:0]const u8) callconv(.c) jni.jstring,
        jni.Index.NewStringUTF,
    );
    return new_string(env, z.ptr);
}

/// A match's extent in viewport coordinates, or null when it is off screen.
///
/// The engine exposes a selection as a *predicate* — `contains(terminal,
/// selection, point)` — not as a rectangle. That is deliberate: a selection can
/// be rectangular, linear across rows, or semantic, and a rectangle would be a
/// wrong answer for two of the three.
///
/// So the extent is found by asking. The scan is bounded by the viewport, which
/// is at most a few thousand cells, and it runs once per match on a user action
/// rather than per frame.
fn viewportBounds(
    terminal: ghostty.Terminal,
    selection: c.GhosttySelection,
    columns: u16,
    rows: u16,
) ?Bounds {
    var first_row: i32 = -1;
    var first_column: i32 = -1;
    var last_column: i32 = -1;

    var row: u16 = 0;
    while (row < rows) : (row += 1) {
        var column: u16 = 0;
        while (column < columns) : (column += 1) {
            var point = std.mem.zeroes(c.GhosttyPoint);
            point.tag = c.GHOSTTY_POINT_TAG_VIEWPORT;
            point.value.coordinate.x = column;
            point.value.coordinate.y = row;

            var contained: bool = false;
            if (c.ghostty_terminal_selection_contains(terminal, selection, point, &contained) !=
                ghostty.SUCCESS)
            {
                continue;
            }
            if (!contained) continue;

            if (first_row < 0) {
                first_row = row;
                first_column = column;
            }
            last_column = column;
        }
    }

    if (first_row < 0) return null;
    // A match spanning rows reports its first row and its width there; the
    // host's highlight draws per row anyway.
    return .{
        .row = first_row,
        .column = first_column,
        .length = @max(last_column - first_column + 1, 0),
    };
}

fn emptyString(env: jni.JNIEnv) jni.jstring {
    const new_string = jni.call(
        env,
        *const fn (jni.JNIEnv, [*:0]const u8) callconv(.c) jni.jstring,
        jni.Index.NewStringUTF,
    );
    return new_string(env, "");
}

fn readString(
    env: jni.JNIEnv,
    text: jni.jstring,
    arena: std.mem.Allocator,
) ![]const u8 {
    if (text == null) return &.{};

    const get_string_utf_chars = jni.call(
        env,
        *const fn (jni.JNIEnv, jni.jstring, ?*jni.jboolean) callconv(.c) ?[*:0]const u8,
        jni.Index.GetStringUTFChars,
    );
    const release_string_utf_chars = jni.call(
        env,
        *const fn (jni.JNIEnv, jni.jstring, [*:0]const u8) callconv(.c) void,
        jni.Index.ReleaseStringUTFChars,
    );

    const chars = get_string_utf_chars(env, text, null) orelse return error.OutOfMemory;
    defer release_string_utf_chars(env, text, chars);

    return arena.dupe(u8, std.mem.span(chars));
}
