//! The allocator every allocation in this library goes through.
//!
//! A single `c_allocator`: the library is called from Java, its lifetime is the
//! process's, and a general-purpose allocator's bookkeeping would buy nothing
//! for buffers that are allocated once and freed at exit.
//!
//! In its own file rather than in `main.zig` because everything needs it and
//! `main.zig` needs everything: a file that imports the root to reach the
//! allocator is a cycle, and a cycle is a compile error the moment anything is
//! evaluated at comptime.

const std = @import("std");

pub const allocator = std.heap.c_allocator;
