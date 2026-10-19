package dev.drosh.domain.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockTest {

    private fun baseBlock(
        state: BlockState = BlockState.Success(0),
        startedAtMs: Long = 1000L,
        completedAtMs: Long? = 2000L,
        startRxBytes: Long = 100L,
        startTxBytes: Long = 50L,
        currentRxBytes: Long = 100L,
        currentTxBytes: Long = 50L,
    ) = Block(
        id = "test-id",
        prompt = "muhofy@drosh:~$",
        command = "ls",
        outputLines = listOf("file1", "file2"),
        state = state,
        startedAtMs = startedAtMs,
        completedAtMs = completedAtMs,
        startRxBytes = startRxBytes,
        startTxBytes = startTxBytes,
        currentRxBytes = currentRxBytes,
        currentTxBytes = currentTxBytes,
    )

    @Test
    fun `elapsedMs returns duration when completed`() {
        val block = baseBlock(completedAtMs = 5000L)
        assertEquals(4000L, block.elapsedMs(now = 9999L))
    }

    @Test
    fun `elapsedMs uses current time when still running`() {
        val block = baseBlock(
            state = BlockState.Running,
            startedAtMs = 1000L,
            completedAtMs = null,
        )
        assertEquals(2000L, block.elapsedMs(now = 3000L))
    }

    @Test
    fun `networkDelta is positive when current exceeds start`() {
        val block = baseBlock(
            startRxBytes = 100L,
            currentRxBytes = 250L,
            startTxBytes = 50L,
            currentTxBytes = 80L,
        )
        val delta = block.networkDelta
        assertEquals(150L, delta.rxBytes)
        assertEquals(30L, delta.txBytes)
        assertTrue(delta.hasTraffic)
    }

    @Test
    fun `networkDelta is zero and not hasTraffic when no traffic`() {
        val block = baseBlock()
        val delta = block.networkDelta
        assertEquals(0L, delta.rxBytes)
        assertEquals(0L, delta.txBytes)
        assertFalse(delta.hasTraffic)
    }

    @Test
    fun `networkDelta clamps to zero on counter rollover`() {
        val block = baseBlock(
            startRxBytes = 500L,
            currentRxBytes = 100L,
        )
        assertEquals(0L, block.networkDelta.rxBytes)
    }

    @Test
    fun `exitCode returns null for running state`() {
        val block = baseBlock(state = BlockState.Running, completedAtMs = null)
        assertNull(block.exitCode)
    }

    @Test
    fun `exitCode returns the exit code from Success`() {
        val block = baseBlock(state = BlockState.Success(0))
        assertEquals(0, block.exitCode)
    }

    @Test
    fun `exitCode returns the exit code from Error`() {
        val block = baseBlock(state = BlockState.Error(127))
        assertEquals(127, block.exitCode)
    }

    @Test
    fun `exitCode returns null for Cancelled`() {
        val block = baseBlock(state = BlockState.Cancelled)
        assertNull(block.exitCode)
    }
}
