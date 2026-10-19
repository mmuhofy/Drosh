package dev.drosh.domain.agent

/**
 * Why an attachment was refused, as something the UI can say.
 *
 * ## Data, not text
 *
 * Every one of these is a case the user has to act on differently — switch
 * model, pick a smaller file, pick a different file — so the type is what the
 * composer branches on and the string is only resolved at the point of display.
 * The alternative, a `String` held by the ViewModel, makes it impossible to
 * tell a warning about *this* model from a leftover one when the model changes
 * underneath it.
 *
 * ## The model is named, because the fix is a different model
 *
 * [ModelLacksModality] carries the model id and the file it refused. "This
 * model does not accept video input" is a puzzle when three files are attached
 * and only one is the video; "gpt-4o-mini cannot take video (clip.mp4)" is a
 * sentence the user can act on.
 */
sealed interface AgentWarning {

    /**
     * The selected model does not take this kind of input.
     *
     * Only ever raised when something actually said so — see
     * [ModelInputCapabilities], whose unknown case is permissive.
     */
    data class ModelLacksModality(
        val modelId: String,
        val modality: InputModality,
        val fileName: String = "",
    ) : AgentWarning

    /**
     * The file is a type Drosh cannot send inline.
     *
     * Distinct from [ModelLacksModality] because it will not be fixed by
     * switching models: a PDF is a PDF on every one of them.
     */
    data class UnsupportedType(
        val fileName: String,
        val mediaType: String,
    ) : AgentWarning

    /** The encoded file is past [AttachmentLimits.MAX_BASE64_BYTES]. */
    data class AttachmentTooLarge(
        val fileName: String,
        val byteSize: Int,
        val maxByteSize: Int,
    ) : AgentWarning

    /** Already [AttachmentLimits.MAX_ATTACHMENTS_PER_MESSAGE] attachments. */
    data class TooManyAttachments(val limit: Int) : AgentWarning

    /** The picker gave back something that could not be read or decoded. */
    data class UnreadableAttachment(val fileName: String = "") : AgentWarning

    /**
     * The input the user asked about is empty.
     *
     * Not an error, and not something the transcript should record — there is
     * nothing to say and nothing they did wrong. It exists so the composer's
     * send affordance can be disabled with a reason rather than silently doing
     * nothing when pressed.
     */
    data object NothingToSend : AgentWarning
}

/**
 * The first thing wrong with [attachments] for a model, or null when all is well.
 *
 * One function so the composer and the send path cannot disagree about what is
 * allowed: a check that exists in only one of them is a check that will
 * eventually be wrong in the other.
 */
fun attachmentWarning(
    attachments: List<MessageAttachment>,
    capabilities: ModelInputCapabilities,
    modelId: String,
    limit: Int = AttachmentLimits.MAX_ATTACHMENTS_PER_MESSAGE,
): AgentWarning? = when {
    attachments.isEmpty() -> null

    attachments.size > limit -> AgentWarning.TooManyAttachments(limit)

    else -> {
        val unsupportedType = attachments.firstOrNull { !AttachmentLimits.isSupported(it.mediaType) }
        if (unsupportedType != null) {
            AgentWarning.UnsupportedType(unsupportedType.fileName, unsupportedType.mediaType)
        } else {
            capabilities.firstUnsupported(attachments)?.let { requirement ->
                AgentWarning.ModelLacksModality(
                    modelId = modelId,
                    modality = requirement.modality,
                    fileName = requirement.fileName,
                )
            }
        }
    }
}