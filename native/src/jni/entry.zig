//! The JNI boundary.
//!
//! Every function here is `extern "C"` with a JNI signature and nothing else:
//! it validates the handle, calls the behaviour, and turns a failure into a
//! Java exception. No terminal logic lives in this directory — that rule is
//! what keeps the boundary boring enough to audit.

const std = @import("std");

comptime {
    _ = @import("terminal.zig");
    _ = @import("smoke.zig");
}
