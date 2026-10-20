package dev.drosh.ui.terminal

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Where the chrome above a terminal reads that terminal's appearance from.
 *
 * The app draws pills, a top bar and a selection menu over the terminal, and
 * all of them want the terminal's own look: the picture behind a pill to blur,
 * the colour behind the menu to tint it with. The terminal underneath may be
 * drawn by either renderer, and the two answer differently:
 *
 *  - [TermuxAppearanceSource] — the vendored Termux view draws into itself,
 *    so its pixels can be sampled. The answers are real pictures.
 *  - [EngineAppearanceSource] — the Rust engine draws through OpenGL, so
 *    there are no pixels to sample; the session's cells are asked instead.
 *    The answers are the terminal's colours, not a picture of its output.
 *
 * The chrome asks this interface and never asks what is behind it, so the
 * two paths cannot be crossed: a Termux-backed terminal is only ever sampled
 * from its pixels, and an engine-backed one only ever from its cells.
 *
 * ## The sampling discipline belongs to the caller
 *
 * Nothing here runs on a timer and nothing here caches. A capture is a real
 * piece of work — for the Termux view a full draw of the terminal, for the
 * engine a trip across the JNI boundary — so the caller decides how often to
 * ask, and asks only when [contentToken] says there is something new.
 */
sealed interface TerminalAppearanceSource {

    /** What draws the terminal this source describes. */
    val kind: TerminalAppearanceKind

    /** The width of the terminal, in pixels. */
    val widthPx: Int

    /** The height of the terminal, in pixels. */
    val heightPx: Int

    /**
     * A token that changes when the terminal's content changes.
     *
     * The chrome samples on a timer and compares the token with the one it
     * saw last, so a terminal that has not changed keeps the picture it
     * already has. The token is compared by the caller, which means it is
     * compared against whatever the caller last saw — a caller that starts
     * over samples again.
     */
    val contentToken: Int

    /**
     * A picture of the terminal's top strip, [heightPx] tall.
     *
     * The strip starts at the terminal's top-left corner and is measured in
     * the terminal's own pixels, so the chrome can slice it by the position
     * of whatever floats over it.
     *
     * Null when there is nothing to show: no terminal, or no content in it.
     */
    fun captureStrip(heightPx: Int): Bitmap?

    /**
     * A picture of the terminal at ([left], [top]), [width] by [height]
     * pixels.
     *
     * The picture is [width] by [height] pixels and the terminal is drawn
     * into it with its own origin at ([left], [top]) — so a region that hangs
     * over the edge of the terminal shows that edge, and a region that is
     * entirely outside it shows nothing at all (null).
     */
    fun captureRegion(left: Int, top: Int, width: Int, height: Int): Bitmap?
}

/**
 * The region at ([left], [top]), [width] by [height] pixels, clamped to the
 * terminal.
 *
 * A region that hangs over the edge is cut back to it: sampling past the edge
 * yields empty pixels, which the blur then smears into the menu. A region that
 * is entirely outside the terminal has nothing to sample and answers null.
 *
 * The returned rectangle carries the clamped **origin**; the picture itself is
 * still [width] by [height] pixels, so a region that was cut back shows the
 * edge of the terminal rather than a smaller picture.
 */
internal fun TerminalAppearanceSource.clampRegion(
    left: Int,
    top: Int,
    width: Int,
    height: Int,
): Rect? {
    val rect = Rect(left, top, left + width, top + height).also {
        it.left = it.left.coerceAtLeast(0)
        it.top = it.top.coerceAtLeast(0)
        it.right = it.right.coerceAtMost(widthPx)
        it.bottom = it.bottom.coerceAtMost(heightPx)
    }
    return if (rect.width() <= 0 || rect.height() <= 0) null else rect
}
