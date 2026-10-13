package dev.drosh.ui.agent

import dev.drosh.domain.agent.MessageAttachment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The first prompt of a new chat, waiting for the chat screen that will run it.
 *
 * ## Why this exists
 *
 * Sending from a chat that does not exist yet creates the chat and navigates to
 * it:
 *
 * ```kotlin
 * onChatCreated = { createdId ->
 *     navController.navigate("agent_chat/$createdId") {
 *         popUpTo("agent_chat/$chatId") { inclusive = true }
 *     }
 * }
 * ```
 *
 * Popping the placeholder destination destroys its `ViewModelStore`, so the
 * ViewModel that was asked to send is gone before the run could start — a run
 * started from `viewModelScope` dies with the scope. Before this existed, the
 * first prompt of every conversation was therefore written to a chat name and
 * then thrown away, and the user was asked the same question again.
 *
 * A process-scoped holder is what survives the navigation: the creating side
 * leaves the prompt here, the ViewModel on the other side of the navigation
 * takes it. One prompt, one chat, no timing assumption — the take only succeeds
 * for a chat id that was actually offered.
 *
 * ## Why not a repository field
 *
 * Because it is not conversation state. It is a hand-off across one
 * navigation, it is consumed by the next ViewModel, and nothing renders it. A
 * row on [dev.drosh.domain.agent.AgentChat] would outlive the hand-off and
 * have to be cleared by a timer.
 */
@Singleton
class AgentPendingPrompt @Inject constructor() {

    /**
     * At most one prompt is ever in flight here.
     *
     * A second offer replaces the first: two chats cannot both be waiting for
     * their first prompt at the same moment, and whichever the user actually
     * saw last is the one they meant to send.
     */
    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    /** Called on the creating side, just before it navigates. */
    fun offer(chatId: String, prompt: String, attachments: List<MessageAttachment>) {
        _pending.value = Pending(chatId, prompt, attachments)
    }

    /**
     * Take the prompt waiting for [chatId], or null.
     *
     * Takes rather than peeks, and clears on any id: a prompt that was offered
     * for one chat and never taken would otherwise be offered again to whatever
     * chat is opened next, which is how a screenshot ends up on an unrelated
     * conversation.
     */
    fun take(chatId: String): Pending? {
        val current = _pending.value ?: return null
        if (current.chatId != chatId) return null
        _pending.value = null
        return current
    }

    /** One prompt plus the media that went with it. */
    data class Pending(
        val chatId: String,
        val prompt: String,
        val attachments: List<MessageAttachment>,
    )
}