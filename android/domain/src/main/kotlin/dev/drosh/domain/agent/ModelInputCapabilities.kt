package dev.drosh.domain.agent

import kotlinx.serialization.Serializable

/**
 * What a model accepts in its input.
 *
 * Inspired by: openai/codex — `codex-rs/app-server-protocol/schema/typescript/InputModality.ts`
 * (`"text" | "image" | "audio"`) and `chatwidget/settings.rs`, where
 * `current_model_supports_images()` defaults to `true` when the catalog cannot
 * be read.
 * Adapted for Drosh — dev.drosh
 *
 * ## Text is not optional
 *
 * Every model takes text, so [TEXT] is implied by any non-empty declaration
 * rather than listed: a catalog that named only `["image"]` would be describing
 * something no model is.
 */
@Serializable
enum class InputModality(val wireName: String) {

    TEXT("text"),
    IMAGE("image"),
    VIDEO("video"),
    AUDIO("audio"),
    ;

    companion object {

        /**
         * The modality a wire name names, or null.
         *
         * Null rather than a default: an unrecognized name is a catalog we do
         * not understand, and guessing `text` for it would silently drop the one
         * modality that was being claimed.
         */
        fun fromWire(raw: String?): InputModality? {
            val name = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wireName == name }
        }

        /** The modalities a MIME type maps to, as [AttachmentKind] does. */
        fun of(mediaType: String): InputModality = when (AttachmentKind.of(mediaType)) {
            AttachmentKind.IMAGE -> IMAGE
            AttachmentKind.VIDEO -> VIDEO
            AttachmentKind.AUDIO -> AUDIO
            AttachmentKind.DOCUMENT -> TEXT
        }
    }
}

/**
 * What one model takes, as far as anything knows.
 *
 * ## Unknown means permissive, and that is deliberate
 *
 * Codex's `current_model_supports_images()` returns `true` when the catalog
 * cannot be read, for a reason worth copying: a transient fetch failure must
 * not make the composer refuse to do its job. The same applies here — a model
 * missing from the catalog (a local endpoint's model list, a provider added
 * between catalog builds) is a model whose abilities nobody has described, and
 * blocking a screenshot because of it is worse than letting the provider answer.
 *
 * The provider itself is the backstop: it rejects the part it cannot take, and
 * that answer arrives with a provider-authored message attached.
 */
data class ModelInputCapabilities(
    /** What the catalog declared. Empty means "nobody has said". */
    val declared: Set<InputModality> = emptySet(),
    /** Where the declaration came from, for the warning text and for tests. */
    val source: Source = Source.UNKNOWN,
) {

    /** True when something actually said, as opposed to nothing being known. */
    val isDeclared: Boolean get() = declared.isNotEmpty()

    /**
     * Whether this model takes [modality].
     *
     * Unknown is permissive — see the class comment.
     */
    fun supports(modality: InputModality): Boolean =
        !isDeclared || modality in declared

    /** The modality a MIME type needs, asked about this model. */
    fun supports(mediaType: String): Boolean = supports(InputModality.of(mediaType))

    /** Whether every one of [modalities] is accepted. */
    fun supportsAll(modalities: List<InputModality>): Boolean = modalities.all(::supports)

    /** How this answer was arrived at. Never shown raw. */
    enum class Source {

        /** The catalog entry names its modalities. */
        CATALOG,

        /**
         * The catalog entry carries only the `attachment` flag, which is
         * models.dev's boolean for "takes images".
         */
        ATTACHMENT_FLAG,

        /** Nothing in the catalog describes this model. */
        UNKNOWN,
    }

    companion object {

        /**
         * Nothing known, so nothing refused.
         *
         * The value the UI starts on before the catalog lands: the answer a
         * chat opened during the first fetch has to be able to give.
         */
        val UNKNOWN = ModelInputCapabilities()

        /**
         * What [model] takes.
         *
         * Two steps, because models.dev states it two ways and Drosh keeps
         * both: a newer catalog entry may name its modalities outright, and
         * every entry has the boolean `attachment` flag underneath it.
         */
        fun of(model: CatalogModel?): ModelInputCapabilities {
            if (model == null) return UNKNOWN

            val named = model.inputModalities
                .mapNotNull(InputModality::fromWire)
                .toSet()
            if (named.isNotEmpty()) {
                return ModelInputCapabilities(declared = named + InputModality.TEXT, source = Source.CATALOG)
            }

            // `attachment` is OpenCode's `Model.attachment` capability flag
            // (packages/core/src/models-dev.ts, line 72), folded into the
            // catalog as [CatalogModel.attachment]. It is a real statement
            // about the model, not an absence of one, so it is honored as such.
            return ModelInputCapabilities(
                declared = setOf(InputModality.TEXT) +
                    if (model.attachment) setOf(InputModality.IMAGE) else emptySet(),
                source = Source.ATTACHMENT_FLAG,
            )
        }

        /** The modalities a whole batch of attachments needs. */
        fun requiredFor(attachments: List<MessageAttachment>): Set<InputModality> =
            attachments.mapTo(linkedSetOf()) { InputModality.of(it.mediaType) }

        /**
         * What the catalog says about one model, addressed the way the UI has it.
         *
         * The chat screen holds a [CatalogState] and a pair of ids, and the
         * catalog is the only thing that knows what a model takes — so this is
         * the lookup that keeps the UI out of the provider repository's
         * internals. A catalog that has not loaded yet answers [UNKNOWN], which
         * is the permissive answer, so a chat opened during the first fetch
         * still accepts a screenshot.
         */
        fun fromCatalog(
            state: CatalogState,
            providerId: String,
            modelId: String,
        ): ModelInputCapabilities {
            val ready = state as? CatalogState.Ready ?: return UNKNOWN
            val provider = ready.providers.firstOrNull { it.id == providerId } ?: return UNKNOWN
            return of(provider.models[modelId])
        }
    }
}

/**
 * What one attachment would ask of a model, as a modality plus the file name.
 *
 * The name is carried so the refusal can say which file it is about: "this
 * model does not accept video" is a puzzle when three files are attached and
 * only one of them is the video.
 */
data class AttachmentRequirement(
    val modality: InputModality,
    val fileName: String,
) {
    /** The modality's own name, for a message that says it in a sentence. */
    val label: String get() = modality.wireName
}

/** The first attachment this model cannot take, or null when it takes them all. */
fun ModelInputCapabilities.firstUnsupported(
    attachments: List<MessageAttachment>,
): AttachmentRequirement? = attachments.asSequence()
    .map { AttachmentRequirement(InputModality.of(it.mediaType), it.fileName) }
    .firstOrNull { !supports(it.modality) }