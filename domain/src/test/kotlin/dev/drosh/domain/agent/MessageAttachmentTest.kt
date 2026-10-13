package dev.drosh.domain.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The attachment limits and the data URL around them.
 *
 * Base64 inflates by a third, so a limit stated on the wrong side of the
 * encoding is a limit that is off by a third. These are the assertions that pin
 * which side it is stated on.
 */
class MessageAttachmentTest {

    @Test
    fun `the payload is the data url with its prefix removed`() {
        val attachment = MessageAttachment.fromBase64(
            id = "a1",
            mediaType = "image/png",
            base64 = "AAAA",
            fileName = "shot.png",
            byteSize = 3,
        )

        assertEquals("data:image/png;base64,AAAA", attachment.dataUrl)
        assertEquals("AAAA", attachment.base64Payload)
        assertEquals(AttachmentKind.IMAGE, attachment.kind)
        assertTrue(attachment.isImage)
    }

    @Test
    fun `a string that is not a data url yields no payload rather than throwing`() {
        // An adapter handed something malformed must build a request the
        // provider rejects, not crash while building one.
        val broken = MessageAttachment(id = "a1", mediaType = "image/png", dataUrl = "nonsense")

        assertEquals("", broken.base64Payload)
    }

    @Test
    fun `an image is its own preview and a video shows its frame`() {
        val image = MessageAttachment.fromBase64("a1", "image/png", "AAAA")
        val video = MessageAttachment.fromBase64(
            id = "a2",
            mediaType = "video/mp4",
            base64 = "AAAA",
            thumbnailDataUrl = "data:image/jpeg;base64,BBBB",
        )

        assertEquals(image.dataUrl, image.previewDataUrl)
        assertEquals("data:image/jpeg;base64,BBBB", video.previewDataUrl)
    }

    @Test
    fun `an audio attachment has nothing to draw`() {
        val audio = MessageAttachment.fromBase64("a1", "audio/mpeg", "AAAA")

        assertNull(audio.previewDataUrl)
        assertEquals(AttachmentKind.AUDIO, audio.kind)
    }

    @Test
    fun `the limit is on the encoded payload, not the file`() {
        val under = AttachmentLimits.dataUrlFor("image/png", "A".repeat(10))
        val over = AttachmentLimits.dataUrlFor("image/png", "A".repeat(AttachmentLimits.MAX_BASE64_BYTES + 1))

        assertFalse(AttachmentLimits.exceedsLimit(under))
        assertTrue(AttachmentLimits.exceedsLimit(over))
    }

    @Test
    fun `the payload length excludes the prefix`() {
        val url = AttachmentLimits.dataUrlFor("image/png", "A".repeat(64))

        assertEquals(64, AttachmentLimits.payloadLength(url))
    }

    @Test
    fun `jpg is spelled the way providers spell it`() {
        assertEquals("image/jpeg", AttachmentLimits.normalizeMediaType("image/jpg"))
        assertEquals("image/jpeg", AttachmentLimits.normalizeMediaType("IMAGE/JPEG"))
        assertEquals("image/png", AttachmentLimits.normalizeMediaType("image/png; charset=binary"))
    }

    @Test
    fun `heic is a jpeg on the wire or it is nothing`() {
        // Both spellings are accepted by the picker and rejected by some
        // providers, so the reader rewrites rather than passes them through.
        assertEquals("image/jpeg", AttachmentLimits.normalizeMediaType("image/heic"))
        assertTrue(AttachmentLimits.isSupported("image/heic"))
    }

    @Test
    fun `a pdf is not something the app can send inline`() {
        assertFalse(AttachmentLimits.isSupported("application/pdf"))
        assertFalse(AttachmentLimits.isSupported("application/octet-stream"))
        assertEquals(AttachmentKind.DOCUMENT, AttachmentKind.of("application/pdf"))
    }

    @Test
    fun `the three image formats and three video containers are the supported set`() {
        assertTrue(AttachmentLimits.isSupported("image/png"))
        assertTrue(AttachmentLimits.isSupported("image/jpeg"))
        assertTrue(AttachmentLimits.isSupported("image/webp"))
        assertTrue(AttachmentLimits.isSupported("image/gif"))
        assertTrue(AttachmentLimits.isSupported("video/mp4"))
        assertFalse(AttachmentLimits.isSupported("video/avi"))
    }
}