//! The pseudoterminal and the child process on the far end of it.
//!
//! Taken from Ghostty's `src/pty.zig` and `src/pty.c` (MIT), reduced to what
//! Android needs. The structure is Ghostty's on purpose: `openpty` + a
//! `posix_spawn`-shaped fork/exec is the part of a terminal that is easy to get
//! subtly wrong — a missing `setsid` leaves the shell in the app's process
//! group, so a Ctrl+C kills the app instead of the command, and a missing
//! `TIOCSCTTY` leaves `^Z` doing nothing.
//!
//! ## What is different from Ghostty
//!
//! - **No `xev`.** Ghostty reads the master through its own event loop; this
//!   library exposes a non-blocking read and lets the caller decide when.
//! - **No Windows.** Android is the only target.
//! - **The child is a guest launcher**, not a shell: the caller passes argv and
//!   envp, and the launcher decides what to exec inside the rootfs.
//!
//! ## Android specifics
//!
//! `forkpty` and `openpty` are in bionic since API 23 (see `pty.h`), so no
//! hand-rolled `posix_openpt`/`grantpt`/`unlockpt` is needed. The one thing
//! bionic does *not* do for us is `fork` safety across a JVM: the child must
//! only call async-signal-safe functions before `exec`, which is why the exec
//! path below touches nothing but `dup2`, `close`, `ioctl` and `execvp`.

const std = @import("std");
const builtin = @import("builtin");
const posix = std.posix;

const c = @cImport({
    @cInclude("pty.h");
    @cInclude("termios.h");
    @cInclude("unistd.h");
    @cInclude("sys/ioctl.h");
    @cInclude("sys/wait.h");
    @cInclude("signal.h");
    @cInclude("fcntl.h");
    @cInclude("errno.h");
});

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

    /// True while the master is open and the child has not been reaped.
    pub fn isAlive(self: *const Pty) bool {
        return self.master >= 0;
    }

    /// Open a pseudoterminal and fork the child into it.
    ///
    /// `argv` must be null-terminated; `envp` likewise. The child execs
    /// `argv[0]` immediately after the terminal is set up.
    pub fn spawn(
        argv: []const [*:0]const u8,
        envp: []const [*:0]const u8,
        size: Winsize,
        cwd: ?[*:0]const u8,
    ) PtyError!Pty {
        if (argv.len == 0) return error.ExecFailed;

        // `forkpty` returns 0 in the child, the pid in the parent, -1 on
        // failure. It does the openpty, the setsid and the TIOCSCTTY.
        var master: c_int = -1;
        var winsize = c.struct_winsize{
            .ws_row = size.rows,
            .ws_col = size.cols,
            .ws_xpixel = size.xpixel,
            .ws_ypixel = size.ypixel,
        };

        const pid = c.forkpty(&master, null, null, &winsize);
        if (pid < 0) return error.ForkFailed;

        if (pid == 0) {
            // --- child ---------------------------------------------------
            //
            // Only async-signal-safe calls from here to exec. The JVM may have
            // had other threads mid-allocation when this forked, so anything
            // that takes a lock in libc can deadlock the child forever.
            childExec(argv, envp, cwd);
            // childExec only returns on failure.
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
                // The child is gone and the slave side closed. That is a
                // clean end, not a failure; the session reports the exit.
                .IO => return 0,
                .INTR => continue,
                else => return error.ReadFailed,
            }
        }
    }

    /// Write all of `bytes`, or fail.
    ///
    /// A partial write is retried rather than truncated: a truncated escape
    /// sequence leaves the terminal's parser mid-state and the next byte is
    /// read as its continuation.
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
                    .AGAIN => continue,
                    .INTR => continue,
                    .IO => return error.WriteFailed,
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

        const winsize = c.struct_winsize{
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

/// Set up the terminal and exec. Only async-signal-safe calls.
fn childExec(
    argv: []const [*:0]const u8,
    envp: []const [*:0]const u8,
    cwd: ?[*:0]const u8,
) void {
    // The slave is already the controlling terminal and stdin/stdout/stderr —
    // forkpty did that. What is left is the working directory and the exec.

    if (cwd) |dir| {
        _ = c.chdir(dir);
    }

    // `execvpe` is not in bionic. `execve` takes the path as given, which is
    // what the caller passes: the guest launcher's absolute path.
    _ = c.execve(argv[0], @ptrCast(argv.ptr), @ptrCast(envp.ptr));
}
