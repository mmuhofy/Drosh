package dev.drosh.ui.agent

import android.content.Context
import dev.drosh.core.localizedContext
import dev.drosh.domain.agent.AgentWarning
import dev.drosh.domain.agent.InputModality
import dev.drosh.ui.R

/**
 * What an [AgentWarning] says, in the language the app is set to.
 *
 * ## Why the warning is data and its text is not
 *
 * The ViewModel decides *whether* a file is refused and which one, because that
 * is a rule about the model and the file. The sentence is a rendering concern,
 * and a sentence assembled in a ViewModel is a sentence no translator ever sees.
 * So the warning travels as a value and is turned into text here, next to the
 * strings.
 *
 * ## Why a [Context] rather than a composable
 *
 * A refusal has to reach the transcript, and the transcript is written by the
 * ViewModel as a `ChatMessage.Notice` — there is no composition at that point.
 * [localizedContext] applies the language the settings screen wrote rather than
 * the system one the application context would otherwise answer in, so a user
 * who picked Turkish on an English phone still reads their own language.
 *
 * The text is resolved once, at the moment of the refusal, and the transcript
 * row keeps the language the user was reading then. A chat reopened tomorrow in
 * another language showing yesterday's language in one warning row is the
 * cheaper of the two mistakes.
 */
fun AgentWarning.describe(
    context: Context,
    languageTag: String,
): String {
    val strings = localizedContext(context, languageTag)
    return when (this) {
        is AgentWarning.ModelLacksModality -> {
            val kind = strings.getString(modality.labelRes())
            val model = modelId.ifBlank { strings.getString(R.string.agent_media_model_unknown) }
            if (fileName.isBlank()) {
                strings.getString(R.string.agent_media_model_lacks, model, kind)
            } else {
                strings.getString(R.string.agent_media_model_lacks_named, model, kind, fileName)
            }
        }

        is AgentWarning.UnsupportedType ->
            strings.getString(R.string.agent_media_unsupported_type, fileName)

        is AgentWarning.AttachmentTooLarge ->
            strings.getString(
                R.string.agent_media_too_large_named,
                fileName.ifBlank { strings.getString(R.string.agent_media_anonymous_file) },
                formatBytes(maxByteSize),
            )

        is AgentWarning.TooManyAttachments ->
            strings.getString(R.string.agent_media_too_many, limit)

        is AgentWarning.UnreadableAttachment ->
            strings.getString(R.string.agent_media_unreadable)

        AgentWarning.NothingToSend ->
            strings.getString(R.string.agent_media_nothing_to_send)
    }
}

/** The word for a modality, in a sentence rather than as a heading. */
private fun InputModality.labelRes(): Int = when (this) {
    InputModality.IMAGE -> R.string.agent_media_kind_image
    InputModality.VIDEO -> R.string.agent_media_kind_video
    InputModality.AUDIO -> R.string.agent_media_kind_audio
    InputModality.TEXT -> R.string.agent_media_kind_text
}

/**
 * A byte count as something short.
 *
 * Deliberately not `Formatter.formatShortFileSize`: that reports in the
 * device's decimal units, while the limit it is being compared against is a
 * power of two, and a refusal reading "5.0 MB" beside a 5 MiB limit is a small
 * lie that makes the app look like it cannot count.
 */
internal fun formatBytes(bytes: Int): String {
    if (bytes < KILOBYTE) return "$bytes B"
    val kilobytes = bytes.toLong() * 100 / KILOBYTE
    if (kilobytes < 1000) return "${kilobytes} KB"
    val megabytes = kilobytes * 100 / 1000
    if (megabytes < 1000) return "${megabytes / 100}.${megabytes % 100 / 10} MB"
    val gigabytes = megabytes * 100 / 1000
    return "${gigabytes / 100}.${gigabytes % 100 / 10} GB"
}

private const val KILOBYTE = 1024L