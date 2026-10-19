package dev.drosh.domain.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The refusal rules, asserted where they live.
 *
 * These are the assertions that matter most for the bug this replaced: an
 * image that is refused, and an image that is not, are both user-visible
 * decisions, and the difference between them is one boolean in a catalog
 * document fetched over the network.
 */
class ModelInputCapabilitiesTest {

    private fun model(
        attachment: Boolean = false,
        inputModalities: List<String> = emptyList(),
    ) = CatalogModel(
        id = "test/model",
        label = "Test",
        attachment = attachment,
        inputModalities = inputModalities,
    )

    @Test
    fun `a model nothing has said about takes anything`() {
        val capabilities = ModelInputCapabilities.of(null)

        assertFalse(capabilities.isDeclared)
        assertTrue(capabilities.supports(InputModality.IMAGE))
        assertTrue(capabilities.supports(InputModality.VIDEO))
        assertTrue(capabilities.supports(InputModality.AUDIO))
    }

    @Test
    fun `the attachment flag is a declaration, not an absence`() {
        assertTrue(ModelInputCapabilities.of(model(attachment = true)).supports(InputModality.IMAGE))
        assertFalse(ModelInputCapabilities.of(model(attachment = false)).supports(InputModality.IMAGE))
    }

    @Test
    fun `a named modality list wins over the boolean`() {
        val capabilities = ModelInputCapabilities.of(
            model(attachment = false, inputModalities = listOf("image", "video")),
        )

        assertTrue(capabilities.supports(InputModality.IMAGE))
        assertTrue(capabilities.supports(InputModality.VIDEO))
        // Audio was never named, so it is refused — the list is the source of
        // truth and the flag underneath it is not consulted again.
        assertFalse(capabilities.supports(InputModality.AUDIO))
    }

    @Test
    fun `text is implied rather than required in the list`() {
        val capabilities = ModelInputCapabilities.of(
            model(inputModalities = listOf("image")),
        )

        assertTrue(capabilities.supports(InputModality.TEXT))
    }

    @Test
    fun `an unrecognised modality name is dropped rather than guessed`() {
        val capabilities = ModelInputCapabilities.of(
            model(inputModalities = listOf("image", "hologram")),
        )

        assertTrue(capabilities.supports(InputModality.IMAGE))
        assertFalse(capabilities.supports(InputModality.AUDIO))
    }

    @Test
    fun `firstUnsupported names the file, not just the kind`() {
        // Images yes, video no: otherwise the first thing refused would be the
        // png and this would be asserting the order of the list, not the fact
        // that the requirement carries a file name.
        val capabilities = ModelInputCapabilities.of(model(inputModalities = listOf("image")))
        val attachments = listOf(
            attachment("a.png", "image/png"),
            attachment("clip.mp4", "video/mp4"),
        )

        val refused = capabilities.firstUnsupported(attachments)

        assertEquals("clip.mp4", refused?.fileName)
        assertEquals(InputModality.VIDEO, refused?.modality)
    }

    @Test
    fun `a catalog that has not loaded answers permissively`() {
        val capabilities = ModelInputCapabilities.fromCatalog(
            state = CatalogState.Loading,
            providerId = "openrouter",
            modelId = "anthropic/claude-sonnet-4",
        )

        assertTrue(capabilities.supports(InputModality.IMAGE))
    }

    @Test
    fun `a catalog that is ready answers for the model asked about`() {
        val capabilities = ModelInputCapabilities.fromCatalog(
            state = CatalogState.Ready(
                listOf(
                    CatalogProvider(
                        id = "openrouter",
                        label = "OpenRouter",
                        models = mapOf(
                            "vendor/vision" to model(attachment = true),
                            "vendor/text" to model(attachment = false),
                        ),
                    ),
                ),
            ),
            providerId = "openrouter",
            modelId = "vendor/vision",
        )

        assertTrue(capabilities.supports(InputModality.IMAGE))
    }

    @Test
    fun `a model the catalog never listed is not refused`() {
        val capabilities = ModelInputCapabilities.fromCatalog(
            state = CatalogState.Ready(listOf(CatalogProvider(id = "custom", label = "Custom"))),
            providerId = "custom",
            modelId = "llama3.1:8b",
        )

        assertTrue(capabilities.supports(InputModality.IMAGE))
    }

    // ── the one place a send is refused ───────────────────────────────────

    @Test
    fun `a text-only model refuses an image and says which file`() {
        val warning = attachmentWarning(
            attachments = listOf(attachment("shot.png", "image/png")),
            capabilities = ModelInputCapabilities.of(model(attachment = false)),
            modelId = "vendor/text",
        )

        val lack = warning as AgentWarning.ModelLacksModality
        assertEquals("vendor/text", lack.modelId)
        assertEquals(InputModality.IMAGE, lack.modality)
        assertEquals("shot.png", lack.fileName)
    }

    @Test
    fun `an unknown model refuses nothing`() {
        assertNull(
            attachmentWarning(
                attachments = listOf(attachment("shot.png", "image/png")),
                capabilities = ModelInputCapabilities.UNKNOWN,
                modelId = "anything",
            ),
        )
    }

    @Test
    fun `a file type the app cannot send is refused before the model is asked`() {
        val warning = attachmentWarning(
            attachments = listOf(attachment("notes.pdf", "application/pdf")),
            capabilities = ModelInputCapabilities.UNKNOWN,
            modelId = "anything",
        )

        assertEquals(
            AgentWarning.UnsupportedType("notes.pdf", "application/pdf"),
            warning,
        )
    }

    @Test
    fun `too many files is refused whatever they are`() {
        val many = List(AttachmentLimits.MAX_ATTACHMENTS_PER_MESSAGE + 1) { index ->
            attachment("shot$index.png", "image/png")
        }

        val warning = attachmentWarning(
            attachments = many,
            capabilities = ModelInputCapabilities.UNKNOWN,
            modelId = "anything",
        )

        assertEquals(
            AgentWarning.TooManyAttachments(AttachmentLimits.MAX_ATTACHMENTS_PER_MESSAGE),
            warning,
        )
    }

    @Test
    fun `nothing attached is not a problem`() {
        assertNull(
            attachmentWarning(emptyList(), ModelInputCapabilities.UNKNOWN, "anything"),
        )
    }

    private fun attachment(name: String, mediaType: String) = MessageAttachment(
        id = name,
        mediaType = mediaType,
        dataUrl = AttachmentLimits.dataUrlFor(mediaType, "AAAA"),
        fileName = name,
        byteSize = 3,
    )
}