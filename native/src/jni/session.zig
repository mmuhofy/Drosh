//! The frame, across the JNI boundary.
//!
//! The whole point of the frame is that it crosses in one piece: the Kotlin
//! side calls `nativeFrame`, gets a byte array, and parses it. There is no
//! second call that could answer from a different moment, and no shared buffer
//! whose lifetime the two sides have to negotiate.
//!
//! A copy per frame is the cost. At 80x24 that is 38 KB; at 200x60, 240 KB. A
//! `memcpy` of that is microseconds, and it buys an ownership rule that cannot
//! be got wrong.

const std = @import("std");
const jni = @import("types.zig");
const ghostty = @import("../ghostty.zig");
const c = ghostty.c;

const Session = @import("../session.zig").Session;
const frame = @import("../frame.zig");

const allocator = @import("../alloc.zig").allocator;

/// A session handle as a pointer. Zero is null and never dereferenced.
inline fn fromHandle(handle: jni.jlong) ?*Session {
    return Session.fromHandle(handle);
}

inline fn toHandle(session: ?*Session) jni.jlong {
    if (session == null) return 0;
    return @intCast(@intFromPtr(session.?));
}

/// Create a session with a `columns` × `rows` terminal. Zero on failure.
export fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeCreate(
    _: jni.JNIEnv,
    _: jni.jclass,
    columns: jni.jint,
    rows: jni.jint,
) jni.jlong {
    if (columns <= 0 or rows <= 0 or columns > 0xffff or rows > 0xffff) return 0;
    const session = Session.create(@intCast(columns), @intCast(rows)) catch return 0;
    return toHandle(session);
}

/// Destroy a session. A zero handle is a no-op.
export fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeFree(
    _: jni.JNIEnv,
    _: jni.jclass,
    handle: jni.jlong,
) void {
    const session = fromHandle(handle) orelse return;
    session.destroy();
}

/// Feed a byte stream to the parser.
export fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeWrite(
    env: jni.JNIEnv,
    _: jni.jclass,
    handle: jni.jlong,
    data: jni.jbyteArray,
    length: jni.jint,
) jni.jint {
    const session = fromHandle(handle) orelse return -1;
    if (data == null or length < 0) return -1;

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

    // The shell's marks are scanned first: the tap observes the bytes and
    // never changes them, so the parser sees exactly what the child wrote.
    _ = session.shell.scan(buffer);
    c_vt_write(session, buffer);
    return 0;
}

/// The shell's phase, as its `OSC 133` marks report it.
export fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeShellPhase(
    _: jni.JNIEnv,
    _: jni.jclass,
    handle: jni.jlong,
) jni.jint {
    const session = Session.fromHandle(handle) orelse return -1;
    return @intCast(@intFromEnum(session.shell.phase));
}

/// The working directory the shell last reported, or an empty string.
export fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeWorkingDirectory(
    env: jni.JNIEnv,
    _: jni.jclass,
    handle: jni.jlong,
) jni.jstring {
    const session = Session.fromHandle(handle) orelse return emptyString(env);
    const directory = session.shell.currentDirectory() orelse return emptyString(env);

    const z = allocator.dupeZ(u8, directory) catch return emptyString(env);
    defer allocator.free(z);

    const new_string = jni.call(
        env,
        *const fn (jni.JNIEnv, [*:0]const u8) callconv(.c) jni.jstring,
        jni.Index.NewStringUTF,
    );
    return new_string(env, z.ptr);
}

fn emptyString(env: jni.JNIEnv) jni.jstring {
    const new_string = jni.call(
        env,
        *const fn (jni.JNIEnv, [*:0]const u8) callconv(.c) jni.jstring,
        jni.Index.NewStringUTF,
    );
    return new_string(env, "");
}

/// Resize the grid and record the cell size.
export fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeResize(
    _: jni.JNIEnv,
    _: jni.jclass,
    handle: jni.jlong,
    columns: jni.jint,
    rows: jni.jint,
    cell_width: jni.jint,
    cell_height: jni.jint,
) jni.jint {
    const session = fromHandle(handle) orelse return -1;
    if (columns <= 0 or rows <= 0) return -1;
    const result = c.ghostty_terminal_resize(
        session.terminal,
        @intCast(columns),
        @intCast(rows),
        @intCast(@max(cell_width, 1)),
        @intCast(@max(cell_height, 1)),
    );
    return if (result == ghostty.SUCCESS) 0 else -1;
}

/// Produce the next frame as a byte array, or null when there is nothing to
/// draw.
export fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeFrame(
    env: jni.JNIEnv,
    _: jni.jclass,
    handle: jni.jlong,
) jni.jbyteArray {
    const session = fromHandle(handle) orelse return null;
    const bytes = session.render() orelse return null;

    const new_array = jni.call(
        env,
        *const fn (jni.JNIEnv, jni.jsize) callconv(.c) jni.jbyteArray,
        jni.Index.NewByteArray,
    );
    const array = new_array(env, @intCast(bytes.len));
    if (array == null) return null;

    const set_region = jni.call(
        env,
        *const fn (jni.JNIEnv, jni.jbyteArray, jni.jsize, jni.jsize, [*]const u8) callconv(.c) void,
        jni.Index.SetByteArrayRegion,
    );
    set_region(env, array, 0, @intCast(bytes.len), bytes.ptr);
    return array;
}

/// Scroll the viewport. `tag` is a `GhosttyTerminalScrollViewportTag`.
export fn Java_dev_drosh_terminal_engine_native_NativeSession_nativeScroll(
    _: jni.JNIEnv,
    _: jni.jclass,
    handle: jni.jlong,
    tag: jni.jint,
    amount: jni.jint,
) jni.jint {
    const session = fromHandle(handle) orelse return -1;

    var behavior = std.mem.zeroes(c.GhosttyTerminalScrollViewport);
    behavior.tag = @intCast(tag);
    if (tag == c.GHOSTTY_SCROLL_VIEWPORT_DELTA) {
        behavior.value.delta = amount;
    } else if (tag == c.GHOSTTY_SCROLL_VIEWPORT_ROW) {
        behavior.value.row = if (amount > 0) @intCast(amount) else 0;
    }
    c.ghostty_terminal_scroll_viewport(session.terminal, behavior);
    return 0;
}

inline fn c_vt_write(session: *Session, bytes: []const u8) void {
    c.ghostty_terminal_vt_write(session.terminal, bytes.ptr, bytes.len);
}

/// The frame layout constants, so Kotlin and Zig agree from one place.
export fn Java_dev_drosh_terminal_engine_native_NativeFrame_nativeLayout(
    _: jni.JNIEnv,
    _: jni.jclass,
) jni.jlong {
    // header_bytes | cell_bytes << 16 | version << 32 | magic << 32
    return @intCast(frame.HEADER_BYTES |
        (@as(u64, frame.CELL_BYTES) << 16) |
        (@as(u64, frame.VERSION) << 32));
}
