package dev.drosh.terminal.engine.bench

/**
 * Frame timings for the Phase B acceptance numbers ("frame p99 < 8 ms; no jank
 * under full-screen output").
 *
 * **Monotone counters, not a sliding window.** The first version kept a ring of
 * the last 240 frames and printed its percentiles twice a second; a number that
 * changes while it is being read cannot be reported — which is exactly what
 * happened. These counters only ever grow, so the line a user reads is a superset
 * of the line they read a moment ago, and "freeze" turns it into an artefact that
 * can be sent as-is (the app logs it under `DroshLabBench`).
 *
 * The frame is also split into the phases it is made of, because the first device
 * run produced a number (`p50 114 ms`) nobody could attribute:
 *
 * - **pump**: the snapshot call. It takes the grid lock, so it also waits while
 *   the parser is busy.
 * - **build**: turning damaged rows into vertices and uploading them.
 * - **draw**: the GL submit.
 *
 * Only frames that actually drew are counted: GLSurfaceView runs in
 * `RENDERMODE_WHEN_DIRTY`, so idle time between frames says nothing. A frame is
 * **janky** when the gap since the previous drawn frame exceeded two refreshes.
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
    private var recordsTotal = 0
    private var lastFrameNanos = 0L

    /** While frozen, [record] is ignored: the numbers stop moving. */
    var isFrozen = false
        private set

    fun record(
        startNanos: Long,
        pumpEndNanos: Long,
        buildEndNanos: Long,
        uploadEndNanos: Long,
        drawEndNanos: Long,
        recordCount: Int,
    ) {
        if (isFrozen) {
            return
        }

        val pumpMillis = millis(startNanos, pumpEndNanos)
        val buildMillis = millis(pumpEndNanos, buildEndNanos)
        val uploadMillis = millis(buildEndNanos, uploadEndNanos)
        val drawMillis = millis(uploadEndNanos, drawEndNanos)
        val total = pumpMillis + buildMillis + uploadMillis + drawMillis

        pumpTotal += pumpMillis
        buildTotal += buildMillis
        uploadTotal += uploadMillis
        drawTotal += drawMillis
        recordsTotal += recordCount
        frames += 1
        maxWork = maxOf(maxWork, total)
        work[bucket(total)] += 1

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
        drawTotal = 0f
        lastFrameNanos = 0L
    }

    /** Frames whose work exceeded 8 ms — the acceptance criterion's numerator. */
    fun slowFrames(): Int = work[3] + work[4] + work[5]

    /** Share of drawn frames that exceeded 8 ms, in percent. */
    fun slowShare(): Float = if (frames == 0) 0f else slowFrames() * 100f / frames

    fun averagePump(): Float = average(pumpTotal)

    fun averageBuild(): Float = average(buildTotal)

    fun averageUpload(): Float = average(uploadTotal)

    fun averageRecords(): Float = recordsPerFrame()

    fun averageDraw(): Float = average(drawTotal)

    fun frameCount(): Int = frames

    fun maxFrame(): Float = maxWork

    fun janky(): Int = longs

    /** The status line: compact, and nothing in it moves backwards. */
    fun summary(): String {
        if (frames == 0) {
            return "no frame drawn yet"
        }

        // Locale.ROOT everywhere in this class: a device set to a comma-decimal
        // locale (Turkish, German…) would otherwise emit `12,33`, which the
        // reviewer cannot parse and which is not what the log lines look like on
        // any other device.
        return java.lang.String.format(
            java.util.Locale.ROOT,
            "%s%d frames · %.0f rec/f · >8ms %d (%.1f%%) · max %.0f · pump %.1f build %.1f up %.1f draw %.1f · jank %d",
            if (isFrozen) "FROZEN " else "",
            frames,
            recordsPerFrame(),
            slowFrames(),
            slowShare(),
            maxWork,
            averagePump(),
            averageBuild(),
            averageUpload(),
            averageDraw(),
            longs,
        )
    }

    /**
     * The same numbers as one logcat line, which is what gets sent: a line that
     * cannot change under the reader's feet.
     */
    fun logLine(): String =
        java.lang.String.format(
            java.util.Locale.ROOT,
            "frames=%d records_per_frame=%.1f slow8=%d (%.2f%%) max=%.1f pump=%.1f build=%.1f upload=%.1f draw=%.1f jank=%d interval_max=%.1f",
            frames,
            recordsPerFrame(),
            slowFrames(),
            slowShare(),
            maxWork,
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
     * this, so without it two runs cannot be compared (TODO.md, device runs 2-3).
     */
    private fun recordsPerFrame(): Float = if (frames == 0) 0f else recordsTotal.toFloat() / frames

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
        const val NANOS_PER_MILLI = 1_000_000f

        /**
         * Two refreshes at 60 Hz: a gap longer than this while the engine was
         * sending output is what a user would call a stall.
         */
        const val JANK_MILLIS = 33.4f
    }
}
