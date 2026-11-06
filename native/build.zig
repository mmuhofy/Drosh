const std = @import("std");

/// libdrosh — the Drosh terminal's native half.
///
/// Built for Android only. The library links against `libghostty-vt.so`, which
/// `tools/fetch-ghostty-vt.sh` places in `ghostty/lib/<abi>/`; it is not
/// committed and it is not built here.
pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});

    // Which ABI's copy of libghostty-vt.so to link against. The fetched
    // release keeps one directory per ABI, and the linker needs the one that
    // matches the target.
    const abi = b.option(
        []const u8,
        "ghostty-abi",
        "ABI directory under ghostty/lib to link against (default: arm64-v8a)",
    ) orelse "arm64-v8a";

    // The NDK sysroot, for liblog. Zig does not know where the NDK lives, and
    // a system library the platform provides has to be found by path.
    const sysroot = b.option(
        []const u8,
        "ndk-sysroot",
        "NDK sysroot for the target (the directory holding usr/lib/<triple>)",
    );

    const root = b.path("src/main.zig");

    const lib = b.addLibrary(.{
        .name = "drosh",
        .root_module = b.createModule(.{
            .root_source_file = root,
            .target = target,
            .optimize = optimize,
            // The JNI headers come from the NDK's sysroot; the ghostty headers
            // come from the fetched release.
            .link_libc = true,
        }),
        .linkage = .dynamic,
    });

    lib.root_module.addIncludePath(b.path("ghostty/include"));

    // libghostty-vt.so is a dynamic dependency, not a static one: it is
    // shipped next to this library in the APK's jniLibs and loaded by the
    // system linker by name.
    lib.root_module.addLibraryPath(b.path(b.fmt("ghostty/lib/{s}", .{abi})));
    lib.root_module.linkSystemLibrary("ghostty-vt", .{});

    // Android's liblog, from the NDK sysroot when one is given.
    if (sysroot) |path| {
        lib.root_module.addLibraryPath(.{ .cwd_relative = path });
        lib.root_module.addSystemIncludePath(.{ .cwd_relative = path ++ "/usr/include" });
    }
    lib.root_module.linkSystemLibrary("log", .{});

    // The DT_NEEDED entry has to be the soname the device will resolve, not a
    // build-time path.
    lib.root_module.addRPathSpecial("$ORIGIN");

    b.installArtifact(lib);

    const tests = b.addTest(.{
        .root_module = b.createModule(.{
            .root_source_file = root,
            .target = target,
            .optimize = optimize,
            .link_libc = true,
        }),
    });
    tests.root_module.addIncludePath(b.path("ghostty/include"));
    tests.root_module.addLibraryPath(b.path(b.fmt("ghostty/lib/{s}", .{abi})));
    tests.root_module.linkSystemLibrary("ghostty-vt", .{});
    if (sysroot) |path| {
        tests.root_module.addLibraryPath(.{ .cwd_relative = path });
        tests.root_module.addSystemIncludePath(.{ .cwd_relative = path ++ "/usr/include" });
    }
    tests.root_module.linkSystemLibrary("log", .{});

    const run_tests = b.addRunArtifact(tests);
    const test_step = b.step("test", "Run the native tests");
    test_step.dependOn(&run_tests.step);
}
