package dev.drosh.linux

import android.util.Log
import java.io.File

/** The log tag the guest runtime's own messages go out under. */
private const val TAG = "DroshGuest"

/**
 * Drives `libchrootng.so` — the ptrace-free chroot/bind emulator from the
 * chroot-ng project (a fork of `sylirre/fake-chroot-ng`).
 *
 * chroot-ng intercepts only path-bearing syscalls in-process (seccomp
 * `RET_TRAP` → `SIGSYS`) and runs guest programs with its own userland ELF
 * loader, so it works on static / Go / Rust / raw-syscall binaries where an
 * `LD_PRELOAD` shim would not.
 *
 * The binary comes from the chroot-ng project's own release, pinned by tag and
 * SHA-256 in CI (`.github/workflows/debug.yml`), and lands in the app's
 * `nativeLibraryDir` — the one directory an app may exec from.
 *
 * ## Two things worth knowing
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

 */
class ChrootNgRunner(
    private val rootfsDir: File,
    /**
     * The staged runtime — the file the bootstrap put in the app's own
     * storage, not a path in the APK's native library folder.
     */
    private val runtimeFile: File,
    /**
     * Host paths the guest sees as themselves.
     *
     * A parameter rather than a constant so the argv can be tested without a
     * device: what the guest needs to see is the storage the user's files live
     * in, and `/data`.
     */
    private val devicePaths: List<String> = listOf("/data", "/storage", "/sdcard"),
) : GuestLauncher {

    private val chrootNgPath: String get() = runtimeFile.absolutePath
    private val rootfsPath: String get() = rootfsDir.absolutePath

    /**
     * Whether the packaged runtime is there.
     *
     * The binary is not in this repository: it is fetched by the build and
     * carried in the APK's `lib/arm64-v8a/`, and the installer puts it in the
     * one directory an app may exec from. A build that skipped the fetch has
     * no chroot-ng at all, and the session then dies with an `ENOENT` that
     * says nothing about the cause.
     */
    override val isAvailable: Boolean
        get() = runtimeFile.isFile && runtimeFile.canExecute()

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

    /**
     * Where the guest starts, and why it is not always what was asked for.
     *
     * The work directory has to exist inside the guest: chroot-ng **refuses** a
     * bad one rather than quietly demoting it to the root. A path that only
     * exists on the host — a project directory that was never created — is
     * refused, and the guest does not start at all.
     *
     * The binds are the identity (`/data:/data`, `/sdcard:/sdcard`), so a guest
     * path under one of them is the same path on the host, and that is what the
     * check looks at.
     */
    private fun workingDirectory(spec: GuestSpec): String {
        val requested = spec.workingDirectory
        if (requested != null) {
            if (File(requested).isDirectory) {
                return requested
            }
            Log.w(TAG, "the work directory $requested does not exist; starting in the home")
        }
        return defaultWorkingDirectory()
    }

    override fun build(spec: GuestSpec): GuestCommand {
        val argv = mutableListOf<String>().apply {
            add(chrootNgPath)
            // The long forms of the options the lab ran with: the same
            // options, but the names say what they do.
            add("--fake-id=0:0") // fake root identity (uid/gid 0)
            add("--link2symlink") // emulate hardlinks where the host refuses link(2)
            add("--rewrite") // ahead-of-time svc rewriting
            deviceBinds().forEach { bind ->
                add("--bind")
                add(bind)
            }
            // The work directory is a *guest* path and must exist inside the
            // rootfs or in a bind; chroot-ng refuses a bad one rather than
            // quietly demoting it to /. Without one it would start the guest at
            // the rootfs root, so the home is named explicitly — which is what

            add("--work-dir")
            add(workingDirectory(spec))
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
        // running it, so the output is line-buffered
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
     * default (the shell's own path, the locale).
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
