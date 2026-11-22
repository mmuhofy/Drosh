package dev.drosh.terminal.engine.bench

import java.util.Locale

/**
 * Frame timings for the render loop, and what each frame was made of.
 *
 * **Monotone counters, not a sliding window.** The first version kept a ring of
 * the last 240 frames and printed its percentiles twice a second; a number that
 * changes while it is being read cannot be reported — which is exactly what
 * happened. These counters only ever grow, so the line a user reads is a superset
 * of the line they read a moment ago, and "freeze" turns it into an artefact that
 * can be sent as-is (the app logs it under `DroshBench`).
 *
 * The frame is also split into the phases it is made of, because a number nobody
 * can attribute is a number nobody can improve:
 *
 * - **pump**: the snapshot call. It takes the grid lock, so it also waits while
 *   the parser is busy. This is the phase the lock-shortening work attacks.
 * - **build**: turning the cells into vertices.
 * - **upload**: handing those vertices and the atlas to the GPU.
 * - **draw**: the GL submit and the swap.
 *
 * Only frames that actually drew are counted: a frame that draws nothing leaves
 * the previous picture on the screen, and its cost is nobody's problem. A frame
 * is **janky** when the work took longer than two refreshes.
 */
internal class FrameStats {

    /** Work buckets, milliseconds: `<2`, `<4`, `<8`, `<16`, `<33`, `>=33`. */
    private val work = IntArray(BUCKETS)

    /** Frame-interval buckets, same edges. */
    private val interval = IntArray(BUCKETS)

    private var frames = 0
    private var longs = 0
    private var maxWork = 0f
    private var maxInterval = 0f
    private var pumpTotal = 0f
    private var buildTotal = 0f
    private var uploadTotal = 0f
    private var drawTotal = 0f

    /**
     * What the frames were made of.
     *
     * These are the numbers a change to the renderer is measured against: a
     * renderer that walks fewer cells, uploads fewer bytes and issues fewer
     * draw calls is a renderer that got faster, and a renderer that says so
     * without saying why is a renderer whose next change is guesswork.
     */
    private var cellsTotal = 0L
    private var spritesTotal = 0L
    private var imagesTotal = 0L
    private var drawCallsTotal = 0L
    private var frameBytesTotal = 0L
    private var atlasUploadsTotal = 0L
    private var dirtyRowsTotal = 0L
    private var backgroundBuildTotal = 0f
    private var foregroundBuildTotal = 0f

    /**
     * The frames by the engine's own dirty state.
     *
     * The engine says whether the whole screen changed (`full`), only some of
     * it changed (`partial`) or nothing changed at all (`false`). The renderer
     * draws all of it either way today, so the ratio is the headroom the
     * dirty-row work has to claim.
     */
    private var cleanFrames = 0
    private var partialFrames = 0
    private var fullFrames = 0

    private var lastFrameNanos = 0L

    /**
     * A bounded ring of the last [SAMPLE_COUNT] frames, for percentiles.
     *
     * The counters above are deliberately monotone: the frozen report must not
     * change under the reader's feet. A percentile cannot be computed from a
     * counter, so this ring lives beside them and is never used for the headline
     * numbers. It is read only when the diagnostics panel asks for detail.
     */
    private val workSamples = FloatArray(SAMPLE_COUNT)
    private val intervalSamples = FloatArray(SAMPLE_COUNT)
    private var sampleCursor = 0
    private var sampleCount = 0

    /** While frozen, [record] is ignored: the numbers stop moving. */
    var isFrozen = false
        private set

    /**
     * Record one drawn frame.
     *
     * @param startNanos when the frame's work started.
     * @param pumpEndNanos when the snapshot was in hand.
     * @param buildEndNanos when the vertices were built.
     * @param uploadEndNanos when the GPU had everything.
     * @param drawEndNanos when the frame was submitted.
     * @param cells the cells the renderer walked.
     * @param sprites the foreground instances drawn.
     * @param images the pictures drawn.
     * @param drawCalls the GL draw calls issued.
     * @param frameBytes the size of the frame that came across the boundary.
     * @param atlasUploads the atlas regions uploaded.
     * @param dirty the engine's dirty state for this frame.
     */
    fun record(
        startNanos: Long,
        pumpEndNanos: Long,
        buildEndNanos: Long,
        uploadEndNanos: Long,
        drawEndNanos: Long,
        cells: Int,
        sprites: Int,
        images: Int,
        drawCalls: Int,
        frameBytes: Int,
        atlasUploads: Int,
        dirty: Int,
        dirtyRows: Int,
        backgroundBuildNanos: Long,
        foregroundBuildNanos: Long,
    ) {
        if (isFrozen) {
            return
        }

        val pumpMillis = millis(startNanos, pumpEndNanos)
        val buildMillis = millis(pumpEndNanos, buildEndNanos)
        val uploadMillis = millis(buildEndNanos, uploadEndNanos)
        val drawMillis = millis(uploadEndNanos, drawEndNanos)
        val total = pumpMillis + buildMillis + uploadMillis + drawMillis

        // The percentile ring. Absolute puts: no cursor arithmetic can leave a
        // stale value in a slot the next reader would mistake for a sample.
        workSamples[sampleCursor] = total
        sampleCursor = (sampleCursor + 1) % SAMPLE_COUNT
        sampleCount = minOf(sampleCount + 1, SAMPLE_COUNT)

        pumpTotal += pumpMillis
        buildTotal += buildMillis
        uploadTotal += uploadMillis
        drawTotal += drawMillis
        cellsTotal += cells
        spritesTotal += sprites
        imagesTotal += images
        drawCallsTotal += drawCalls
        frameBytesTotal += frameBytes
        atlasUploadsTotal += atlasUploads
        dirtyRowsTotal += dirtyRows
        backgroundBuildTotal += backgroundBuildNanos / NANOS_PER_MILLI
        foregroundBuildTotal += foregroundBuildNanos / NANOS_PER_MILLI
        frames += 1
        maxWork = maxOf(maxWork, total)
        work[bucket(total)] += 1

        when (dirty) {
            DIRTY_NONE -> cleanFrames += 1
            DIRTY_PARTIAL -> partialFrames += 1
            else -> fullFrames += 1
        }

        // Jank is *work*, not the gap between frames: the gap also contains idle
        // time, keyboard animations and everything else that has nothing to do
        // with rendering. A frame that took longer than two refreshes is a frame
        // the user waited for.
        if (total > JANK_MILLIS) {
            longs += 1
        }

        // The gap is still recorded, but only as information.
        if (lastFrameNanos != 0L) {
            val gap = millis(lastFrameNanos, startNanos)
            maxInterval = maxOf(maxInterval, gap)
            interval[bucket(gap)] += 1
            intervalSamples[(sampleCursor - 1 + SAMPLE_COUNT) % SAMPLE_COUNT] = gap
        }
        lastFrameNanos = startNanos
    }

    /** Freeze or resume; returns the new state so the UI can show it. */
    fun toggleFreeze(): Boolean {
        isFrozen = !isFrozen
        return isFrozen
    }

    fun reset() {
        work.fill(0)
        interval.fill(0)
        frames = 0
        longs = 0
        maxWork = 0f
        maxInterval = 0f
        pumpTotal = 0f
        buildTotal = 0f
        uploadTotal = 0f
        drawTotal = 0f
        cellsTotal = 0
        spritesTotal = 0
        imagesTotal = 0
        drawCallsTotal = 0
        frameBytesTotal = 0
        atlasUploadsTotal = 0
        dirtyRowsTotal = 0
        backgroundBuildTotal = 0f
        foregroundBuildTotal = 0f
        cleanFrames = 0
        partialFrames = 0
        fullFrames = 0
        lastFrameNanos = 0L
        workSamples.fill(0f)
        intervalSamples.fill(0f)
        sampleCursor = 0
        sampleCount = 0
    }

    /** Frames whose work exceeded 8 ms — the acceptance criterion's numerator. */
    fun slowFrames(): Int = work[3] + work[4] + work[5]

    /** Share of drawn frames that exceeded 8 ms, in percent. */
    fun slowShare(): Float = if (frames == 0) 0f else slowFrames() * 100f / frames

    fun averagePump(): Float = average(pumpTotal)

    fun averageBuild(): Float = average(buildTotal)

    fun averageUpload(): Float = average(uploadTotal)

    fun averageDraw(): Float = average(drawTotal)

    fun frameCount(): Int = frames

    fun maxFrame(): Float = maxWork

    fun janky(): Int = longs

    /** The cells the renderer walked, per frame. */
    fun averageCells(): Float = if (frames == 0) 0f else cellsTotal.toFloat() / frames

    /** The foreground instances drawn, per frame. */
    fun averageSprites(): Float = if (frames == 0) 0f else spritesTotal.toFloat() / frames

    /** The pictures drawn, per frame. */
    fun averageImages(): Float = if (frames == 0) 0f else imagesTotal.toFloat() / frames

    /** The GL draw calls issued, per frame. */
    fun averageDrawCalls(): Float = if (frames == 0) 0f else drawCallsTotal.toFloat() / frames

    /** The bytes that crossed the boundary, per frame. */
    fun averageFrameBytes(): Float =
        if (frames == 0) 0f else frameBytesTotal.toFloat() / frames

    /** The atlas regions uploaded, per frame. */
    fun averageAtlasUploads(): Float =
        if (frames == 0) 0f else atlasUploadsTotal.toFloat() / frames

    /** The rows rebuilt, per frame. */
    fun averageDirtyRows(): Float =
        if (frames == 0) 0f else dirtyRowsTotal.toFloat() / frames

    /** The background vertex building, per frame, in milliseconds. */
    fun averageBackgroundBuild(): Float =
        if (frames == 0) 0f else backgroundBuildTotal / frames

    /** The foreground vertex building, per frame, in milliseconds. */
    fun averageForegroundBuild(): Float =
        if (frames == 0) 0f else foregroundBuildTotal / frames

    /** The frames the engine said were clean. */
    fun cleanFrameCount(): Int = cleanFrames

    /** The frames where only some of the screen changed. */
    fun partialFrameCount(): Int = partialFrames

    /** The frames where the whole screen changed. */
    fun fullFrameCount(): Int = fullFrames

    /**
     * The share of frames where only part of the screen changed.
     *
     * This is the headroom the dirty-row work has to claim: a frame that only
     * touched two rows should cost two rows, not forty.
     */
    fun partialShare(): Float =
        if (frames == 0) 0f else partialFrames * 100f / frames

    /**
     * The distribution behind the headline numbers.
     *
     * The acceptance criteria talk about p50 and p99; a bucket count cannot
     * answer that. This is read only when someone opens the diagnostics panel,
     * so sorting a few hundred floats is not on the render loop's path.
     */
    fun detail(): String {
        if (sampleCount == 0) return "no frame samples yet"
        val work = sortedSamples(workSamples)
        val gap = sortedSamples(intervalSamples)
        return String.format(
            Locale.ROOT,
            "samples %d · work p50 %.2f p95 %.2f p99 %.2f · gap p50 %.2f p95 %.2f p99 %.2f ms",
            sampleCount,
            work.percentile(50),
            work.percentile(95),
            work.percentile(99),
            gap.percentile(50),
            gap.percentile(95),
            gap.percentile(99),
        )
    }

    private fun sortedSamples(source: FloatArray): FloatArray =
        source.copyOfRange(0, sampleCount).apply { sort() }

    private fun FloatArray.percentile(percent: Int): Float {
        if (isEmpty()) return 0f
        // Nearest rank: an index that always names a sample we actually took,
        // rather than an interpolation between two of them.
        val rank = (percent * size / 100f).toInt().coerceIn(0, size - 1)
        return this[rank]
    }

    /** The status line: compact, and nothing in it moves backwards. */
    fun summary(): String {
        if (frames == 0) {
            return "no frame drawn yet"
        }

        // Locale.ROOT everywhere in this class: a device set to a comma-decimal
        // locale (Turkish, German…) would otherwise emit `12,33`, which the
        // reviewer cannot parse and which is not what the log lines look like on
        // any other device.
        return String.format(
            Locale.ROOT,
            "%s%d frames · %.0f cells/f · %.0f sprites/f · %.1f draw calls/f · " +
                ">8ms %d (%.1f%%) · max %.0f · pump %.1f build %.1f up %.1f draw %.1f · jank %d",
            if (isFrozen) "FROZEN " else "",
            frames,
            averageCells(),
            averageSprites(),
            averageDrawCalls(),
            slowFrames(),
            slowShare(),
            maxFrame(),
            averagePump(),
            averageBuild(),
            averageBackgroundBuild(),
            averageForegroundBuild(),
            averageUpload(),
            averageDraw(),
            janky(),
        )
    }

    /**
     * The same numbers as one logcat line, which is what gets sent: a line that
     * cannot change under the reader's feet.
     */
    fun logLine(): String =
        String.format(
            Locale.ROOT,
            "frames=%d cells=%.0f sprites=%.0f images=%.1f calls=%.1f bytes=%.0f " +
                "atlas=%.2f rows=%.0f clean=%d partial=%d full=%d slow8=%d (%.2f%%) max=%.1f " +
                "pump=%.2f build=%.2f bg=%.2f fg=%.2f upload=%.2f draw=%.2f jank=%d interval_max=%.1f",
            frames,
            averageCells(),
            averageSprites(),
            averageImages(),
            averageDrawCalls(),
            averageFrameBytes(),
            averageAtlasUploads(),
            averageDirtyRows(),
            cleanFrames,
            partialFrames,
            fullFrames,
            slowFrames(),
            slowShare(),
            maxFrame(),
            averagePump(),
            averageBuild(),
            averageUpload(),
            averageDraw(),
            longs,
            maxInterval,
        )

    private fun average(total: Float): Float = if (frames == 0) 0f else total / frames

    /**
     * Foreground records per frame on average: build and upload cost scale with
     * it, so it is the number that says whether the renderer is doing less.
     */
    private fun recordsPerFrame(): Float = if (frames == 0) 0f else spritesTotal.toFloat() / frames

    private fun bucket(millis: Float): Int = when {
        millis < 2f -> 0
        millis < 4f -> 1
        millis < 8f -> 2
        millis < 16f -> 3
        millis < 33f -> 4
        else -> 5
    }

    private fun millis(from: Long, to: Long): Float = (to - from) / NANOS_PER_MILLI

    private companion object {
        const val BUCKETS = 6
        /** How many samples the percentile ring keeps. */
        private const val SAMPLE_COUNT = 600
        const val NANOS_PER_MILLI = 1_000_000f

        /**
         * Two refreshes at 60 Hz: a gap longer than this while the engine was
         * sending output is what a user would call a stall.
         */
        const val JANK_MILLIS = 33.4f

        /** The engine's dirty states, as the frame header carries them. */
        const val DIRTY_NONE = 0
        const val DIRTY_PARTIAL = 2
    }
}
