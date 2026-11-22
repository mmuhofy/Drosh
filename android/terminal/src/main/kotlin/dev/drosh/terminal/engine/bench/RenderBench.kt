package dev.drosh.terminal.engine.bench

/**
 * The render loop's numbers, in one place.
 *
 * The loop draws on its own thread and the diagnostics screen reads them from
 * another one, so the counters live here rather than in the loop: a number that
 * belongs to a thread nobody else can reach is a number nobody can read.
 *
 * The counters are read while the loop writes them. That is safe because every
 * one of them is a plain `Long` or `Int` that only ever grows: a reader that
 * catches a torn value reads a slightly stale number, never a wrong one. The
 * alternative — a lock on the hot path — would cost more than the measurement
 * is worth.
 */
object RenderBench {

    /** The counters. */
    private val stats = FrameStats()

    /**
     * The frames after which the line is logged.
     *
     * Often enough to see a change, rarely enough that the log is not the
     * thing being measured. At sixty frames a second this is about two
     * seconds.
     */
    private const val LOG_EVERY_FRAMES = 120

    /** The frames counted since the last log line. */
    @Volatile
    private var sinceLog = 0

    /**
     * What the renderer is made of, as of the last frame.
     *
     * The atlas's fill is the number the atlas work is measured against: an
     * atlas that is nearly full is an atlas that has to be reset, and a reset
     * is a re-rasterisation of every glyph on the screen.
     */
    @Volatile
    private var facts = "no frame yet"

    /** The values behind [facts], so a change is noticed without formatting. */
    @Volatile
    private var atlasFill = -1
    @Volatile
    private var fontPx = -1f
    @Volatile
    private var cellWidth = -1f
    @Volatile
    private var cellHeight = -1f

    /**
     * Publish what the renderer is made of.
     *
     * Written from the render thread and read from anywhere. The values only
     * change when the user changes something, so the line is formatted only
     * when they do: a formatter call on every frame would be a cost the
     * measurement itself pays for.
     */
    fun setFacts(atlasFillPercent: Int, fontPx: Float, cellWidth: Float, cellHeight: Float) {
        if (atlasFillPercent == this.atlasFill &&
            fontPx == this.fontPx &&
            cellWidth == this.cellWidth &&
            cellHeight == this.cellHeight
        ) {
            return
        }
        this.atlasFill = atlasFillPercent
        this.fontPx = fontPx
        this.cellWidth = cellWidth
        this.cellHeight = cellHeight
        facts = String.format(
            java.util.Locale.ROOT,
            "atlas %d%% full · font %.1fpx · cell %.1f×%.1f",
            atlasFillPercent,
            fontPx,
            cellWidth,
            cellHeight,
        )
    }

    /** What the renderer is made of. */
    fun rendererFacts(): String = facts

    /**
     * Record a drawn frame.
     *
     * @return the log line when it is time to write one, null otherwise.
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
    ): String? {
        stats.record(
            startNanos = startNanos,
            pumpEndNanos = pumpEndNanos,
            buildEndNanos = buildEndNanos,
            uploadEndNanos = uploadEndNanos,
            drawEndNanos = drawEndNanos,
            cells = cells,
            sprites = sprites,
            images = images,
            drawCalls = drawCalls,
            frameBytes = frameBytes,
            atlasUploads = atlasUploads,
            dirty = dirty,
            dirtyRows = dirtyRows,
        )
        sinceLog += 1
        if (sinceLog < LOG_EVERY_FRAMES) return null
        sinceLog = 0
        return stats.logLine()
    }

    /** Frames drawn so far, for sampling FPS without scraping [summary]. */
    fun frameCount(): Int = stats.frameCount()

    /**
     * Mean render work per drawn frame, in milliseconds.
     *
     * Work, not the interval between frames: the interval contains idle time and
     * belongs to the display, while this is the part the renderer actually costs.
     */
    fun averageFrameWorkMs(): Float =
        stats.averagePump() + stats.averageBuild() + stats.averageUpload() + stats.averageDraw()

    /** The status line, for the diagnostics screen. */
    fun summary(): String = stats.summary()

    /** The distribution behind the status line, for the expanded panel. */
    fun detail(): String = stats.detail()

    /** The numbers as one line, for the log. */
    fun logLine(): String = stats.logLine()

    /** A multi-line report for diagnostics and benchmark files. */
    fun report(): String = buildString {
        appendLine("Drosh render benchmark")
        appendLine(rendererFacts())
        appendLine(stats.summary())
        appendLine(stats.detail())
        appendLine("rows/frame=${stats.averageDirtyRows()}")
    }

    /** Whether the counters are frozen. */
    fun isFrozen(): Boolean = stats.isFrozen

    /** Freeze or resume the counters; returns the new state. */
    fun toggleFreeze(): Boolean = stats.toggleFreeze()

    /** Start the numbers again from zero. */
    fun reset() {
        stats.reset()
        sinceLog = 0
    }
}
