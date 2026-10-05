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

    // ── conversation compaction ───────────────────────────────────────────
    // Once history is durable it never stops growing, and the whole conversation
    // is resent every turn. Past this budget the older half is summarised rather
    // than letting the conversation hit the provider's context ceiling.

    /** Soft ceiling, in characters, for what the model is sent. */
    const val COMPACTION_THRESHOLD_CHARS: Int = 120_000

    /**
     * The opening exchange is always kept whole: it frames everything after it.
     *
     * Two messages — a question and the answer it was asked about.
     */
    const val COMPACTION_KEEP_HEADING: Int = 2

    /** Recent turns kept whole: that is the work in progress. */
    const val COMPACTION_KEEP_TAIL: Int = 8

    /**
     * Below this many messages, never bother — the character budget is not near,
     * and compacting a short conversation would throw away turns to save nothing.
     *
     * Spelled as arithmetic rather than referencing the two constants because a
     * `const val` cannot initialise from another: a const must be a compile-time
     * literal. Written out, it still ties the three together, and
     * `ConversationCompactorTest` asserts the relationship so a change to one
     * without the others is caught.
     */
    const val COMPACTION_MIN_MESSAGES: Int = 2 + 8 + 4

    /**
     * Identical tool calls in a row beyond this count stop the run.
     *
     * A crude guard against the model retrying the same failing command; it
     * counts only *consecutive* repeats, so legitimate repetition across a turn
     * boundary is unaffected.
     */
    const val REPEATED_TOOL_CALL_LIMIT: Int = 3
}
