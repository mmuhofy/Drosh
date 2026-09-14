package com.iris.irisshell.ui.input

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.asAndroidBitmap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Backdrop sampler for the classic TerminalView.
 *
 * Important: RenderEffect is applied to the sampled backdrop layer itself.
 * The terminal is therefore explicitly sampled first; this is what makes
 * the effect a real backdrop-style blur instead of merely blurring the
 * glass overlay.
 */
class LiquidGlassBackdropView(
    private val sourceView: View,
) : View(sourceView.context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var sourceBitmap: Bitmap? = null
    private var lastCaptureWidth = 0
    private var lastCaptureHeight = 0
    private var lastCaptureAt = 0L
    private var dirty = AtomicBoolean(true)

    private val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        dirty.set(true)
        invalidate()
    }

    init {
        setWillNotDraw(false)
        if (Build.VERSION.SDK_INT >= 31) {
            setRenderEffect(
                RenderEffect.createBlurEffect(
                    18f,
                    18f,
                    Shader.TileMode.CLAMP,
                )
            )
        }
        sourceView.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)
    }

    override fun onDetachedFromWindow() {
        sourceView.viewTreeObserver.removeOnGlobalLayoutListener(globalLayoutListener)
        sourceBitmap?.recycle()
        sourceBitmap = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (width <= 0 || height <= 0 || !sourceView.isAttachedToWindow) return

        // Do not capture continuously. Terminal text normally changes in bursts,
        // and 15 FPS is already visually sufficient for a glass backdrop.
        val now = System.currentTimeMillis()
        if (dirty.get() || now - lastCaptureAt >= 66L) {
            capture()
            lastCaptureAt = now
            dirty.set(false)
        }

        val bitmap = sourceBitmap ?: return
        paint.alpha = 255

        val src = android.graphics.Rect(
            0,
            0,
            bitmap.width,
            bitmap.height,
        )
        val dst = android.graphics.Rect(
            0,
            0,
            width,
            height,
        )
        canvas.drawBitmap(bitmap, src, dst, paint)
    }

    fun markDirty() {
        dirty.set(true)
        postInvalidateOnAnimation()
    }

    private fun capture() {
        val sw = sourceView.width
        val sh = sourceView.height
        if (sw <= 0 || sh <= 0) return

        val sourceLocation = IntArray(2)
        val targetLocation = IntArray(2)
        sourceView.getLocationInWindow(sourceLocation)
        getLocationInWindow(targetLocation)

        val left = targetLocation[0] - sourceLocation[0]
        val top = targetLocation[1] - sourceLocation[1]

        val cropLeft = left.coerceIn(0, (sw - 1).coerceAtLeast(0))
        val cropTop = top.coerceIn(0, (sh - 1).coerceAtLeast(0))
        val cropRight = (left + width).coerceIn(cropLeft + 1, sw)
        val cropBottom = (top + height).coerceIn(cropTop + 1, sh)

        val cropWidth = cropRight - cropLeft
        val cropHeight = cropBottom - cropTop
        if (cropWidth <= 0 || cropHeight <= 0) return

        val bitmap = Bitmap.createBitmap(
            cropWidth,
            cropHeight,
            Bitmap.Config.ARGB_8888,
        )

        val bitmapCanvas = Canvas(bitmap)
        bitmapCanvas.translate(-cropLeft.toFloat(), -cropTop.toFloat())

        // This is deliberately a direct View draw. TerminalView is a Canvas-backed
        // Android View, so this avoids introducing a second terminal renderer.
        sourceView.draw(bitmapCanvas)

        sourceBitmap?.recycle()
        sourceBitmap = bitmap

        lastCaptureWidth = cropWidth
        lastCaptureHeight = cropHeight
    }
}
