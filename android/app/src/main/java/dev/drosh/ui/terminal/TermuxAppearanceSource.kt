package dev.drosh.ui.terminal

import android.graphics.Bitmap
import android.graphics.Canvas
import com.termux.view.TerminalView

/**
 * The vendored Termux terminal's appearance, sampled from its own pixels.
 *
 * The Termux renderer draws into a plain `View`, so the pixels can be read
 * back: the view is asked to draw itself into a bitmap of ours, and what
 * comes out is the terminal's own output — the glyphs and all. This is the
 * path the app has always used, and it is the one that gives the chrome a
 * real picture to blur.
 *
 * The sampling is the app's own, unchanged: a capture is a second full draw
 * of the terminal, so it is done on a timer and not per frame.
 */
class TermuxAppearanceSource(private val view: TerminalView) : TerminalAppearanceSource {

    override val kind: TerminalAppearanceKind get() = TerminalAppearanceKind.TERMUX

    override val widthPx: Int get() = view.width

    override val heightPx: Int get() = view.height

    /**
     * The view's own generation counter.
     *
     * The counter advances once per chunk of output, so it says whether the
     * terminal has anything new to show. It is not a rate limit: a download
     * produces thousands of chunks a second, so the interval the caller waits
     * between captures is the real limit and this only avoids redrawing the
     * same content.
     */
    override val contentToken: Int get() = view.contentGeneration

    override fun captureStrip(heightPx: Int): Bitmap? {
        if (view.width <= 0 || view.height <= 0) return null
        val h = heightPx.coerceIn(1, view.height)
        val bitmap = runCatching { Bitmap.createBitmap(view.width, h, Bitmap.Config.ARGB_8888) }
            .getOrNull() ?: return null
        // No translation. The bitmap is h tall and the view is drawn into it as-is, so
        // the view's own rows 0..h land in it and the rest falls outside — which is the
        // *top* strip the pills sit over.
        //
        // It used to translate by -(view.height - h), which lands the view's rows
        // [view.height - h, view.height) in the bitmap: the **bottom** strip. The
        // comment said top, so the mistake was invisible until the pills actually
        // floated over the terminal — and then it showed the last line of output
        // behind the first button on screen.
        val ok = runCatching { view.draw(Canvas(bitmap)) }.isSuccess
        return if (ok) bitmap else bitmap.also { it.recycle() }
    }

    override fun captureRegion(left: Int, top: Int, width: Int, height: Int): Bitmap? {
        if (view.width <= 0 || view.height <= 0) return null
        if (width <= 0 || height <= 0) return null
        val bitmap = runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
            .getOrNull() ?: return null
        // Clip to the region so view.draw only rasterises the part we need.
        val canvas = Canvas(bitmap)
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.translate(-left.toFloat(), -top.toFloat())
        val ok = runCatching { view.draw(canvas) }.isSuccess
        if (!ok) {
            bitmap.recycle()
            return null
        }
        return bitmap
    }
}
