package dev.drosh.linux

import java.io.File

/**
 * Drives `libchrootng.so` — the ptrace-free chroot/bind emulator from the
 * chroot-ng project (a fork of `sylirre/fake-chroot-ng`).
 *
 * chroot-ng intercepts only path-bearing syscalls in-process (seccomp
 * `RET_TRAP` → `SIGSYS`) and runs guest programs with its own userland ELF
 * loader, so it works on static / Go / Rust / raw-syscall binaries where
 * `LD_PRELOAD` tricks and proot's per-syscall ptrace both fail.
 *
 * The binary comes from the chroot-ng project's own release, pinned by tag and
 * SHA-256 in CI (`.github/workflows/debug.yml`), and lands in the app's
 * `nativeLibraryDir` — the one directory an app may exec from.
 *
 * ## Two things differ from proot
 *
 * **The environment is built, not inherited.** chroot-ng starts the guest from
 * a clean environment and carries over only `TERM` and `COLORTERM`, because a
 * host variable describes the host rather than the rootfs. Every other guest
 * variable is therefore spelled out as `-E VAR=VAL` on the argv, and the
 * process environment handed to chroot-ng itself carries only `TERM` — so
 * nothing of the Android app's namespace leaks into the guest.
 *
 * **`/proc` and `/dev` need no binds.** chroot-ng synthesises both, and
 * deliberately keeps them narrower than a real device's: the guest sees a `/proc`
 * that describes the guest rather than the host, and a `/dev` with a fixed
 * whitelist of nodes. The binds below are the ones a guest genuinely needs to
 * see the device: the storage the user's files live in, and `/data`, which
 * proot exposed for the same reason.
 */
class ChrootNgRunner(
    private val rootfsDir: File,
    private val nativeLibDir: String,
    /**
     * Host paths the guest sees as themselves.
     *
     * A parameter rather than a constant so the argv can be tested without a
     * device: what the guest needs to see is the storage the user's files live
     * in, and `/data`, which proot exposed for the same reason.
     */
    private val devicePaths: List<String> = listOf("/data", "/storage", "/sdcard"),
) : GuestLauncher {

    private val chrootNgPath: String get() = "$nativeLibDir/libchrootng.so"
    private val rootfsPath: String get() = rootfsDir.absolutePath

    /**
     * The binds, as `SRC:DST` pairs.
     *
     * chroot-ng refuses a bind whose source does not exist, so each one is
     * checked — a device without secondary storage is normal, not an error.
     */
    private fun deviceBinds(): List<String> =
        devicePaths
            .filter { File(it).isDirectory }
            .map { "$it:$it" }

    /** The guest's home, which is where a session starts when nothing says otherwise. */
    private fun defaultWorkingDirectory(): String =
        if (File(rootfsPath, "home").isDirectory) "/home" else "/root"

    override fun build(spec: GuestSpec): GuestCommand {
        val argv = mutableListOf<String>().apply {
            add(chrootNgPath)
            add("-u") // fake root identity (uid/gid 0)
            add("-l") // emulate hardlinks where the host refuses link(2)
            add("-R") // ahead-of-time svc rewriting
            deviceBinds().forEach { bind ->
                add("-b")
                add(bind)
            }
            // The work directory is a *guest* path and must exist inside the
            // rootfs or in a bind; chroot-ng refuses a bad one rather than
            // quietly demoting it to /. Without one it would start the guest at
            // the rootfs root, so the home is named explicitly — which is what
            // proot does too.
            add("-w")
            add(spec.workingDirectory ?: defaultWorkingDirectory())
            // Every option comes before the rootfs: chroot-ng stops parsing
            // options at the rootfs, so an -E after it would be taken for the
            // program to run.
            guestEnvironment(spec).forEach { entry ->
                add("-E")
                add(entry)
            }
            add(rootfsPath)
            if (spec.command.isNotBlank()) {
                add(spec.shell)
                add("-c")
                add(spec.command)
            } else {
                add(spec.shell)
                addAll(spec.shellArgs)
                add("--login")
            }
        }

        return GuestCommand(
            executable = chrootNgPath,
            argv = argv,
            cwd = rootfsPath,
            // Only TERM crosses over; chroot-ng builds the rest itself.
            environment = listOf("TERM=xterm-256color"),
        )
    }

    override fun buildCommand(spec: GuestSpec, command: String): GuestCommand {
        // chroot-ng runs a single program, so a one-shot command is the shell
        // running it — the same shape proot uses, so the output is line-buffered
        // and the caller sees it as it arrives.
        val oneShot = spec.copy(
            command = "stdbuf -oL $command",
            shellArgs = emptyList(),
        )
        return build(oneShot)
    }

    /**
     * The guest's environment, as `-E` arguments.
     *
     * The values here are the ones the app needs the guest to have; anything
     * the shell integration asks for is added on top, so a hook can override a
     * default (the shell's own path, the locale) the way it does under proot.
     */
    private fun guestEnvironment(spec: GuestSpec): List<String> {
        val environment = linkedMapOf(
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "HOME" to "/home",
            "LANG" to "C.UTF-8",
            "TMPDIR" to "/tmp",
            "SHELL" to spec.shell,
        )
        environment.putAll(spec.environment)
        return environment.map { (name, value) -> "$name=$value" }
    }
}
