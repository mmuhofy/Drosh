package dev.drosh.ui.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Terminal output handed to the agent, waiting to be picked up.
 *
 * ## Why a holder and not a navigation
 *
 * The log is chosen in the terminal, and the agent reads it in a different screen
 * owned by a different ViewModel. There is no route between them that carries a
 * payload — the terminal screen is instantiated twice in `MainActivity`, once on
 * either side of the PIN gate, so there is not one chat screen to hand a string
 * to. A shared holder is the one thing both sides can reach, and it is what the
 * clipboard cannot be: a clipboard write has no reader, so anything that wanted
 * the text back would have to ask the user to paste it.
 *
 * ## What is in it
 *
 * The whole thing is *also* written to the clipboard by the caller, because a
 * clipboard is what the user can reach from anywhere — including the agent
 * composer, by pasting. The holder is the automatic half, the clipboard is the
 * manual one, and this is the automatic half.
 *
 * ## For whoever owns `AgentChatScreen`
 *
 * Read [pending] where the composer reads its text, and [consume] when it is
 * inserted, so the same log is not pasted twice:
 *
 * ```kotlin
 * val pending by AgentLogAttach.pending.collectAsStateWithLifecycle()
 * LaunchedEffect(pending) {
 *     pending?.let { composer?.append(it.text); AgentLogAttach.consume() }
 * }
 * ```
 *
 * Nothing here depends on the chat screen existing: [attach] is a plain write, and
 * a value nobody reads simply stays until it is consumed or replaced.
 */
object AgentLogAttach {

    private val _pending = MutableStateFlow<PendingAttachment?>(null)

    /** The attachment waiting to be picked up, or null when there is none. */
    val pending: StateFlow<PendingAttachment?> = _pending.asStateFlow()

    /**
     * Takes the attachment, leaving nothing behind.
     *
     * A get rather than a read-and-clear so two readers cannot both see the same
     * text: whoever calls this has committed to inserting it.
     */
    fun consume(): PendingAttachment? = _pending.getAndUpdate { null }

    /** Holds [text] as the next thing the agent should read. */
    fun attach(sessionName: String, firstLine: Int, lastLine: Int, text: String) {
        _pending.value = PendingAttachment(sessionName, firstLine, lastLine, text)
    }

    /**
     * Wraps chosen lines as a note about where they came from.
     *
     * The wrapper is not decoration. An agent handed forty lines of build output
     * has no way to know that they are the tail of a build rather than something
     * it was asked to write, and the difference decides whether it summarises the
     * failure or starts editing the file. Naming the session and the line range is
     * what makes the paste answerable.
     *
     * `system-reminder` and the fence are what opencode puts around an editor
     * selection before it goes into the prompt
     * (`packages/tui/src/component/prompt/index.tsx`, `formatEditorContext`), and
     * for the same reason: it is text about the request rather than part of it,
     * and a model reads the tag as such.
     */
    fun format(sessionName: String, firstLine: Int, lastLine: Int, body: String): String {
        val where = "lines $firstLine-$lastLine of the terminal session \"$sessionName\""
        return buildString {
            append("<system-reminder>\n")
            append("The user selected $where from the terminal. ")
            append("This may or may not be relevant to the current task.\n\n")
            append("```text\n")
            append(body)
            if (!body.endsWith("\n")) append('\n')
            append("```\n")
            append("</system-reminder>\n")
        }
    }

    }

/**
 * Log text on its way to the agent.
 *
 * The line range is kept as numbers rather than worked out of the text because the
 * chat surface shows "40 lines from build" — a range it can display without
 * re-parsing the fence back apart.
 */
data class PendingAttachment(
    val sessionName: String,
    val firstLine: Int,
    val lastLine: Int,
    val text: String,
) {
    /** How many lines were chosen, which is one more than the gap between them. */
    val lineCount: Int get() = lastLine - firstLine + 1
}