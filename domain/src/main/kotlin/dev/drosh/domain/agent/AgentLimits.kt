package dev.drosh.domain.agent

/**
 * Tuning constants for the agent loop.
 *
 * The loop's previous implementation reported "Max steps reached" as a provider
 * *error* even on a clean stop, and had no cap at all on how much text a tool
 * could push into the conversation history. On a phone that second problem is
 * the one that actually breaks a run: a single `cat` of a large file or an
 * `apt install` with verbose output is enough to exhaust the context window
 * several turns later, and the failure surfaces far from its cause.
 */
object AgentLimits {

    /**
     * Loop iterations per run before the run stops cleanly.
     *
     * Desktop agents commonly allow 50; a phone pays for every step in battery
     * and thermal budget, so this is lower. Exceeding it is reported as
     * [RunOutcome.StepLimitReached], never as an error.
     */
    const val MAX_STEPS: Int = 20

    /** Hard cap on characters any single tool result may contribute to history. */
    const val TOOL_OUTPUT_MAX_CHARS: Int = 8_000

    /** Hard cap on lines any single tool result may contribute to history. */
    const val TOOL_OUTPUT_MAX_LINES: Int = 200

    /** Wall-clock limit for a single shell command. */
    const val SHELL_TIMEOUT_SEC: Long = 120L

    /** Transient-failure resend attempts before the run fails. */
    const val MAX_PROVIDER_RETRIES: Int = 3

    /** First backoff delay; doubles each attempt. */
    const val RETRY_BASE_DELAY_MS: Long = 1_000L

    /** Backoff ceiling. */
    const val RETRY_MAX_DELAY_MS: Long = 15_000L

    /** Default ceiling for a `read_file` result, before the general caps apply. */
    const val READ_FILE_MAX_CHARS: Int = 20_000

    /**
     * Identical tool calls in a row beyond this count stop the run.
     *
     * A crude guard against the model retrying the same failing command; it
     * counts only *consecutive* repeats, so legitimate repetition across a turn
     * boundary is unaffected.
     */
    const val REPEATED_TOOL_CALL_LIMIT: Int = 3
}
