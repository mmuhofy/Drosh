package dev.drosh.domain.agent

/**
 * One item on the agent's checklist.
 *
 * @param id stable across updates, so the model can say "mark 2 done" without
 *        restating the list. A checklist addressed by position drifts the moment
 *        an item is inserted.
 */
data class AgentTodo(
    val id: String,
    val title: String,
    val status: AgentTodoStatus,
)

enum class AgentTodoStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED;

    companion object {
        fun parse(raw: String?): AgentTodoStatus = when (raw?.trim()?.lowercase()) {
            "completed", "done", "x" -> COMPLETED
            "in_progress", "in-progress", "doing", ">" -> IN_PROGRESS
            else -> PENDING
        }
    }
}

/**
 * The checklist's wire format.
 *
 * ## Why a codec at all
 *
 * The checklist has to survive a restart, and the transcript already persists a
 * tool row's final output as a string. Adding a Room column for the list would
 * mean a schema migration and a version bump for one field, and would give the
 * checklist a second home that could disagree with the output text.
 *
 * So the output string *is* the storage, and this is the one place that knows its
 * shape. `update_todo` encodes with it, and the transcript decodes with it. Two
 * ends of one format, adjacent, round-trip tested — not a view parsing whatever a
 * tool happened to print.
 *
 * ## Why the header line
 *
 * `TODO (2/5)` is the model's summary and the user's progress at a glance. It is
 * also what makes a decode failure detectable: a string without that header is not
 * a checklist, and pretending otherwise would render an error message's output as
 * an empty list of tasks.
 *
 * ## Newlines in a title
 *
 * A title containing a newline would split into two items on decode, so they are
 * folded to spaces on the way in. The model gets a slightly tidier title back than
 * it sent, which is better than a checklist that gains an item per line break.
 */
object AgentTodoCodec {

    private const val HEADER = "TODO ("
    private const val MARK_PENDING = " "
    private const val MARK_ACTIVE = ">"
    private const val MARK_DONE = "x"

    /** The list as the model sees it, and as the transcript stores it. */
    fun encode(todos: List<AgentTodo>): String {
        val done = todos.count { it.status == AgentTodoStatus.COMPLETED }
        val body = todos.joinToString("\n") { todo ->
            val mark = when (todo.status) {
                AgentTodoStatus.PENDING -> MARK_PENDING
                AgentTodoStatus.IN_PROGRESS -> MARK_ACTIVE
                AgentTodoStatus.COMPLETED -> MARK_DONE
            }
            "[$mark] ${oneLine(todo.id)}. ${oneLine(todo.title)}"
        }
        return "$HEADER$done/${todos.size})\n$body"
    }

    /**
     * The list back out, or an empty list when [text] is not one.
     *
     * Returns empty rather than throwing: this runs while restoring a transcript, on
     * a string that may be a truncated tool result, and a restored chat that fails
     * to open is worse than one whose checklist is briefly absent.
     */
    fun decode(text: String?): List<AgentTodo> {
        if (text.isNullOrBlank()) return emptyList()
        val lines = text.lines()
        if (!lines.first().startsWith(HEADER)) return emptyList()

        return lines.drop(1).mapNotNull { line ->
            // "[x] 2. title" — mark, id, then the title which may itself contain ". ".
            if (line.length < 6) return@mapNotNull null
            val mark = line[1]
            if (line[0] != '[' || line[2] != ']') return@mapNotNull null

            val rest = line.substring(3).trimStart()
            val separator = rest.indexOf(". ")
            if (separator <= 0) return@mapNotNull null

            val id = rest.substring(0, separator).trim()
            val title = rest.substring(separator + 2).trim()
            if (id.isEmpty() || title.isEmpty()) return@mapNotNull null

            AgentTodo(
                id = id,
                title = title,
                status = when (mark) {
                    MARK_ACTIVE[0] -> AgentTodoStatus.IN_PROGRESS
                    MARK_DONE[0] -> AgentTodoStatus.COMPLETED
                    else -> AgentTodoStatus.PENDING
                },
            )
        }
    }

    private fun oneLine(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ').trim()
}
