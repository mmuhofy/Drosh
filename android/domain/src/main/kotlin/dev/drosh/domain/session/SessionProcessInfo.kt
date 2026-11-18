package dev.drosh.domain.session

/**
 * One process the engine actually runs, for the session processes sheet.
 *
 * [name] is the process name the kernel reports, not a label invented from the
 * transcript. [pid] is null when the session has no child process (a bridged
 * session, or one that has not started yet).
 *
 * [jobs] is deliberately always empty. The engine has no job list: it runs one
 * child per session and the shell's jobs are not exposed across the native
 * boundary. Reporting a list here would mean guessing one from terminal output,
 * so the field exists only to keep the UI's shape stable until there is a real
 * source.
 */
data class SessionProcessInfo(
    val sessionId: String,
    val name: String,
    val pid: Int?,
    val jobs: List<String> = emptyList(),
)
