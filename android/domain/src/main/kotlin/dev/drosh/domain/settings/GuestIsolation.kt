package dev.drosh.domain.settings

/**
 * Which isolation engine runs the Linux guest.
 *
 * `proot` is ptrace-based: it intercepts every syscall of the guest and
 * forwards it, which costs a context switch per syscall and breaks on
 * programs that use raw syscalls.
 *
 * `chroot-ng` is ptrace-free: it traps only the path-bearing syscalls with
 * seccomp and loads guest programs with its own ELF loader, so static, Go and
 * Rust binaries run unchanged.
 *
 * The choice is a user setting because the two behave differently on some
 * devices, and the one that works is not known until it is tried.
 */
enum class GuestIsolation {
    /** proot: ptrace-based. The engine that works everywhere today. */
    PROOT,

    /** chroot-ng: ptrace-free, seccomp-based. */
    CHROOT_NG,
    ;

    companion object {
        /** The engine used when nothing was chosen. */
        val DEFAULT = PROOT

        /** The stored name, which is also the value the debug override uses. */
        fun fromName(name: String?): GuestIsolation? =
            entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }
    }
}
