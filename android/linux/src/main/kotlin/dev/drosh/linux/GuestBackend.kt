package dev.drosh.linux

import android.content.Context
import java.io.File

/**
 * Which guest isolation engine launches the user's shell.
 *
 * `proot` is the engine that works on a device today. `chroot-ng` is
 * ptrace-free and is the one that will stay: it is built and published by
 * its own project, and Drosh consumes a pinned release asset of it.
 */
enum class GuestBackend {
    /** proot: ptrace-based, from the bootstrap. */
    PROOT,

    /** chroot-ng: ptrace-free, seccomp/SIGSYS, from its own release. */
    CHROOT_NG;

    companion object {
        private const val OVERRIDE_FILE = "guest_backend"

        /**
         * The backend a device test asked for, or null when it did not.
         *
         * The override is a file in the app's own storage, so it can be set
         * from a shell without a build or a UI:
         *
         *     adb shell run-as dev.drosh.my sh -c 'echo chroot_ng > files/guest_backend'
         *
         * A file that names something else is ignored rather than fatal: a
         * typo in a test command must not take the app down.
         */
        fun override(context: Context): GuestBackend? {
            val file = File(context.filesDir, OVERRIDE_FILE)
            if (!file.isFile) return null
            val name = runCatching { file.readText().trim().uppercase() }.getOrNull()
                ?: return null
            return entries.firstOrNull { it.name == name }
        }
    }
}
