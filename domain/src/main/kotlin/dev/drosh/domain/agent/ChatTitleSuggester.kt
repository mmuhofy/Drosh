package dev.drosh.domain.agent

import kotlinx.coroutines.flow.Flow

/**
 * Asks the model to name a conversation.
 *
 * ## Why it is an interface
 *
 * The chat screen is in `:ui`, which sees `:domain` and nothing else. The
 * adapters are in `:agent`. Without a domain interface the ViewModel would
 * have to import the agent module, which is the one dependency direction
 * `AGENT.md` §139 rules out.
 *
 * ## What the implementation owes the caller
 *
 * A title is a nicety. Every failure path — no key, no provider, a timeout, a
 * model that answers with prose — has to come back as null and leave the chat
 * named the way it would have been named before. It must never throw, and it
 * must never block the run it is running beside.
 */
interface ChatTitleSuggester {

    /**
     * A title for the conversation [request] opened, or null for none.
     *
     * Suspends until the model answers, is cancelled with the caller's scope,
     * and returns rather than throws for anything the provider does wrong.
     */
    suspend fun suggest(request: ChatTitleRequest): String?
}

/**
 * What to name, and who to ask.
 *
 * Carries the provider and model ids rather than a resolved provider so the
 * implementation resolves them at call time: the user may have changed either
 * since the chat was opened, and the naming call must go to the same model that
 * is answering, or the name describes a different conversation.
 *
 * @param prompt the first user message, verbatim. It is not summarised before
 *        being sent, only clipped by [ChatTitle.sourceFor] so a pasted log is
 *        not the naming prompt.
 */
data class ChatTitleRequest(
    val providerId: String,
    val modelId: String,
    val prompt: String,
)