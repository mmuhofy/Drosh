package dev.drosh.agent.runtime

import dev.drosh.agent.provider.ProviderRegistry
import dev.drosh.domain.agent.ChatTitle
import dev.drosh.domain.agent.ChatTitleRequest
import dev.drosh.domain.agent.ChatTitleSuggester
import dev.drosh.domain.agent.LlmProviderRepository
import dev.drosh.domain.agent.LlmStreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks the running model to name the conversation.
 *
 * ## A second request, not a rewritten first one
 *
 * The prompt that produced this call already went to the model exactly as the
 * user typed it, and this class does not touch it. What it sends is a *separate*
 * request whose only input is that same prompt, asked for a name instead of an
 * answer. Summarising the first message "for the titler" — the obvious way to
 * save tokens — is how a title starts describing a paraphrase of what the user
 * asked.
 *
 * ## Why it is fire and forget
 *
 * Nothing in the chat waits on this. The name already on the chat when the run
 * finishes is the derived one, the user has been reading an answer for a second
 * by then, and a naming call that failed has to cost nothing rather than leave
 * a spinner somewhere. So every exit is `null`, and `null` means "keep the name
 * you already had".
 *
 * ## Why it reuses the adapter
 *
 * There is no title protocol. [dev.drosh.domain.agent.ChatAdapter.titleStream] is
 * one ordinary request with a system prompt that explains the job, so it goes
 * through the same request builder, the same credential resolution and the same
 * error handling as everything else — which also means it works on all four
 * protocols without a line written per protocol.
 */
@Singleton
class AgentChatTitleSuggester @Inject constructor(
    private val providers: LlmProviderRepository,
    private val registry: ProviderRegistry,
) : ChatTitleSuggester {

    override suspend fun suggest(request: ChatTitleRequest): String? {
        // Nothing to name: a single word, or "ok". Asking anyway produces
        // either a useless title or a refusal, both of which are worse than
        // the first words of the message.
        if (!ChatTitle.isWorthNaming(request.prompt)) return null
        if (request.modelId.isBlank()) return null

        val provider = providers.provider(request.providerId) ?: return null
        val credential = providers.credential(request.providerId) ?: return null

        val adapter = try {
            registry.adapterFor(provider)
        } catch (unsupported: UnsupportedOperationException) {
            return null
        }

        val text = StringBuilder()
        var failed = false

        try {
            adapter.titleStream(
                provider = provider,
                modelId = request.modelId,
                credential = credential,
                userText = ChatTitle.sourceFor(request.prompt),
            ).collect { event ->
                when (event) {
                    // Bounded rather than unbounded: a model that ignored the
                    // instruction and started explaining itself costs the same
                    // after the first line as after the first paragraph, and
                    // the sanitizer drops the rest either way.
                    is LlmStreamEvent.TextDelta ->
                        if (text.length < MAX_TITLE_STREAM_CHARS) text.append(event.text)

                    is LlmStreamEvent.Failed -> failed = true

                    // Reasoning is not part of an answer, and a tool call here
                    // would mean the model ignored a prompt with no tools in it.
                    else -> Unit
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return null
        }

        if (failed) return null
        return ChatTitle.sanitize(text.toString())
    }

    private companion object {

        /**
         * How much of the answer is kept before it is known to be a title.
         *
         * Four times [ChatTitle.MAX_CHARS]: enough for a prefix, a quote and a
         * trailing explanation to all be present and still be recognizable as
         * something the model wrote in a hurry.
         */
        const val MAX_TITLE_STREAM_CHARS: Int = ChatTitle.MAX_CHARS * 4
    }
}