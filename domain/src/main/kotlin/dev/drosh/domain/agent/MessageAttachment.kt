package dev.drosh.domain.agent

/**
 * One file the user attached to a prompt.
 *
 * Inspired by: openai/codex — `codex-rs/protocol/src/models.rs`, where a user
 * turn is a list of `ContentItem`s (`InputText`, `InputImage`, `InputAudio`)
 * rather than a string.
 * Adapted for Drosh — dev.drosh
 *
 * ## Why the bytes travel as a data URL
 *
 * Every protocol wants the same thing in three different spellings:
 *
 * - Chat Completions wants the whole `data:image/png;base64,…` string as the
 *   `url` of an `image_url` object.
 * - Anthropic wants the media type and the payload as two separate fields, so
 *   the prefix has to come back off.
 * - Gemini wants the payload under `inlineData.data` with `inlineData.mimeType`
 *   beside it.
 *
 * Carrying one form and deriving the other two at the adapter is what keeps the
 * three adapters from disagreeing about what a given picture is. The raw
 * payload is therefore never stored — [base64Payload] is derived, not kept.
 *
 * ## What is not here
 *
 * A file path, a content Uri, or a compressed image. The attachment is already
 * in the shape the provider will read it in, so nothing above this layer has to
 * open a file, hold a Uri permission, or know that Android exists. That also
 * means it does not survive process death: the transcript stores the text of a
 * turn, and re-reading megabytes of base64 out of Room on every restore is a
 * cost with no benefit for a conversation the loop no longer holds anyway.
 */
data class MessageAttachment(
    /** Stable within one composer session; the transcript row key. */
    val id: String,
    /** MIME type as the provider spells it, e.g. `image/png`. */
    val mediaType: String,
    /** The whole `data:<mediaType>;base64,<payload>` string. */
    val dataUrl: String,
    /** Original file name, for the composer chip and for the model-facing note. */
    val fileName: String = "",
    /** Decoded byte length, before base64. Zero when unknown. */
    val byteSize: Int = 0,
    /**
     * A preview frame, for media the model takes as whole files.
     *
     * A video is a few megabytes of bytes the user cannot see in a chip and the
     * wire protocols mostly cannot take at all, so the composer shows a frame
     * grabbed off the video instead. Null for images, which are their own
     * preview.
     */
    val thumbnailDataUrl: String? = null,
) {

    val kind: AttachmentKind get() = AttachmentKind.of(mediaType)

    val isImage: Boolean get() = kind == AttachmentKind.IMAGE

    val isVideo: Boolean get() = kind == AttachmentKind.VIDEO

    /**
     * The payload with the `data:…;base64,` prefix removed.
     *
     * Empty rather than throwing when the string is not a base64 data URL: an
     * adapter that was handed something malformed must produce a request the
     * provider rejects, not a crash in the middle of building one.
     */
    val base64Payload: String
        get() = dataUrl.substringAfter(AttachmentLimits.BASE64_MARKER, "")

    /** What a chip in the composer draws. */
    val previewDataUrl: String?
        get() = when {
            thumbnailDataUrl != null -> thumbnailDataUrl
            isImage -> dataUrl
            else -> null
        }

    /** The size actually on the wire, which is what the limit is about. */
    val base64ByteCount: Int get() = base64Payload.length

    companion object {

        /**
         * Build an attachment from an already-encoded payload.
         *
         * Encoding itself lives in the Android layer (`android.util.Base64`);
         * this keeps `:domain` free of Android types and keeps the one place
         * that knows about limits in the one place that knows about providers.
         */
        fun fromBase64(
            id: String,
            mediaType: String,
            base64: String,
            fileName: String = "",
            byteSize: Int = 0,
            thumbnailDataUrl: String? = null,
        ): MessageAttachment = MessageAttachment(
            id = id,
            mediaType = mediaType,
            dataUrl = AttachmentLimits.dataUrlFor(mediaType, base64),
            fileName = fileName,
            byteSize = byteSize,
            thumbnailDataUrl = thumbnailDataUrl,
        )
    }
}

/**
 * What sort of media an attachment is, which is what decides whether a
 * protocol can carry it.
 *
 * Derived from the MIME type rather than stored: a field that could disagree
 * with the type it claims to describe is a second thing to get wrong.
 */
enum class AttachmentKind {

    IMAGE,
    VIDEO,
    AUDIO,

    /** PDFs and other files — present so an unsupported type is named, not guessed. */
    DOCUMENT,
    ;

    companion object {

        fun of(mediaType: String): AttachmentKind {
            val normalized = mediaType.substringBefore('/').lowercase()
            return when (normalized) {
                "image" -> IMAGE
                "video" -> VIDEO
                "audio" -> AUDIO
                else -> DOCUMENT
            }
        }
    }
}

/**
 * The limits an attachment has to fit inside, and the data-URL plumbing.
 *
 * ## Why 5 MB of base64
 *
 * Ported from OpenCode's `packages/opencode/src/image/image.ts`, which caps
 * `max_base64_bytes` at `5 * 1024 * 1024`. Codex's `MAX_PROMPT_IMAGE_INPUT_BYTES`
 * is deliberately far larger (1 GB) because it is read from local disk on a
 * desktop; a phone carrying the same picture over a mobile connection is the
 * other problem, and OpenCode's number is the one written for it.
 *
 * ## Why the cap is on the encoded form
 *
 * Base64 inflates by a third, so a 5 MB cap on the payload is a 3.75 MB cap on
 * the file. Checking the encoded string is what actually bounds the request
 * body, and it is the number the encoder has in hand at the moment it has to
 * decide whether to downscale.
 */
object AttachmentLimits {

    /** Base64 payload ceiling for one attachment. */
    const val MAX_BASE64_BYTES: Int = 5 * 1024 * 1024

    /**
     * How many attachments one message may carry.
     *
     * A bound rather than a preference: every attachment is a full
     * image-sized token cost on every subsequent turn of the conversation, so a
     * picker that never said no would let one paste become a twenty-turn bill.
     */
    const val MAX_ATTACHMENTS_PER_MESSAGE: Int = 8

    const val DATA_URL_PREFIX: String = "data:"

    const val BASE64_MARKER: String = ";base64,"

    /** The image formats every protocol below accepts inline. */
    val IMAGE_MEDIA_TYPES: Set<String> = setOf(
        "image/png",
        "image/jpeg",
        "image/webp",
        "image/gif",
    )

    /**
     * Video containers.
     *
     * Only Gemini takes video bytes inline (see `MediaEncoding` in `:agent`),
     * so this list exists to say "this is video, and this endpoint cannot have
     * it" rather than to promise it works.
     */
    val VIDEO_MEDIA_TYPES: Set<String> = setOf(
        "video/mp4",
        "video/webm",
        "video/quicktime",
    )

    /** Non-image mime types that are still an image in all but name. */
    val IMAGE_MIME_ALIASES: Map<String, String> = mapOf(
        // What Android's picker hands back for a camera photo, and what some
        // providers reject outright rather than recognising.
        "image/jpg" to "image/jpeg",
        "image/heic" to "image/jpeg",
    )

    /** Longest edge an inline image is downscaled to before encoding. */
    const val MAX_IMAGE_EDGE_PX: Int = 2000

    fun dataUrlFor(mediaType: String, base64: String): String =
        "$DATA_URL_PREFIX$mediaType$BASE64_MARKER$base64"

    /** The canonical spelling for a type, so `image/jpg` never reaches a wire. */
    fun normalizeMediaType(mediaType: String): String {
        val trimmed = mediaType.substringBefore(';').trim().lowercase()
        return IMAGE_MIME_ALIASES[trimmed] ?: trimmed
    }

    /** Whether this type may be attached at all, whatever the model supports. */
    fun isSupported(mediaType: String): Boolean {
        val type = normalizeMediaType(mediaType)
        return type in IMAGE_MEDIA_TYPES || type in VIDEO_MEDIA_TYPES
    }

    /** True when [dataUrl] would exceed [MAX_BASE64_BYTES] once sent. */
    fun exceedsLimit(dataUrl: String): Boolean = payloadLength(dataUrl) > MAX_BASE64_BYTES

    /** Bytes of base64 payload in a data URL, without building a substring. */
    fun payloadLength(dataUrl: String): Int {
        val marker = dataUrl.indexOf(BASE64_MARKER)
        if (marker < 0) return dataUrl.length
        return dataUrl.length - marker - BASE64_MARKER.length
    }

    /** Decoded size of a base64 payload of [base64Chars] characters. */
    fun decodedByteCount(base64Chars: Int): Int = base64Chars / 4 * 3
}