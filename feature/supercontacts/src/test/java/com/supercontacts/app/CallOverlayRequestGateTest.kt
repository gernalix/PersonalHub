package com.supercontacts.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallOverlayRequestGateTest {
    @Test
    fun dismissInvalidatesPendingLookup() {
        val gate = CallOverlayRequestGate()
        val first = gate.begin()
        gate.invalidate()
        assertFalse(gate.isCurrent(first))
    }

    @Test
    fun newerCallInvalidatesOlderLookup() {
        val gate = CallOverlayRequestGate()
        val first = gate.begin()
        val second = gate.begin()
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }
}
