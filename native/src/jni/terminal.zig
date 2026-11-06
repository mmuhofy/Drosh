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

/// Create a terminal with `columns` x `rows` cells.
///
/// Returns the handle, or 0 on failure. A zero handle is the only failure the
/// caller can see, so the reason is logged rather than returned: the caller
/// cannot do anything different for "out of memory" than for "invalid size".
export fn Java_dev_drosh_terminal_engine_native_NativeTerminal_nativeCreate(
    _env: jni.JNIEnv,
    _class: jni.jclass,
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
    if (result != ghostty.SUCCESS or terminal == null) {
        return 0;
    }
    return @intFromPtr(terminal);
}

/// Destroy a terminal. A zero handle is a no-op, so a double free is safe.
export fn Java_dev_drosh_terminal_engine_native_NativeTerminal_nativeFree(
    _env: jni.JNIEnv,
    _class: jni.jclass,
    handle: jni.jlong,
) void {
    if (handle == 0) return;
    c.ghostty_terminal_free(@ptrFromInt(@as(usize, @intCast(handle))));
}

/// Feed a byte stream to the parser.
///
/// This is the whole input path: escape sequences, text, OSC, DCS — the
/// library parses all of it. The caller passes the raw PTY bytes.
export fn Java_dev_drosh_terminal_engine_native_NativeTerminal_nativeWrite(
    env: jni.JNIEnv,
    _class: jni.jclass,
    handle: jni.jlong,
    data: jni.jbyteArray,
    length: jni.jint,
) jni.jint {
    if (handle == 0 or data == null or length < 0) return -1;

    const terminal: ghostty.Terminal = @ptrFromInt(@as(usize, @intCast(handle)));

    // The bytes are copied into a stack buffer for small writes and a heap
    // buffer for large ones. A frame's worth of output is usually a few
    // hundred bytes; a `cat` of a large file is not.
    var stack: [4096]u8 = undefined;
    var heap: ?[]u8 = null;
    defer if (heap) |slice| @import("../main.zig").allocator.free(slice);

    const buffer: []u8 = if (@as(usize, @intCast(length)) <= stack.len)
        stack[0..@intCast(length)]
    else blk: {
        const slice = @import("../main.zig").allocator.alloc(u8, @intCast(length)) catch return -2;
        heap = slice;
        break :blk slice;
    };

    const get_region = jni.call(
        env,
        *const fn (jni.JNIEnv, jni.jbyteArray, jni.jsize, jni.jsize, [*]jni.jbyte) callconv(.c) void,
        jni.Index.GetByteArrayRegion,
    );
    get_region(env, data, 0, @intCast(length), @ptrCast(buffer.ptr));

    c.ghostty_terminal_vt_write(terminal, buffer.ptr, buffer.len);
    return 0;
}

/// Resize the grid and tell the terminal how large a cell is.
export fn Java_dev_drosh_terminal_engine_native_NativeTerminal_nativeResize(
    _env: jni.JNIEnv,
    _class: jni.jclass,
    handle: jni.jlong,
    columns: jni.jint,
    rows: jni.jint,
    cell_width: jni.jint,
    cell_height: jni.jint,
) jni.jint {
    if (handle == 0 or columns <= 0 or rows <= 0) return -1;
    const terminal: ghostty.Terminal = @ptrFromInt(@as(usize, @intCast(handle)));
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
/// The result is packed into a long so one function covers every 16- and
/// 32-bit field: the caller says which field and reads the low bits.
export fn Java_dev_drosh_terminal_engine_native_NativeTerminal_nativeGet(
    _env: jni.JNIEnv,
    _class: jni.jclass,
    handle: jni.jlong,
    field: jni.jint,
) jni.jlong {
    if (handle == 0) return -1;
    const terminal: ghostty.Terminal = @ptrFromInt(@as(usize, @intCast(handle)));

    var value: u64 = 0;
    const result = c.ghostty_terminal_get(terminal, @enumFromInt(field), &value);
    return if (result == ghostty.SUCCESS) @intCast(value) else -1;
}

/// Scroll the viewport.
///
/// `tag` is a `GhosttyTerminalScrollViewportTag`; `amount` is the row delta
/// for `DELTA` and the absolute row for `ROW`, and ignored for the others.
export fn Java_dev_drosh_terminal_engine_native_NativeTerminal_nativeScroll(
    _env: jni.JNIEnv,
    _class: jni.jclass,
    handle: jni.jlong,
    tag: jni.jint,
    amount: jni.jint,
) jni.jint {
    if (handle == 0) return -1;
    const terminal: ghostty.Terminal = @ptrFromInt(@as(usize, @intCast(handle)));

    var behavior: c.GhosttyTerminalScrollViewport = undefined;
    behavior.tag = @enumFromInt(tag);
    behavior.value = std.mem.zeroes(c.GhosttyTerminalScrollViewportValue);
    if (behavior.tag == c.GHOSTTY_SCROLL_VIEWPORT_DELTA) {
        behavior.value.delta = amount;
    } else if (behavior.tag == c.GHOSTTY_SCROLL_VIEWPORT_ROW) {
        behavior.value.row = if (amount > 0) @intCast(amount) else 0;
    }

    c.ghostty_terminal_scroll_viewport(terminal, behavior);
    return 0;
}
