//! Keyboard input: Android's key codes become the engine's, and the engine
//! becomes escape sequences.
//!
//! ## Why the engine encodes
//!
//! A terminal's escape sequences depend on state the terminal owns: whether the
//! application asked for cursor keys in `DECCKM` form (`ESC O A` rather than
//! `ESC [ A`), whether the kitty keyboard protocol is on, whether a modifier is
//! being reported as a parameter or as a prefix. The previous engine
//! reimplemented those rules in Kotlin — from alacritty's source, correctly, but
//! still a copy — and every copy is a place to be wrong.
//!
//! `libghostty-vt` owns the rules. This file translates Android's vocabulary
//! into the engine's and lets the engine decide the bytes.
//!
//! ## The three layers
//!
//! 1. **Named keys** — arrows, F1–F12, Home/End, PgUp/PgDn, Insert/Delete, Tab,
//!    Escape, Enter, Backspace. `GhosttyKey` has a name for every one.
//! 2. **Modified characters** — Ctrl and Alt. The engine turns `Ctrl+C` into
//!    `0x03` and `Alt+b` into `ESC b`.
//! 3. **Plain characters** — the unicode the IME committed. The engine encodes
//!    them as UTF-8, with the kitty protocol's form when the application asked
//!    for it.

const std = @import("std");
const ghostty = @import("../ghostty.zig");
const c = ghostty.c;
const jni = @import("types.zig");

const allocator = @import("../alloc.zig").allocator;

/// One key encoder per session, kept alive between frames.
///
/// Created lazily: a session that never receives a key never allocates one.
pub const KeyEncoder = struct {
    handle: c.GhosttyKeyEncoder = null,

    /// The output buffer, reused. A key sequence is never longer than this;
    /// the kitty protocol's longest form is about forty bytes.
    buffer: [256]u8 = undefined,

    pub fn ensure(self: *KeyEncoder, terminal: ghostty.Terminal) bool {
        if (self.handle != null) return true;
        if (c.ghostty_key_encoder_new(null, &self.handle) != ghostty.SUCCESS) {
            return false;
        }
        // The encoder reads the terminal's modes — DECCKM, the kitty flags —
        // from the terminal itself, so the two cannot disagree about which form
        // a key takes.
        c.ghostty_key_encoder_setopt_from_terminal(self.handle, terminal);
        return true;
    }

    pub fn destroy(self: *KeyEncoder) void {
        if (self.handle == null) return;
        c.ghostty_key_encoder_free(self.handle);
        self.handle = null;
    }

    /// Encode one key event. Returns the bytes, or an empty slice when the key
    /// produced nothing.
    pub fn encode(
        self: *KeyEncoder,
        terminal: ghostty.Terminal,
        key: c.GhosttyKey,
        mods: c.GhosttyMods,
        action: c.GhosttyKeyAction,
        utf8: []const u8,
        unshifted: u32,
    ) []const u8 {
        if (!self.ensure(terminal)) return &.{};

        // The modes can change between keys — a program turning on the kitty
        // protocol mid-session — so the encoder is re-read every time. It is a
        // struct copy, not a JNI call.
        c.ghostty_key_encoder_setopt_from_terminal(self.handle, terminal);

        var event: c.GhosttyKeyEvent = null;
        if (c.ghostty_key_event_new(null, &event) != ghostty.SUCCESS) return &.{};
        defer c.ghostty_key_event_free(event);

        c.ghostty_key_event_set_key(event, key);
        c.ghostty_key_event_set_mods(event, mods);
        c.ghostty_key_event_set_action(event, action);
        if (utf8.len > 0) {
            c.ghostty_key_event_set_utf8(event, utf8.ptr, utf8.len);
        }
        c.ghostty_key_event_set_unshifted_codepoint(event, unshifted);

        var written: usize = 0;
        const result = c.ghostty_key_encoder_encode(
            self.handle,
            event,
            &self.buffer,
            self.buffer.len,
            &written,
        );
        if (result != ghostty.SUCCESS or written == 0) return &.{};
        return self.buffer[0..written];
    }
};

// --- Android key codes ------------------------------------------------------
//
// The mapping is Android's vocabulary to the engine's. It is a table rather
// than a chain of `if`s because a key that is missing is a key that silently
// does nothing, and a table can be read.

const KEY_MAP = [_]struct { android: i32, ghostty: c.GhosttyKey }{
    .{ .android = 19, .ghostty = c.GHOSTTY_KEY_ARROW_UP }, // KEYCODE_DPAD_UP
    .{ .android = 20, .ghostty = c.GHOSTTY_KEY_ARROW_DOWN },
    .{ .android = 21, .ghostty = c.GHOSTTY_KEY_ARROW_LEFT },
    .{ .android = 22, .ghostty = c.GHOSTTY_KEY_ARROW_RIGHT },
    .{ .android = 23, .ghostty = c.GHOSTTY_KEY_ENTER }, // KEYCODE_DPAD_CENTER
    .{ .android = 66, .ghostty = c.GHOSTTY_KEY_ENTER },
    .{ .android = 67, .ghostty = c.GHOSTTY_KEY_BACKSPACE },
    .{ .android = 111, .ghostty = c.GHOSTTY_KEY_ESCAPE }, // KEYCODE_ESCAPE
    .{ .android = 4, .ghostty = c.GHOSTTY_KEY_ESCAPE }, // KEYCODE_BACK
    .{ .android = 61, .ghostty = c.GHOSTTY_KEY_TAB },
    .{ .android = 122, .ghostty = c.GHOSTTY_KEY_HOME }, // KEYCODE_MOVE_HOME
    .{ .android = 123, .ghostty = c.GHOSTTY_KEY_END }, // KEYCODE_MOVE_END
    .{ .android = 92, .ghostty = c.GHOSTTY_KEY_PAGE_UP },
    .{ .android = 93, .ghostty = c.GHOSTTY_KEY_PAGE_DOWN },
    .{ .android = 124, .ghostty = c.GHOSTTY_KEY_INSERT },
    .{ .android = 112, .ghostty = c.GHOSTTY_KEY_DELETE }, // KEYCODE_FORWARD_DEL
    .{ .android = 131, .ghostty = c.GHOSTTY_KEY_F1 },
    .{ .android = 132, .ghostty = c.GHOSTTY_KEY_F2 },
    .{ .android = 133, .ghostty = c.GHOSTTY_KEY_F3 },
    .{ .android = 134, .ghostty = c.GHOSTTY_KEY_F4 },
    .{ .android = 135, .ghostty = c.GHOSTTY_KEY_F5 },
    .{ .android = 136, .ghostty = c.GHOSTTY_KEY_F6 },
    .{ .android = 137, .ghostty = c.GHOSTTY_KEY_F7 },
    .{ .android = 138, .ghostty = c.GHOSTTY_KEY_F8 },
    .{ .android = 139, .ghostty = c.GHOSTTY_KEY_F9 },
    .{ .android = 140, .ghostty = c.GHOSTTY_KEY_F10 },
    .{ .android = 141, .ghostty = c.GHOSTTY_KEY_F11 },
    .{ .android = 142, .ghostty = c.GHOSTTY_KEY_F12 },
    .{ .android = 126, .ghostty = c.GHOSTTY_KEY_MEDIA_PLAY_PAUSE },
    .{ .android = 127, .ghostty = c.GHOSTTY_KEY_MEDIA_STOP },
    .{ .android = 85, .ghostty = c.GHOSTTY_KEY_MEDIA_PLAY_PAUSE },
    .{ .android = 86, .ghostty = c.GHOSTTY_KEY_MEDIA_STOP },
    .{ .android = 87, .ghostty = c.GHOSTTY_KEY_MEDIA_TRACK_NEXT },
    .{ .android = 88, .ghostty = c.GHOSTTY_KEY_MEDIA_TRACK_PREVIOUS },
};

/// The engine's name for an Android key code, or null when the engine has no
/// name for it — a character key, which travels as text instead.
fn keyFor(android: i32) ?c.GhosttyKey {
    for (KEY_MAP) |entry| {
        if (entry.android == android) return entry.ghostty;
    }
    return null;
}

/// Android's modifier bits, as the engine's.
///
/// Android reports a side for every modifier; the engine's own bits are
/// separate fields, so both are set. The right Alt is distinguished because it
/// is AltGr on a European layout — a compose key, not an escape prefix.
fn modsFor(meta: i32) c.GhosttyMods {
    var mods: c.GhosttyMods = 0;
    // KeyEvent.META_SHIFT_ON = 1, META_ALT_ON = 2, META_CTRL_ON = 0x1000,
    // META_META_ON = 0x10000.
    if (meta & 0x1 != 0) mods |= c.GHOSTTY_MODS_SHIFT;
    if (meta & 0x2 != 0) mods |= c.GHOSTTY_MODS_ALT;
    if (meta & 0x1000 != 0) mods |= c.GHOSTTY_MODS_CTRL;
    if (meta & 0x10000 != 0) mods |= c.GHOSTTY_MODS_SUPER;
    // META_ALT_RIGHT_ON = 0x20, META_SHIFT_RIGHT_ON = 0x40,
    // META_CTRL_RIGHT_ON = 0x2000, META_META_RIGHT_ON = 0x20000.
    if (meta & 0x20 != 0) mods |= c.GHOSTTY_MODS_ALT_SIDE;
    if (meta & 0x40 != 0) mods |= c.GHOSTTY_MODS_SHIFT_SIDE;
    if (meta & 0x2000 != 0) mods |= c.GHOSTTY_MODS_CTRL_SIDE;
    if (meta & 0x20000 != 0) mods |= c.GHOSTTY_MODS_SUPER_SIDE;
    return mods;
}

// --- the JNI entry points ---------------------------------------------------

/// Encode one key event.
///
/// `keyCode` is Android's; `metaState` is Android's; `action` is 0 press,
/// 1 release, 2 repeat. `utf8` is the character the key produced, when it
/// produced one.
///
/// The bytes are written into `out`; returns the count, 0 when the key produced
/// nothing, or a negative code.
export fn Java_dev_drosh_terminal_engine_native_NativeInput_nativeEncodeKey(
    env: jni.JNIEnv,
    _: jni.jclass,
    handle: jni.jlong,
    keyCode: jni.jint,
    metaState: jni.jint,
    action: jni.jint,
    utf8: jni.jstring,
    out: jni.jbyteArray,
) jni.jint {
    const session = @import("../session.zig").Session.fromHandle(handle) orelse return -1;
    if (out == null) return -1;

    var arena = std.heap.ArenaAllocator.init(allocator);
    defer arena.deinit();

    const text = readString(env, utf8, arena.allocator()) catch &.{};
    const unshifted: u32 = if (text.len > 0) @intCast(std.unicode.utf8Decode(text) catch 0) else 0;

    const ghosttyKey = keyFor(keyCode) orelse c.GHOSTTY_KEY_UNIDENTIFIED;
    const mods = modsFor(metaState);
    const keyAction: c.GhosttyKeyAction = switch (action) {
        0 => c.GHOSTTY_KEY_ACTION_PRESS,
        1 => c.GHOSTTY_KEY_ACTION_RELEASE,
        2 => c.GHOSTTY_KEY_ACTION_REPEAT,
        else => c.GHOSTTY_KEY_ACTION_PRESS,
    };

    const bytes = session.keys.encode(
        session.terminal,
        ghosttyKey,
        mods,
        keyAction,
        text,
        unshifted,
    );
    if (bytes.len == 0) return 0;

    const set_region = jni.call(
        env,
        *const fn (jni.JNIEnv, jni.jbyteArray, jni.jsize, jni.jsize, [*]const u8) callconv(.c) void,
        jni.Index.SetByteArrayRegion,
    );
    set_region(env, out, 0, @intCast(bytes.len), bytes.ptr);
    return @intCast(bytes.len);
}

/// Read a Java string into UTF-8 bytes. An empty slice when it is null.
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
