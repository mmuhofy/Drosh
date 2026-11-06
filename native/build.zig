const std = @import("std");

/// libdrosh — the Drosh terminal's native half.
///
/// Built for Android only. The library links against `libghostty-vt.so`, which
/// `tools/fetch-ghostty-vt.sh` places in `ghostty/lib/<abi>/`; it is not
/// committed and it is not built here.
pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});

    const root = b.path("src/main.zig");

    // The shared library the JVM loads: libdrosh.so.
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
    lib.root_module.addLibraryPath(b.path("ghostty/lib"));
    lib.root_module.linkSystemLibrary("ghostty-vt", .{});

    // The JVM's own symbols are resolved at runtime by the process, so the
    // link is deliberately lazy — but Android's linker still wants liblog for
    // the log calls the JNI layer makes.
    lib.root_module.linkSystemLibrary("log", .{});

    b.installArtifact(lib);

    // `zig build test` — the parts that do not need a JVM.
    const tests = b.addTest(.{
        .root_module = b.createModule(.{
            .root_source_file = root,
            .target = target,
            .optimize = optimize,
            .link_libc = true,
        }),
    });
    tests.root_module.addIncludePath(b.path("ghostty/include"));
    tests.root_module.addLibraryPath(b.path("ghostty/lib"));
    tests.root_module.linkSystemLibrary("ghostty-vt", .{});
    tests.root_module.linkSystemLibrary("log", .{});

    const run_tests = b.addRunArtifact(tests);
    const test_step = b.step("test", "Run the native tests");
    test_step.dependOn(&run_tests.step);
}
