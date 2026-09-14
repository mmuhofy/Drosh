package com.iris.irisshell.domain.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkDeltaTest {

    @Test
    fun `hasTraffic is true when rx is positive`() {
        assertTrue(NetworkDelta(rxBytes = 1L, txBytes = 0L).hasTraffic)
    }

    @Test
    fun `hasTraffic is true when tx is positive`() {
        assertTrue(NetworkDelta(rxBytes = 0L, txBytes = 1L).hasTraffic)
    }

    @Test
    fun `hasTraffic is false when both are zero`() {
        assertFalse(NetworkDelta(rxBytes = 0L, txBytes = 0L).hasTraffic)
    }
}
