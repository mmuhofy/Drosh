package dev.drosh.agent.transcript

/**
 * Storage port for agent transcripts.
 *
 * ## Why this interface exists at all
 *
 * `AgentLoop` injects `TranscriptStore`, and `:agent` cannot see the Hilt bindings
 * that `:data` contributes — the dependency runs the other way. Putting the Room
 * implementation in `:agent` behind this port is what lets `:data` supply the
 * DAO without `:agent` depending on Room, and without the binding going
 * invisible.
 *
 * The methods are the DAO's, one for one, on purpose: an indirection that reshapes
 * the queries would be a place for a mismatch to hide, and the SQL belongs on the
 * side that owns the database.
 */
interface MessageDao {

    suspend fun load(chatId: String): List<AgentMessageRow>

    suspend fun maxSeq(chatId: String): Int

    suspend fun upsert(row: AgentMessageRow)

    suspend fun deleteForChat(chatId: String)

    /**
     * Rows older than [keepSeq] the model would see, oldest first.
     *
     * Filtered by kind in the query rather than in Kotlin, so compaction never
     * loads a large transcript into memory only to discard most of it.
     */
    suspend fun loadSummarizable(chatId: String, keepSeq: Int): List<AgentMessageRow>

    /** Delete and re-insert as one unit; a partial write here is unrecoverable. */
    suspend fun replaceAll(chatId: String, rows: List<AgentMessageRow>)
}

/**
 * One stored transcript row, independent of Room's annotations.
 *
 * A flat shape rather than a sealed type: the columns are mostly optional and the
 * only field with a non-obvious encoding is [toolOutput]. Modelling this as the
 * same sealed hierarchy `ChatMessage` uses would mean a round trip through the
 * domain types, and this table is the one place where a storage concern and a
 * message concept genuinely differ.
 */
data class AgentMessageRow(
    val chatId: String,
    val seq: Int,
    /** `ChatMessage` subclass discriminator. */
    val kind: String,
    val messageId: String,
    val text: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolSummary: String? = null,
    val toolState: String? = null,
    val toolOutput: String? = null,
    val toolFinalOutput: String? = null,
    val truncated: Boolean = false,
    val durationMs: Long? = null,
    val approvalId: String? = null,
    val approvalTitle: String? = null,
    val approvalBody: String? = null,
    val approvalDiff: String? = null,
    val approvalOptions: String? = null,
    val approvalDecisionKind: String? = null,
    val approvalDecisionValue: String? = null,
    val createdAtMs: Long,
)
