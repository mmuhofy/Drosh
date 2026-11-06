//! The terminal handle: creation, feeding, resizing, scrolling, teardown.
//!
//! Handles cross the JNI boundary as `jlong`. They are opaque: a Java long is
//! 64 bits and a pointer on Android aarch64 is 64 bits, so the pointer travels
//! as-is and is never dereferenced without a null check.
//!
//! Every function returns a status the Kotlin side can act on; nothing throws
//! across the boundary except an out-of-memory, which is unrecoverable anyway.

const std = @import("std");
const ghostty = @import("../ghostty.zig");
const c = ghostty.c;
const jni = @import("types.zig");

const allocator = @import("../main.zig").allocator;

/// A handle as a pointer. Zero is null and never dereferenced.
inline fn fromHandle(handle: jni.jlong) ghostty.Terminal {
    if (handle <= 0) return null;
    return @ptrFromInt(@as(usize, @intCast(handle)));
}

/// A pointer as a handle.
inline fn toHandle(terminal: ghostty.Terminal) jni.jlong {
    if (terminal == null) return 0;
    return @intCast(@intFromPtr(terminal));
}

/// Create a terminal with `columns` x `rows` cells.
///
/// Returns the handle, or 0 on failure.
export fn Java_dev_drosh_terminal_engine_native_NativeTerminal_nativeCreate(
    _: jni.JNIEnv,
    _: jni.jclass,
    columns: jni.jint,
    rows: jni.jint,
) jni.jlong {
    if (columns <= 0 or rows <= 0 or columns > 0xffff or rows > 0xffff) {
        return 0;
    }

    var terminal: ghostty.Terminal = null;
    const result = c.ghostty_terminal_new(
        null,
        &terminal,
        @intCast(columns),
        @intCast(rows),
    );
    if (result != ghostty.SUCCESS) return 0;
    return toHandle(terminal);
}

/// Destroy a terminal. A zero handle is a no-op, so a double free is safe.
export fn Java_dev_drosh_terminal_engine_native_NativeTerminal_nativeFree(
    _: jni.JNIEnv,
    _: jni.jclass,
    handle: jni.jlong,
) void {
    const terminal = fromHandle(handle);
    if (terminal == null) return;
    c.ghostty_terminal_free(terminal);
}

/// Feed a byte stream to the parser.
///
/// This is the whole input path: escape sequences, text, OSC, DCS — the
/// library parses all of it. The caller passes the raw PTY bytes.
export fn Java_dev_drosh_terminal_engine_native_NativeTerminal_nativeWrite(
    env: jni.JNIEnv,
    _: jni.jclass,
    handle: jni.jlong,
    data: jni.jbyteArray,
    length: jni.jint,
) jni.jint {
    const terminal = fromHandle(handle);
    if (terminal == null or data == null or length < 0) return -1;

    // A frame's worth of output is usually a few hundred bytes; a `cat` of a
    // large file is not. Small writes stay on the stack.
    var stack: [4096]u8 = undefined;
    var heap: ?[]u8 = null;
    defer if (heap) |slice| allocator.free(slice);

    const wanted: usize = @intCast(length);
    const buffer: []u8 = if (wanted <= stack.len)
        stack[0..wanted]
    else blk: {
        const slice = allocator.alloc(u8, wanted) catch return -2;
        heap = slice;
        break :blk slice;
    };

    const get_region = jni.call(
        env,
        *const fn (jni.JNIEnv, jni.jbyteArray, jni.jsize, jni.jsize, [*]u8) callconv(.c) void,
        jni.Index.GetByteArrayRegion,
    );
    get_region(env, data, 0, @intCast(length), buffer.ptr);

    c.ghostty_terminal_vt_write(terminal, buffer.ptr, buffer.len);
    return 0;
}

/// Resize the grid and tell the terminal how large a cell is.
export fn Java_dev_drosh_terminal_engine_native_NativeTerminal_nativeResize(
    _: jni.JNIEnv,
    _: jni.jclass,
    handle: jni.jlong,
    columns: jni.jint,
    rows: jni.jint,
    cell_width: jni.jint,
    cell_height: jni.jint,
) jni.jint {
    const terminal = fromHandle(handle);
    if (terminal == null or columns <= 0 or rows <= 0) return -1;

    const result = c.ghostty_terminal_resize(
        terminal,
        @intCast(columns),
        @intCast(rows),
        @intCast(@max(cell_width, 1)),
        @intCast(@max(cell_height, 1)),
    );
    return if (result == ghostty.SUCCESS) 0 else -1;
}

/// Read one of the terminal's own numbers (`COLS`, `ROWS`, `SCROLLBAR`, …).
///
/// The result is widened into a long so one function covers every 16- and
/// 32-bit field: the caller says which field and reads the low bits.
export fn Java_dev_drosh_terminal_engine_native_NativeTerminal_nativeGet(
    _: jni.JNIEnv,
    _: jni.jclass,
    handle: jni.jlong,
    field: jni.jint,
) jni.jlong {
    const terminal = fromHandle(handle);
    if (terminal == null) return -1;

    // Every field this exposes is at most 32 bits wide; the widest is the
    // scrollbar's, which is a struct and is read through its own call below.
    var value: u64 = 0;
    const result = c.ghostty_terminal_get(terminal, @intCast(field), @ptrCast(&value));
    if (result != ghostty.SUCCESS) return -1;
    return @intCast(value);
}

/// Scroll the viewport.
///
/// `tag` is a `GhosttyTerminalScrollViewportTag`; `amount` is the row delta
/// for `DELTA` and the absolute row for `ROW`, and ignored for the others.
export fn Java_dev_drosh_terminal_engine_native_NativeTerminal_nativeScroll(
    _: jni.JNIEnv,
    _: jni.jclass,
    handle: jni.jlong,
    tag: jni.jint,
    amount: jni.jint,
) jni.jint {
    const terminal = fromHandle(handle);
    if (terminal == null) return -1;

    var behavior = std.mem.zeroes(c.GhosttyTerminalScrollViewport);
    behavior.tag = @intCast(tag);
    if (tag == c.GHOSTTY_SCROLL_VIEWPORT_DELTA) {
        behavior.value.delta = amount;
    } else if (tag == c.GHOSTTY_SCROLL_VIEWPORT_ROW) {
        behavior.value.row = if (amount > 0) @intCast(amount) else 0;
    }

    c.ghostty_terminal_scroll_viewport(terminal, behavior);
    return 0;
}
