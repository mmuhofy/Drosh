//! The PTY and the process on it, across the JNI boundary.
//!
//! A session is a terminal *and* a process; this file is the process half. It
//! is separate from `session.zig` because the two fail for different reasons —
//! a PTY that will not open is a permissions or a rootfs problem, a terminal
//! that will not render is a grid problem — and a diagnostic that names the
//! wrong half is worse than none.

const std = @import("std");
const jni = @import("types.zig");
const pty = @import("../pty.zig");

const allocator = @import("../alloc.zig").allocator;

inline fn fromHandle(handle: jni.jlong) ?*pty.Pty {
    if (handle <= 0) return null;
    return @ptrFromInt(@as(usize, @intCast(handle)));
}

inline fn toHandle(value: ?*pty.Pty) jni.jlong {
    if (value == null) return 0;
    return @intCast(@intFromPtr(value.?));
}

/// Spawn a child on a new pseudoterminal.
///
/// `argv` and `env` are newline-separated, the same shape the old engine used
/// so the Kotlin side did not have to learn a second convention. Returns the
/// PTY handle, or 0 when it could not be opened.
export fn Java_dev_drosh_terminal_engine_native_NativePty_nativeSpawn(
    env: jni.JNIEnv,
    _: jni.jclass,
    argv_text: jni.jstring,
    env_text: jni.jstring,
    cwd_text: jni.jstring,
    columns: jni.jint,
    rows: jni.jint,
    cell_width: jni.jint,
    cell_height: jni.jint,
) jni.jlong {
    if (columns <= 0 or rows <= 0) return 0;

    var arena = std.heap.ArenaAllocator.init(allocator);
    defer arena.deinit();
    const arena_alloc = arena.allocator();

    // Java strings become null-terminated copies. They live until the spawn
    // returns, which is all the child needs: `execve` copies them.
    const argv_joined = readString(env, argv_text, arena_alloc) catch return 0;
    const env_joined = readString(env, env_text, arena_alloc) catch return 0;
    const cwd = readString(env, cwd_text, arena_alloc) catch return 0;

    const argv = splitLines(argv_joined, arena_alloc) catch return 0;
    const envp = splitLines(env_joined, arena_alloc) catch return 0;
    if (argv.len == 0) return 0;

    const size = pty.Winsize{
        .rows = @intCast(rows),
        .cols = @intCast(columns),
        .xpixel = @intCast(@max(columns, 1) * @max(cell_width, 1)),
        .ypixel = @intCast(@max(rows, 1) * @max(cell_height, 1)),
    };

    const cwd_z: ?[*:0]const u8 = if (cwd.len == 0) null else cwd.ptr;

    const created = allocator.create(pty.Pty) catch return 0;
    created.* = pty.Pty.spawn(argv, envp, size, cwd_z) catch {
        allocator.destroy(created);
        return 0;
    };
    return toHandle(created);
}

/// Close the PTY and reap the child. A zero handle is a no-op.
export fn Java_dev_drosh_terminal_engine_native_NativePty_nativeClose(
    _: jni.JNIEnv,
    _: jni.jclass,
    handle: jni.jlong,
) void {
    const created = fromHandle(handle) orelse return;
    created.close();
    allocator.destroy(created);
}

/// Read whatever the child has written.
///
/// Returns the number of bytes written into `out`, 0 when there is nothing to
/// read, or a negative code. Zero is the normal answer between keystrokes.
export fn Java_dev_drosh_terminal_engine_native_NativePty_nativeRead(
    env: jni.JNIEnv,
    _: jni.jclass,
    handle: jni.jlong,
    out: jni.jbyteArray,
    capacity: jni.jint,
) jni.jint {
    const created = fromHandle(handle) orelse return -1;
    if (out == null or capacity <= 0) return -1;

    var stack: [8192]u8 = undefined;
    var heap: ?[]u8 = null;
    defer if (heap) |slice| allocator.free(slice);

    const wanted: usize = @intCast(capacity);
    const buffer: []u8 = if (wanted <= stack.len)
        stack[0..wanted]
    else blk: {
        const slice = allocator.alloc(u8, wanted) catch return -2;
        heap = slice;
        break :blk slice;
    };

    const read = created.read(buffer) catch return -3;
    if (read == 0) return 0;

    const set_region = jni.call(
        env,
        *const fn (jni.JNIEnv, jni.jbyteArray, jni.jsize, jni.jsize, [*]const u8) callconv(.c) void,
        jni.Index.SetByteArrayRegion,
    );
    set_region(env, out, 0, @intCast(read), buffer.ptr);
    return @intCast(read);
}

/// Write bytes to the child. Returns 0 on success.
export fn Java_dev_drosh_terminal_engine_native_NativePty_nativeWrite(
    env: jni.JNIEnv,
    _: jni.jclass,
    handle: jni.jlong,
    data: jni.jbyteArray,
    length: jni.jint,
) jni.jint {
    const created = fromHandle(handle) orelse return -1;
    if (data == null or length < 0) return -1;

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

    created.write(buffer) catch return -3;
    return 0;
}

/// Tell the kernel the terminal changed size.
export fn Java_dev_drosh_terminal_engine_native_NativePty_nativeResize(
    _: jni.JNIEnv,
    _: jni.jclass,
    handle: jni.jlong,
    columns: jni.jint,
    rows: jni.jint,
    cell_width: jni.jint,
    cell_height: jni.jint,
) jni.jint {
    const created = fromHandle(handle) orelse return -1;
    if (columns <= 0 or rows <= 0) return -1;

    created.resize(.{
        .rows = @intCast(rows),
        .cols = @intCast(columns),
        .xpixel = @intCast(@max(columns, 1) * @max(cell_width, 1)),
        .ypixel = @intCast(@max(rows, 1) * @max(cell_height, 1)),
    }) catch return -1;
    return 0;
}

/// The child's exit status, or -1 while it runs.
export fn Java_dev_drosh_terminal_engine_native_NativePty_nativeTryWait(
    _: jni.JNIEnv,
    _: jni.jclass,
    handle: jni.jlong,
) jni.jint {
    const created = fromHandle(handle) orelse return -1;
    return created.tryWait() orelse -1;
}

// --- strings ---------------------------------------------------------------

/// Read a Java string into a null-terminated UTF-8 buffer.
fn readString(
    env: jni.JNIEnv,
    text: jni.jstring,
    arena: std.mem.Allocator,
) ![:0]u8 {
    if (text == null) return arena.dupeZ(u8, "");

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

    return arena.dupeZ(u8, std.mem.span(chars));
}

/// Split a newline-separated list into null-terminated entries.
fn splitLines(
    text: [:0]const u8,
    arena: std.mem.Allocator,
) ![]const [*:0]const u8 {
    var list = std.ArrayListUnmanaged([*:0]const u8){};
    errdefer list.deinit(arena);

    if (text.len == 0) {
        return list.toOwnedSlice(arena);
    }

    var it = std.mem.splitScalar(u8, text, '\n');
    while (it.next()) |line| {
        if (line.len == 0) continue;
        const copy = try arena.dupeZ(u8, line);
        try list.append(arena, copy.ptr);
    }
    return list.toOwnedSlice(arena);
}
