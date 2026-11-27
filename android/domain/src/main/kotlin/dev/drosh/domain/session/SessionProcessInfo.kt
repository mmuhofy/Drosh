package dev.drosh.domain.session

/**
 * One process the engine actually runs, for the session processes sheet.
 *
 * [name] is the process name the kernel reports, not a label invented from the
 * transcript. [pid] is null when the session has no child process (a bridged
 * session, or one that has not started yet).
 *
 * [jobs] carries the terminal's foreground process group, when the kernel has
 * one and it is not the shell itself. That group is what `Ctrl+C` would signal:
 * a command while one runs, and the shell's own job at a prompt — which is why
 * the shell is not reported as a job. The name comes from `/proc/<pgid>/comm`,
 * not from the transcript, so it is the kernel's answer and not a guess. A
 * bridged session has no PTY and reports none.
 */
data class SessionProcessInfo(
    val sessionId: String,
    val name: String,
    val pid: Int?,
    val jobs: List<String> = emptyList(),
)
