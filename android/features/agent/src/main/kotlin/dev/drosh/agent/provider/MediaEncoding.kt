package dev.drosh.agent.provider

import dev.drosh.domain.agent.AttachmentKind
import dev.drosh.domain.agent.MessageAttachment
import dev.drosh.domain.agent.ProviderKind

/**
 * Which wire protocols can carry which media, in one place.
 *
 * ## Why the adapters do not each decide this
 *
 * Four adapters, one answer. If each asks "can I encode a video?" in its own
 * `encodeMessage`, the answers drift the moment a protocol is touched, and the
 * failure is a request the provider rejects with a 400 several seconds after a
 * user attached a file — far from the code that got it wrong. Deciding it here
 * means the loop refuses before the request, naming the file, and the adapters
 * can assume everything they are handed is encodable.
 *
 * ## What each protocol actually documents
 *
 * - **Chat Completions** (`image_url` with a data URL) and the **Responses
 *   API** (`input_image`) take images. Neither documents a video part.
 * - **Anthropic Messages** takes images as an `image` block with a base64
 *   `source`. No video block exists in the Messages API.
 * - **Gemini `generateContent`** takes `inlineData` with any mime type the
 *   model declares, which is the only one of the four with a documented video
 *   and audio path.
 *
 * UNTESTED — verify before use: verified against the request shapes each
 * adapter already emits, not against every provider behind an
 * OpenAI-compatible gateway. A gateway that extends its own protocol is free to
 * accept more, and will be told no.
 */
internal object MediaEncoding {

    /** Whether [protocol] has a wire shape for [kind]. */
    fun accepts(protocol: ProviderKind, kind: AttachmentKind): Boolean = when (protocol) {
        ProviderKind.OPENAI_COMPAT, ProviderKind.OPENAI_RESPONSES -> kind == AttachmentKind.IMAGE
        ProviderKind.ANTHROPIC -> kind == AttachmentKind.IMAGE
        ProviderKind.GEMINI -> kind == AttachmentKind.IMAGE ||
            kind == AttachmentKind.VIDEO ||
            kind == AttachmentKind.AUDIO
    }

    /** Whether [protocol] has a wire shape for this exact attachment. */
    fun accepts(protocol: ProviderKind, attachment: MessageAttachment): Boolean =
        accepts(protocol, attachment.kind)

    /** The first attachment [protocol] cannot carry, or null when it can carry them all. */
    fun firstUnsupported(
        protocol: ProviderKind,
        attachments: List<MessageAttachment>,
    ): MessageAttachment? = attachments.firstOrNull { !accepts(protocol, it) }

    /** Whether every one of [attachments] survives a round trip through [protocol]. */
    fun acceptsAll(protocol: ProviderKind, attachments: List<MessageAttachment>): Boolean =
        attachments.all { accepts(protocol, it) }
}