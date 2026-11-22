package dev.drosh.terminal.engine.bench

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The frame stats' arithmetic, without a render loop.
 *
 * The dirty-row average is the number the renderer's incremental work is
 * measured against: a frame that rebuilt two rows should report two, and a
 * number that drifts from that is a counter that lost its meaning.
 */
class FrameStatsTest {

    @Test
    fun `dirty rows are averaged`() {
        val stats = FrameStats()
        stats.record(
            startNanos = 0L,
            pumpEndNanos = 1_000_000L,
            buildEndNanos = 2_000_000L,
            uploadEndNanos = 3_000_000L,
            drawEndNanos = 4_000_000L,
            cells = 10,
            sprites = 5,
            images = 0,
            drawCalls = 1,
            frameBytes = 100,
            atlasUploads = 0,
            dirty = 2,
            dirtyRows = 4,
            backgroundBuildNanos = 1_000_000L,
            foregroundBuildNanos = 2_000_000L,
        )
        stats.record(
            startNanos = 4_000_000L,
            pumpEndNanos = 5_000_000L,
            buildEndNanos = 6_000_000L,
            uploadEndNanos = 7_000_000L,
            drawEndNanos = 8_000_000L,
            cells = 10,
            sprites = 5,
            images = 0,
            drawCalls = 1,
            frameBytes = 100,
            atlasUploads = 0,
            dirty = 2,
            dirtyRows = 6,
            backgroundBuildNanos = 1_500_000L,
            foregroundBuildNanos = 2_500_000L,
        )
        assertEquals(5f, stats.averageDirtyRows(), 0.001f)
    }
}