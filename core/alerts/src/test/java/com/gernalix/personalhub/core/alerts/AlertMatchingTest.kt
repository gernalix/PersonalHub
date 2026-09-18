package com.gernalix.personalhub.core.alerts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertMatchingTest {
    @Test
    fun placeTagsUseAllAndAnySemantics() {
        val actual = setOf("1", "2", "3")
        assertTrue(AlertMatching.tags(AlertMatchMode.ALL, setOf("1", "2"), actual))
        assertFalse(AlertMatching.tags(AlertMatchMode.ALL, setOf("1", "9"), actual))
        assertTrue(AlertMatching.tags(AlertMatchMode.ANY, setOf("9", "2"), actual))
        assertFalse(AlertMatching.tags(AlertMatchMode.ANY, setOf("8", "9"), actual))
    }

    @Test
    fun emptyTagQueryNeverMatches() {
        assertFalse(AlertMatching.tags(AlertMatchMode.ALL, emptySet(), setOf("1")))
        assertFalse(AlertMatching.tags(AlertMatchMode.ANY, emptySet(), setOf("1")))
    }

    @Test
    fun placeBothMatchesCheckInAndCheckOutOnly() {
        assertTrue(AlertMatching.placeTrigger(AlertTrigger.PLACE_BOTH, AlertTrigger.PLACE_CHECK_IN))
        assertTrue(AlertMatching.placeTrigger(AlertTrigger.PLACE_BOTH, AlertTrigger.PLACE_CHECK_OUT))
        assertFalse(AlertMatching.placeTrigger(AlertTrigger.PLACE_BOTH, AlertTrigger.TIMER_START))
    }
}
