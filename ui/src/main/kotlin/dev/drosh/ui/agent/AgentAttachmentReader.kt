package dev.drosh.ui.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import dev.drosh.domain.agent.AgentWarning
import dev.drosh.domain.agent.AttachmentKind
import dev.drosh.domain.agent.AttachmentLimits
import dev.drosh.domain.agent.MessageAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns a picked document into something a provider can be sent.
 *
 * ## Why this lives in `:ui`
 *
 * Reading a `content://` Uri needs `ContentResolver`, decoding needs
 * `BitmapFactory`, base64 needs `android.util.Base64` and grabbing a video
 * frame needs `MediaMetadataRetriever`. `:domain` is a pure Kotlin module with
 * no Android in it, so the whole job is here, and what comes out is a plain
 * [MessageAttachment] that nothing above this file has to know was ever a file.
 *
 * ## Why the bytes are shrunk rather than only checked
 *
 * A modern phone camera produces a 6–12 MB JPEG. Refusing all of them would
 * make the feature useless exactly where it is wanted — "look at this screen,
 * why is this broken" is the question attachments exist for — so a picture
 * past the limit is downscaled to [AttachmentLimits.MAX_IMAGE_EDGE_PX] and
 * re-encoded before the limit is applied. Only a picture that is still too big
 * afterwards is refused, and the refusal names the file.
 *
 * ## Quality is stepped down, not the file
 *
 * Re-encoding is tried in order and stops at the first attempt inside the
 * limit, so a screenshot that only needed a JPEG pass never pays for a JPEG
 * pass. The last rung throws the alpha channel away, which is why it is last:
 * a transparent PNG becomes a picture with a black background rather than an
 * error message.
 */
object AgentAttachmentReader {

    private const val TAG = "AgentAttachment"

    /** Longest edge a video's preview frame is scaled to. */
    private const val THUMBNAIL_EDGE_PX = 512

    /** Tried in order; the first result inside the limit wins. */
    private val IMAGE_RUNGS: List<ImageRung> = listOf(
        // Big lossless: the case a screenshot lands in, and the only rung that
        // keeps a UI with flat colour areas intact.
        ImageRung(maxEdge = AttachmentLimits.MAX_IMAGE_EDGE_PX, format = Bitmap.CompressFormat.PNG, quality = 100),
        ImageRung(maxEdge = AttachmentLimits.MAX_IMAGE_EDGE_PX, format = Bitmap.CompressFormat.WEBP, quality = 90),
        ImageRung(maxEdge = AttachmentLimits.MAX_IMAGE_EDGE_PX, format = Bitmap.CompressFormat.JPEG, quality = 85),
        ImageRung(maxEdge = 1400, format = Bitmap.CompressFormat.JPEG, quality = 75),
        ImageRung(maxEdge = 1000, format = Bitmap.CompressFormat.JPEG, quality = 60),
    )

    /** Result of one re-encoding attempt. */
    private data class ImageRung(
        val maxEdge: Int,
        val format: Bitmap.CompressFormat,
        val quality: Int,
    )

    /** Either an attachment worth keeping, or the reason there is not one. */
    sealed interface Outcome {

        data class Loaded(val attachment: MessageAttachment) : Outcome

        data class Refused(val warning: AgentWarning) : Outcome
    }

    /**
     * Read [uri] into an attachment, or say why it cannot become one.
     *
     * Never throws: every failure the picker and the codecs can produce comes
     * back as a refusal the composer can render, because this is called from a
     * gesture and an exception here would take the chat down with it.
     */
    suspend fun read(context: Context, uri: Uri, id: String): Outcome = withContext(Dispatchers.IO) {
        try {
            val fileName = displayName(context, uri)
            val mediaType = AttachmentLimits.normalizeMediaType(
                context.contentResolver.getType(uri) ?: guessFromName(fileName),
            )

            if (!AttachmentLimits.isSupported(mediaType)) {
                return@withContext Outcome.Refused(
                    AgentWarning.UnsupportedType(fileName, mediaType),
                )
            }

            when (AttachmentKind.of(mediaType)) {
                AttachmentKind.IMAGE -> readImage(context, uri, id, fileName, mediaType)
                AttachmentKind.VIDEO -> readVideo(context, uri, id, fileName, mediaType)
                AttachmentKind.AUDIO ->
                    Outcome.Refused(AgentWarning.UnsupportedType(fileName, mediaType))

                AttachmentKind.DOCUMENT ->
                    Outcome.Refused(AgentWarning.UnsupportedType(fileName, mediaType))
            }
        } catch (cancelled: CancellationException) {
            // The composer went away mid-read. Swallowing this would report the
            // read as a broken file to a ViewModel nobody is showing any more.
            throw cancelled
        } catch (denied: SecurityException) {
            // A persisted Uri grant that has since been revoked. Not worth a
            // stack trace; the user gets the same message as a broken file.
            Log.w(TAG, "attachment read denied", denied)
            Outcome.Refused(AgentWarning.UnreadableAttachment())
        } catch (error: OutOfMemoryError) {
            // A very large image on a small heap. The only safe move is to
            // decline: the alternative is a process death mid-compose.
            Log.w(TAG, "attachment read ran out of memory", error)
            Outcome.Refused(AgentWarning.AttachmentTooLarge("", 0, AttachmentLimits.MAX_BASE64_BYTES))
        } catch (error: Exception) {
            Log.w(TAG, "attachment read failed", error)
            Outcome.Refused(AgentWarning.UnreadableAttachment())
        }
    }

    // ── images ─────────────────────────────────────────────────────────────

    private fun readImage(
        context: Context,
        uri: Uri,
        id: String,
        fileName: String,
        mediaType: String,
    ): Outcome {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(context, uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return Outcome.Refused(AgentWarning.UnreadableAttachment(fileName))
        }

        val source = decodeSampled(context, uri, bounds) ?: return Outcome.Refused(
            AgentWarning.UnreadableAttachment(fileName),
        )

        var smallestBytes = Int.MAX_VALUE
        try {
            for (rung in IMAGE_RUNGS) {
                val scaled = scaleToFit(source, rung.maxEdge)
                val encoded = encode(scaled, rung)
                smallestBytes = minOf(smallestBytes, encoded.size)
                if (encoded.size <= AttachmentLimits.MAX_BASE64_BYTES) {
                    return Outcome.Loaded(
                        MessageAttachment.fromBase64(
                            id = id,
                            mediaType = mediaType,
                            base64 = Base64.encodeToString(encoded, Base64.NO_WRAP),
                            fileName = fileName,
                            byteSize = encoded.size,
                        ),
                    )
                }
            }
        } finally {
            source.recycle()
        }

        // Every rung was too big, which for an image means the pixel count is
        // the problem rather than the format.
        return Outcome.Refused(
            AgentWarning.AttachmentTooLarge(
                fileName = fileName,
                byteSize = smallestBytes,
                maxByteSize = AttachmentLimits.MAX_BASE64_BYTES,
            ),
        )
    }

    /**
     * Decode at a power-of-two sample, which is the only subsampling
     * `BitmapFactory` offers.
     *
     * Decoding straight to full size to re-scale it is the thing that kills the
     * heap: a 4000x3000 camera JPEG is 48 MB as an `ARGB_8888` bitmap, and on
     * a phone that is an `OutOfMemoryError` rather than a slow picture.
     */
    private fun decodeSampled(context: Context, uri: Uri, bounds: BitmapFactory.Options): Bitmap? {
        var sample = 1
        val longestEdge = max(bounds.outWidth, bounds.outHeight)
        while (longestEdge / sample > AttachmentLimits.MAX_IMAGE_EDGE_PX * SAMPLE_HEADROOM) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return openStream(context, uri).use { BitmapFactory.decodeStream(it, null, options) }
    }

    /**
     * Fit the bitmap inside [maxEdge], preserving aspect ratio.
     *
     * Returns the original when it already fits, so a small picture is never
     * resampled for the sake of it.
     */
    private fun scaleToFit(source: Bitmap, maxEdge: Int): Bitmap {
        val longestEdge = max(source.width, source.height)
        if (longestEdge <= maxEdge) return source

        val ratio = maxEdge.toFloat() / longestEdge
        val width = (source.width * ratio).roundToInt().coerceAtLeast(1)
        val height = (source.height * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun encode(bitmap: Bitmap, rung: ImageRung): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(rung.format, rung.quality, out)
        // A codec that refuses the format writes nothing rather than failing.
        // Reporting zero keeps the loop moving on to the next rung instead of
        // attaching a file whose payload is an empty string.
        return out.toByteArray().takeIf { it.isNotEmpty() } ?: ByteArray(0)
    }

    // ── video ──────────────────────────────────────────────────────────────

    /**
     * Read a video's bytes plus one frame of it.
     *
     * The frame is for the composer chip only — a video is not something a
     * model is shown as a picture. Whether the bytes go anywhere at all is
     * decided by the protocol check in `MediaEncoding`, which only Gemini has
     * a documented inline path for.
     */
    private fun readVideo(
        context: Context,
        uri: Uri,
        id: String,
        fileName: String,
        mediaType: String,
    ): Outcome {
        val thumbnail = runCatching { videoThumbnail(context, uri) }.getOrNull()

        val bytes = readCapped(context, uri, MAX_SOURCE_BYTES)
            ?: return Outcome.Refused(
                AgentWarning.AttachmentTooLarge(
                    fileName = fileName,
                    byteSize = MAX_SOURCE_BYTES,
                    maxByteSize = AttachmentLimits.MAX_BASE64_BYTES,
                ),
            )

        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        if (base64.length > AttachmentLimits.MAX_BASE64_BYTES) {
            return Outcome.Refused(
                AgentWarning.AttachmentTooLarge(
                    fileName = fileName,
                    byteSize = bytes.size,
                    maxByteSize = AttachmentLimits.MAX_BASE64_BYTES,
                ),
            )
        }

        return Outcome.Loaded(
            MessageAttachment.fromBase64(
                id = id,
                mediaType = mediaType,
                base64 = base64,
                fileName = fileName,
                byteSize = bytes.size,
                thumbnailDataUrl = thumbnail,
            ),
        )
    }

    /**
     * One frame, [THUMBNAIL_EDGE_PX] on its longest side.
     *
     * Frame zero, then a second later: a video that opens on black — which a
     * great many do — previews as a black chip that looks like a failed
     * attachment. `getFrameAtTime` returning null is normal for a codec that
     * cannot seek, which is why this never throws.
     */
    private fun videoThumbnail(context: Context, uri: Uri): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val frame = retriever.getFrameAtTime(THUMBNAIL_TIME_US)
                ?: retriever.getFrameAtTime(SECOND_IN_US)
                ?: return null
            val scaled = scaleToFit(frame, THUMBNAIL_EDGE_PX)
            try {
                val out = ByteArrayOutputStream()
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, out)) return null
                AttachmentLimits.dataUrlFor(
                    mediaType = JPEG_MEDIA_TYPE,
                    base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP),
                )
            } finally {
                if (scaled !== frame) scaled.recycle()
                frame.recycle()
            }
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Read at most [limit] bytes, or null when the file is longer than that.
     *
     * Null rather than a truncated read on purpose: half a video base64ed and
     * sent is a request the provider accepts and answers nonsense about.
     */
    private fun readCapped(context: Context, uri: Uri, limit: Int): ByteArray? =
        openStream(context, uri).use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(READ_CHUNK)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) return null
                out.write(buffer, 0, read)
            }
            out.toByteArray().takeIf { it.isNotEmpty() }
        }

    // ── shared ─────────────────────────────────────────────────────────────

    private fun openStream(context: Context, uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("The picked document could not be opened")

    /**
     * The name a user would recognise.
     *
     * The display column when the provider offers one, and the last path
     * segment otherwise — which is enough for the picker that hands back a
     * synthesized Uri with no metadata at all.
     */
    private fun displayName(context: Context, uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                }
        }.getOrNull()

        return fromProvider?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()
    }

    /** Extension-based fallback for the providers that decline to say. */
    private fun guessFromName(fileName: String): String = when (
        fileName.substringAfterLast('.', "").lowercase()
    ) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mov", "qt" -> "video/quicktime"
        else -> "application/octet-stream"
    }

    /**
     * How many times the sampler may leave the result over the target edge.
     *
     * `inSampleSize` halves, so the decoded bitmap lands somewhere between one
     * and two target widths; `scaleToFit` finishes the job exactly afterwards.
     */
    private const val SAMPLE_HEADROOM = 2

    /** Source bytes read before the request is refused as too large. */
    private const val MAX_SOURCE_BYTES = AttachmentLimits.MAX_BASE64_BYTES

    private const val READ_CHUNK = 64 * 1024

    private const val THUMBNAIL_TIME_US = 0L

    private const val SECOND_IN_US = 1_000_000L

    private const val THUMBNAIL_QUALITY = 75

    private const val JPEG_MEDIA_TYPE = "image/jpeg"
}