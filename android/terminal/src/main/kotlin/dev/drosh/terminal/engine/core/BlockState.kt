package dev.drosh.terminal.engine.core

/**
 * Where the shell is in its cycle, as the engine's OSC 133 marks report it.
 *
 * The block engine used to find these boundaries by diffing the whole
 * transcript on every keystroke. The engine already knows them: the marks are
 * tapped where the bytes enter, and the session's tracker turns them into this.
 *
 * @property phase one of `unknown`, `prompt`, `typing`, `running`, `finished`
 * @property status the exit status of the last command, when it reported one
 * @property workingDirectory the last directory the shell reported (`OSC 7`)
 * @property editorTarget the last file the shell asked the editor to open
 *   (`OSC 1339`)
 */
data class BlockState(
    val phase: String = PHASE_UNKNOWN,
    val status: Int? = null,
    val workingDirectory: String? = null,
    val editorTarget: String? = null,
) {
    companion object {
        const val PHASE_UNKNOWN = "unknown"
        const val PHASE_PROMPT = "prompt"
        const val PHASE_TYPING = "typing"
        const val PHASE_RUNNING = "running"
        const val PHASE_FINISHED = "finished"

        /**
         * Parses the engine's record: newline-separated `key:value` lines.
         *
         * A missing key is null rather than an error — the engine reports what
         * it knows, and a shell that has not sent a mark yet knows nothing.
         */
        fun parse(record: String): BlockState {
            if (record.isEmpty()) return BlockState()
            var state = BlockState()
            for (line in record.lineSequence()) {
                val separator = line.indexOf(':')
                if (separator <= 0) continue
                val key = line.substring(0, separator)
                val value = line.substring(separator + 1)
                state = when (key) {
                    "phase" -> state.copy(phase = value)
                    "status" -> state.copy(status = value.toIntOrNull())
                    "cwd" -> state.copy(workingDirectory = value)
                    "editor" -> state.copy(editorTarget = value)
                    else -> state
                }
            }
            return state
        }
    }
}
