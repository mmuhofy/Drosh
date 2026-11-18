package dev.drosh.terminal.engine.font

/**
 * The atlas's packer: a skyline.
 *
 * Inspired by: github.com/ghostty-org/ghostty — `src/font/Atlas.zig`
 * (`reserve`, `fit`, `merge`).
 * Adapted for Drosh — dev.drosh.
 *
 * ## Why not shelves
 *
 * The atlas used to be filled in shelves: a row left to right, then the next
 * row under the tallest glyph in the one before. That is easy to reason about
 * and it is what a shelf-packing terminal does, but it has one failure mode
 * that a terminal actually hits: when a shelf cannot fit the next glyph, the
 * whole atlas is thrown away and started over. A pinch re-rasterises at every
 * size it passes through, so the atlas fills, and a reset in the middle of a
 * gesture is a visible stutter — and, before the reset was uploaded whole, a
 * screen of wrong glyphs.
 *
 * A skyline keeps the free space as spans and puts a glyph wherever it fits
 * lowest. Glyphs are all about the same height, so the spans stay short and the
 * packing stays tight, and a glyph that does not fit *now* can still fit after
 * the next one — the atlas is only full when it is full.
 *
 * ## The invariant
 *
 * The spans are sorted by `x`, and at any row their widths sum to the atlas's
 * width: they tile it. That is what makes `fitY` terminate, and it is what
 * `merge` restores after an insertion.
 */
internal class Skyline(
    /** The texture's width, in texels. */
    val width: Int,
    /** The texture's height, in texels. */
    val height: Int,
) {

    /** A reserved rectangle, in texels. */
    class Rect(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    /** A free span: [width] texels starting at ([x], [y]). */
    private class Node(
        var x: Int,
        var y: Int,
        var width: Int,
    )

    private val nodes = ArrayList<Node>()

    /**
     * The lowest row the skyline has reached.
     *
     * Not the same as "how full the atlas is": a skyline can have a deep notch
     * on one side and empty space on the other. It is the bottom of the upload
     * range, which is what the renderer needs — rows below it never changed.
     */
    var usedBottom: Int = 0
        private set

    init {
        reset()
    }

    /** One empty atlas. */
    fun reset() {
        nodes.clear()
        nodes.add(Node(0, 0, width))
        usedBottom = 0
    }

    /**
     * Reserve a rectangle, or null when the atlas cannot fit it.
     *
     * The placement is bottom-left: the lowest top edge wins, and a tie goes to
     * the narrowest span, which leaves the wide spans for the glyphs that need
     * them.
     */
    fun reserve(width: Int, height: Int): Rect? {
        if (width == 0 && height == 0) return Rect(0, 0, 0, 0)
        if (width > this.width || height > this.height) return null

        var bestIndex = -1
        var bestWidth = Int.MAX_VALUE
        var bestHeight = Int.MAX_VALUE
        var regionX = 0
        var regionY = 0

        for (index in nodes.indices) {
            val y = fitY(index, width, height) ?: continue
            val node = nodes[index]
            val top = y + height
            if (top < bestHeight || (top == bestHeight && node.width in 1 until bestWidth)) {
                bestIndex = index
                bestWidth = node.width
                bestHeight = top
                regionX = node.x
                regionY = y
            }
        }
        if (bestIndex < 0) return null

        // The glyph's own span: from its top edge down, as wide as it is. What
        // is left of the span it took over is shrunk, and a span that disappears
        // is dropped.
        nodes.add(bestIndex, Node(regionX, regionY + height, width))

        var index = bestIndex + 1
        while (index < nodes.size) {
            val node = nodes[index]
            val previous = nodes[index - 1]
            if (node.x < previous.x + previous.width) {
                val shrink = previous.x + previous.width - node.x
                node.x += shrink
                node.width -= shrink
                if (node.width <= 0) {
                    nodes.removeAt(index)
                    continue
                }
            }
            break
        }
        merge()

        if (regionY + height > usedBottom) usedBottom = regionY + height
        return Rect(regionX, regionY, width, height)
    }

    /**
     * The row a rectangle would sit at if it started at the span [index], or
     * null when it does not fit there.
     *
     * A rectangle can straddle several spans, and it sits on the highest of
     * them: the spans before it are free below that line only if they are no
     * taller than it.
     */
    private fun fitY(index: Int, width: Int, height: Int): Int? {
        val first = nodes[index]
        if (first.x + width > this.width) return null

        var y = first.y
        var cursor = index
        var left = width
        while (left > 0) {
            if (cursor >= nodes.size) return null
            val node = nodes[cursor]
            if (node.y > y) y = node.y
            if (y + height > this.height) return null
            left -= node.width
            cursor += 1
        }
        return y
    }

    /** Join the spans that ended up at the same row. */
    private fun merge() {
        var index = 0
        while (index < nodes.size - 1) {
            val node = nodes[index]
            val next = nodes[index + 1]
            if (node.y == next.y) {
                node.width += next.width
                nodes.removeAt(index + 1)
                continue
            }
            index += 1
        }
    }
}
