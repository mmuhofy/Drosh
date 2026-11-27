package dev.drosh.domain.session

/**
 * The names a session may carry without the user having chosen them.
 *
 * ## Why this is not just [DEFAULT_SESSION_NAME]
 *
 * A Drosh session's PTY does not spawn the shell directly: it spawns the guest
 * runtime (`libchrootng.so`), which then forks and execs the shell. A name probe
 * that reads `/proc/<pid>/comm` before that exec therefore sees the runtime, not
 * the shell. Earlier builds persisted that reading into the session row, so a
 * card and the top bar could be titled `libchroot-ng.so` — a file name the user
 * never typed.
 *
 * The runtime is never a session name, whatever build wrote it. Treating it as a
 * generated default lets the app repair an old row, and lets the probe replace
 * it with the shell's real name when it learns one.
 *
 * The comparison is made on the binary's base name and on the name with the
 * `lib` prefix and `.so` suffix stripped, because the runtime is shipped under
 * more than one spelling (`libchrootng.so`, `libchroot-ng.so`, `chroot-ng`).
 */
private val RUNTIME_PROCESS_NAMES = setOf(
    "chrootng",
    "chroot-ng",
    "fake-chroot-ng",
    "proot",
)

/**
 * The wrapper shells a runtime may sit above. `sh -c` is how chroot-ng runs a
 * command before handing the terminal to the real shell, so `sh` at that level
 * is not the session's program either.
 */
private val WRAPPER_SHELL_NAMES = setOf("sh")

/**
 * Whether [name] is the guest runtime or a wrapper shell rather than the
 * program the user is talking to.
 */
fun isRuntimeProcessName(name: String): Boolean {
    val base = name.trim()
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .lowercase()
    if (base.isEmpty()) return false
    if (base in WRAPPER_SHELL_NAMES) return true
    val stripped = base
        .removePrefix("lib")
        .removeSuffix(".so")
    return stripped in RUNTIME_PROCESS_NAMES
}

/**
 * Whether [name] is a name the app generated rather than one the user chose.
 *
 * A user rename is never discarded because it happens to look generated: the
 * cost of asking twice (and replacing a name the user typed) is higher than the
 * cost of leaving a default in place one tick longer.
 */
fun isGeneratedSessionName(name: String): Boolean =
    name.isBlank() || name == DEFAULT_SESSION_NAME || isRuntimeProcessName(name)

/**
 * The name to show for a session: [name] unless it is unusable, in which case
 * [DEFAULT_SESSION_NAME].
 */
fun displayableSessionName(name: String): String =
    if (isGeneratedSessionName(name)) DEFAULT_SESSION_NAME else name
