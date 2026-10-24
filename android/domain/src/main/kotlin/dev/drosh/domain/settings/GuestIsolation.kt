package dev.drosh.domain.settings

/**
 * Which isolation engine runs the Linux guest.
 *
 * `chroot-ng` is ptrace-free: it traps only the path-bearing syscalls with
 * seccomp and loads guest programs with its own ELF loader, so static, Go and
 * Rust binaries run unchanged. It is the only engine Drosh ships.
 *
 * The enum is kept rather than collapsed to a constant because the value is
 * stored in the settings file and read back on launch; a build that met an
 * older file must still parse it.
 */
enum class GuestIsolation {
    /** chroot-ng: ptrace-free, seccomp-based. The only engine. */
    CHROOT_NG,
    ;

    companion object {
        /** The engine used when nothing was chosen. */
        val DEFAULT = CHROOT_NG

        /**
         * The stored name, which is also the value the debug override uses.
         *
         * An older file may name the retired `proot` engine; that is read as
         * "no choice" rather than as an error, so the file still loads and the
         * app falls back to the engine it has.
         */
        fun fromName(name: String?): GuestIsolation? =
            entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }
    }
}
