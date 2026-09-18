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
    fun emptyTagQueryNeverMatchesUnlessLegacyTimerExplicitlyOptsIn() {
        assertFalse(AlertMatching.tags(AlertMatchMode.ALL, emptySet(), setOf("1")))
        assertFalse(AlertMatching.tags(AlertMatchMode.ANY, emptySet(), setOf("1")))
        assertTrue(AlertMatching.tags(AlertMatchMode.ANY, emptySet(), setOf("1"), emptyAnyMatches = true))
    }

    @Test
    fun placeBothMatchesCheckInAndCheckOutOnly() {
        assertTrue(AlertMatching.placeTrigger(AlertTrigger.PLACE_BOTH, AlertTrigger.PLACE_CHECK_IN))
        assertTrue(AlertMatching.placeTrigger(AlertTrigger.PLACE_BOTH, AlertTrigger.PLACE_CHECK_OUT))
        assertFalse(AlertMatching.placeTrigger(AlertTrigger.PLACE_BOTH, AlertTrigger.TIMER_START))
    }

    @Test
    fun sameNumericTagIdNeverCrossesTimerAndPlacesDomains() {
        val rule = AlertRuleSpec(
            domain = AlertDomain.PLACE,
            trigger = AlertTrigger.PLACE_CHECK_IN,
            targetKind = AlertTargetKind.TAGS,
            requiredTagIds = setOf("7"),
        )
        val timerEvent = AlertEventSpec(
            domain = AlertDomain.TIMER,
            trigger = AlertTrigger.TIMER_START,
            tagIds = setOf("7"),
        )
        assertFalse(AlertMatching.ruleMatches(rule, timerEvent, nowMs = 1_000L))
    }

    @Test
    fun entityTargetRequiresTheExactPlaceAndCooldownIsEnforced() {
        val rule = AlertRuleSpec(
            domain = AlertDomain.PLACE,
            trigger = AlertTrigger.PLACE_CHECK_OUT,
            targetKind = AlertTargetKind.ENTITY,
            entityId = "carlo",
            cooldownMs = 60_000L,
            lastFiredAtMs = 100_000L,
        )
        assertFalse(
            AlertMatching.ruleMatches(
                rule,
                AlertEventSpec(AlertDomain.PLACE, AlertTrigger.PLACE_CHECK_OUT, entityId = "other"),
                nowMs = 200_000L,
            )
        )
        assertFalse(
            AlertMatching.ruleMatches(
                rule,
                AlertEventSpec(AlertDomain.PLACE, AlertTrigger.PLACE_CHECK_OUT, entityId = "carlo"),
                nowMs = 120_000L,
            )
        )
        assertTrue(
            AlertMatching.ruleMatches(
                rule,
                AlertEventSpec(AlertDomain.PLACE, AlertTrigger.PLACE_CHECK_OUT, entityId = "carlo"),
                nowMs = 170_000L,
            )
        )
    }
}
