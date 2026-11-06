//! The pseudoterminal and the child process on the far end of it.
//!
//! Taken from Ghostty's `src/pty.zig` (MIT), reduced to what Android needs. The
//! structure is Ghostty's on purpose: `forkpty` + `setsid` + `TIOCSCTTY` is the
//! part of a terminal that is easy to get subtly wrong — a missing `setsid`
//! leaves the shell in the app's process group, so a Ctrl+C kills the app
//! instead of the command, and a missing `TIOCSCTTY` leaves `^Z` doing nothing.
//!
//! ## Why the libc functions are declared here
//!
//! `@cImport` of the NDK's `<pty.h>`/`<termios.h>` does not translate: bionic's
//! headers carry C23 `_Nullable` specifiers and a `bits/ioctl.h` that redefines
//! `ioctl` with a different prototype, and translate-c rejects both. The
//! declarations below are the same functions with the same signatures, written
//! out — six of them, which is a smaller surface than the headers and one that
//! can be read.
//!
//! ## What is different from Ghostty
//!
//! - **No `xev`.** Ghostty reads the master through its own event loop; this
//!   library exposes a non-blocking read and lets the caller decide when.
//! - **No Windows.** Android is the only target.
//! - **The child is a guest launcher**, not a shell: the caller passes argv and
//!   envp, and the launcher decides what to exec inside the rootfs.

const std = @import("std");
const posix = std.posix;

// --- libc, declared ---------------------------------------------------------

const c = struct {
    pub const winsize = extern struct {
        ws_row: u16,
        ws_col: u16,
        ws_xpixel: u16,
        ws_ypixel: u16,
    };

    pub extern "c" fn forkpty(
        amaster: *c_int,
        name: ?[*]u8,
        termp: ?*const anyopaque,
        winp: ?*const winsize,
    ) c_int;

    pub extern "c" fn ioctl(fd: c_int, request: c_ulong, ...) c_int;
    pub extern "c" fn fcntl(fd: c_int, cmd: c_int, ...) c_int;
    pub extern "c" fn read(fd: c_int, buf: [*]u8, count: usize) isize;
    pub extern "c" fn write(fd: c_int, buf: [*]const u8, count: usize) isize;
    pub extern "c" fn close(fd: c_int) c_int;
    pub extern "c" fn waitpid(pid: c_int, status: *c_int, options: c_int) c_int;
    pub extern "c" fn kill(pid: c_int, sig: c_int) c_int;
    pub extern "c" fn execve(path: [*:0]const u8, argv: [*:null]const ?[*:0]const u8, envp: [*:null]const ?[*:0]const u8) c_int;
    pub extern "c" fn chdir(path: [*:0]const u8) c_int;
    pub extern "c" fn _exit(status: c_int) noreturn;

    // fcntl commands and flags.
    pub const F_GETFL: c_int = 3;
    pub const F_SETFL: c_int = 4;
    pub const O_NONBLOCK: c_int = 0o4000;

    // ioctl requests.
    pub const TIOCSWINSZ: c_ulong = 0x5414;
    pub const TIOCGWINSZ: c_ulong = 0x5413;

    // waitpid options and status decoding.
    pub const WNOHANG: c_int = 1;
    pub fn WIFEXITED(status: c_int) bool {
        return (status & 0x7f) == 0;
    }
    pub fn WEXITSTATUS(status: c_int) c_int {
        return (status >> 8) & 0xff;
    }
    pub fn WIFSIGNALED(status: c_int) bool {
        return ((status & 0x7f) + 1) >> 1 > 0;
    }
    pub fn WTERMSIG(status: c_int) c_int {
        return status & 0x7f;
    }

    pub const SIGKILL: c_int = 9;
};

/// The window size, in cells and pixels.
///
/// The same layout as `struct winsize`, so it can be passed to `ioctl`
/// directly. Pixel sizes are what a program uses to place images and to decide
/// whether a mouse is precise enough for a TUI.
pub const Winsize = extern struct {
    rows: u16 = 24,
    cols: u16 = 80,
    xpixel: u16 = 0,
    ypixel: u16 = 0,
};

pub const PtyError = error{
    OpenFailed,
    ForkFailed,
    ResizeFailed,
    ReadFailed,
    WriteFailed,
    ExecFailed,
    NotOpen,
};

/// A pseudoterminal pair and the child process on the slave side.
pub const Pty = struct {
    /// The master side: the app reads and writes this.
    master: posix.fd_t = -1,
    /// The child's process id, or -1 when it has been reaped.
    child: posix.pid_t = -1,
    /// The size last set, so a resize to the same size is free.
    size: Winsize = .{},

    /// True while the master is open.
    pub fn isAlive(self: *const Pty) bool {
        return self.master >= 0;
    }

    /// Open a pseudoterminal and fork the child into it.
    ///
    /// `argv` and `envp` must be null-terminated. The child execs `argv[0]`
    /// immediately after the terminal is set up.
    pub fn spawn(
        argv: []const [*:0]const u8,
        envp: []const [*:0]const u8,
        size: Winsize,
        cwd: ?[*:0]const u8,
        arena: std.mem.Allocator,
    ) PtyError!Pty {
        if (argv.len == 0) return error.ExecFailed;

        // `execve` wants a null-terminated vector of pointers. The arena owns
        // it, and the child only needs it until it execs.
        const argv_z = arena.allocSentinel(?[*:0]const u8, argv.len, null) catch return error.ExecFailed;
        for (argv, 0..) |arg, index| argv_z[index] = arg;
        const envp_z = arena.allocSentinel(?[*:0]const u8, envp.len, null) catch return error.ExecFailed;
        for (envp, 0..) |entry, index| envp_z[index] = entry;

        var master: c_int = -1;
        var winsize = c.winsize{
            .ws_row = size.rows,
            .ws_col = size.cols,
            .ws_xpixel = size.xpixel,
            .ws_ypixel = size.ypixel,
        };

        // `forkpty` returns 0 in the child, the pid in the parent, -1 on
        // failure. It does the openpty, the setsid and the TIOCSCTTY.
        const pid = c.forkpty(&master, null, null, &winsize);
        if (pid < 0) return error.ForkFailed;

        if (pid == 0) {
            // --- child ---------------------------------------------------
            //
            // Only async-signal-safe calls from here to exec. The JVM may have
            // had other threads mid-allocation when this forked, so anything
            // that takes a libc lock can deadlock the child forever.
            if (cwd) |dir| {
                _ = c.chdir(dir);
            }
            _ = c.execve(argv[0], argv_z.ptr, envp_z.ptr);
            c._exit(127);
        }

        // --- parent ------------------------------------------------------
        if (master < 0) {
            _ = c.kill(pid, c.SIGKILL);
            return error.OpenFailed;
        }

        // Non-blocking: the caller polls. A blocking read here would freeze
        // whichever thread called it, and the caller is the render loop.
        const flags = c.fcntl(master, c.F_GETFL);
        if (flags >= 0) {
            _ = c.fcntl(master, c.F_SETFL, flags | c.O_NONBLOCK);
        }

        return .{ .master = master, .child = pid, .size = size };
    }

    /// Close the master and reap the child. Safe to call twice.
    pub fn close(self: *Pty) void {
        if (self.master >= 0) {
            _ = c.close(self.master);
            self.master = -1;
        }
        if (self.child > 0) {
            // Reap without blocking: the caller may close while the child is
            // still shutting down, and a blocking wait there is a hang.
            var status: c_int = 0;
            _ = c.waitpid(self.child, &status, c.WNOHANG);
            self.child = -1;
        }
    }

    /// Read whatever is available. Returns 0 when there is nothing to read,
    /// which is the normal state and not an error.
    pub fn read(self: *Pty, buffer: []u8) PtyError!usize {
        if (self.master < 0) return error.NotOpen;

        while (true) {
            const n = c.read(self.master, buffer.ptr, buffer.len);
            if (n > 0) return @intCast(n);
            if (n == 0) return 0;

            switch (posix.errno(n)) {
                // No data right now.
                .AGAIN => return 0,
                // The child is gone and the slave side closed. That is a clean
                // end, not a failure; the session reports the exit.
                .IO => return 0,
                .INTR => continue,
                else => return error.ReadFailed,
            }
        }
    }

    /// Write all of `bytes`, or fail.
    ///
    /// A partial write is retried rather than truncated: a truncated escape
    /// sequence leaves the parser mid-state and the next byte is read as its
    /// continuation.
    pub fn write(self: *Pty, bytes: []const u8) PtyError!void {
        if (self.master < 0) return error.NotOpen;

        var offset: usize = 0;
        while (offset < bytes.len) {
            const n = c.write(self.master, bytes.ptr + offset, bytes.len - offset);
            if (n > 0) {
                offset += @intCast(n);
                continue;
            }
            if (n < 0) {
                switch (posix.errno(n)) {
                    .AGAIN, .INTR => continue,
                    else => return error.WriteFailed,
                }
            }
            return error.WriteFailed;
        }
    }

    /// Tell the kernel the terminal changed size.
    ///
    /// The kernel turns this into `SIGWINCH` for the foreground process group,
    /// which is how a shell learns to redraw its prompt and a TUI learns to
    /// re-lay itself out.
    pub fn resize(self: *Pty, size: Winsize) PtyError!void {
        if (self.master < 0) return error.NotOpen;
        if (size.rows == self.size.rows and size.cols == self.size.cols and
            size.xpixel == self.size.xpixel and size.ypixel == self.size.ypixel)
        {
            return;
        }

        const winsize = c.winsize{
            .ws_row = size.rows,
            .ws_col = size.cols,
            .ws_xpixel = size.xpixel,
            .ws_ypixel = size.ypixel,
        };
        if (c.ioctl(self.master, c.TIOCSWINSZ, &winsize) < 0) {
            return error.ResizeFailed;
        }
        self.size = size;
    }

    /// The exit status of the child, if it has exited. Null while it runs.
    pub fn tryWait(self: *Pty) ?i32 {
        if (self.child <= 0) return null;

        var status: c_int = 0;
        const result = c.waitpid(self.child, &status, c.WNOHANG);
        if (result <= 0) return null;

        self.child = -1;
        if (c.WIFEXITED(status)) return c.WEXITSTATUS(status);
        if (c.WIFSIGNALED(status)) return 128 + c.WTERMSIG(status);
        return 0;
    }
};
