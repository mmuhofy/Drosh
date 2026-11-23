package dev.drosh.feature.terminal

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** The tag the host logs under, shared by the capture helpers. */
internal const val TERMINAL_HOST_TAG = "GhosttyTerminalHost"

/** How often a visible session's last frame is refreshed. */
internal const val THUMBNAIL_CAPTURE_INTERVAL_MS = 2_000L

/**
 * Capture width for a session card.
 *
 * A card is a few hundred dp wide on the grid, and the old 160px capture was
 * being scaled up into it — the source of the soft, shifting previews. Capture
 * above the card's size so the downscale is done by the decoder, at a fixed
 * width so the bitmap's shape does not change with the pane's layout.
 */
private const val THUMBNAIL_WIDTH = 480

/** Guard against an absurdly tall bitmap for a very narrow pane. */
private const val THUMBNAIL_MAX_HEIGHT = 960

/**
 * Copy one frame from the pane's surface into a stable bitmap and hand it up.
 *
 * // UNTESTED — verify before use
 * PixelCopy is asynchronous and reports through a listener on the main looper;
 * neither the copy nor a failure waits on the render thread. A secure or already
 * destroyed surface can refuse the request — that is logged and dropped, never
 * retried in a tight loop and never allowed to block a frame.
 */
internal fun captureSessionThumbnail(
    surface: Surface,
    viewWidth: Int,
    viewHeight: Int,
    sessionId: String,
    shouldPublish: (String, Bitmap) -> Boolean,
    onThumbnail: (String, ImageBitmap) -> Unit,
) {
    // UNTESTED — verify before use
    if (sessionId.isEmpty() || viewWidth <= 0 || viewHeight <= 0 || !surface.isValid) return
    val width = THUMBNAIL_WIDTH
    val height = (width.toFloat() * viewHeight / viewWidth)
        .toInt()
        .coerceIn(1, THUMBNAIL_MAX_HEIGHT)
    val bitmap = runCatching {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }.getOrElse { error ->
        Log.w(TERMINAL_HOST_TAG, "thumbnail bitmap allocation failed: ${error.message}")
        return
    }
    PixelCopy.request(
        surface,
        bitmap,
        { result ->
            if (result == PixelCopy.SUCCESS) {
                // UNTESTED — verify before use: an idle terminal returns the same
                // pixels on every tick, and republishing those reallocated the
                // card's image at 2s intervals, which read as a flicker.
                if (shouldPublish(sessionId, bitmap)) {
                    onThumbnail(sessionId, bitmap.asImageBitmap())
                } else {
                    Log.d(TERMINAL_HOST_TAG, "thumbnail unchanged for $sessionId — not published")
                }
            } else {
                Log.w(TERMINAL_HOST_TAG, "PixelCopy failed for $sessionId: $result")
            }
        },
        Handler(Looper.getMainLooper()),
    )
}

/**
 * De-duplicates and throttles thumbnails before they reach the session grid.
 *
 * // UNTESTED — verify before use
 * A signature is a sparse sample of the frame rather than a full pixel compare:
 * a terminal is mostly still between keystrokes, and two 480px bitmaps are
 * cheap to compare this way on the main looper. A transparent capture is dropped
 * because the surface can exist before its first frame — publishing that gave
 * the card an empty picture.
 *
 * State is kept per session: this single publisher serves every live session's
 * captures (polled ones and the final frames of ones going background), so a
 * global "last session" would either throttle the wrong card or republish an
 * unchanged one. All calls arrive on the main looper — PixelCopy's listener and
 * the polling coroutine both run there — so the map needs no lock.
 */
internal class ThumbnailPublisher(private val minIntervalMs: Long) {
    private data class Published(val signature: IntArray, val atMs: Long)

    private val published = HashMap<String, Published>()

    /**
     * [force] skips only the throttle: a final capture of a session leaving the
     * pane must land even when its last normal capture was seconds ago. Dedupe
     * and the transparency check are unchanged, so an unchanged or empty frame
     * is never published.
     */
    fun shouldPublish(sessionId: String, bitmap: Bitmap, force: Boolean = false): Boolean {
        if (sessionId.isEmpty() || bitmap.isRecycled) return false
        if (bitmap.width <= 0 || bitmap.height <= 0 || !bitmap.hasVisiblePixels()) return false

        val signature = bitmap.signature()
        val previous = published[sessionId]
        if (previous != null && signature.contentEquals(previous.signature)) return false
        val now = SystemClock.elapsedRealtime()
        if (!force && previous != null && now - previous.atMs < minIntervalMs) return false

        published[sessionId] = Published(signature, now)
        return true
    }
}

/** Sparse sample of a bitmap, stable enough to tell a changed frame from a still one. */
private fun Bitmap.signature(): IntArray {
    val samples = THUMBNAIL_SIGNATURE_SAMPLES
    val out = IntArray(samples * samples)
    for (y in 0 until samples) {
        val py = y * (height - 1) / (samples - 1)
        for (x in 0 until samples) {
            val px = x * (width - 1) / (samples - 1)
            out[y * samples + x] = getPixel(px, py)
        }
    }
    return out
}

/** True when any sampled pixel is not fully transparent. */
private fun Bitmap.hasVisiblePixels(): Boolean {
    val samples = THUMBNAIL_SIGNATURE_SAMPLES
    for (y in 0 until samples) {
        val py = y * (height - 1) / (samples - 1)
        for (x in 0 until samples) {
            val px = x * (width - 1) / (samples - 1)
            if (getPixel(px, py) and OPAQUE_ALPHA_MASK != 0) return true
        }
    }
    return false
}

private const val THUMBNAIL_SIGNATURE_SAMPLES = 16
private const val OPAQUE_ALPHA_MASK = -0x1000000
