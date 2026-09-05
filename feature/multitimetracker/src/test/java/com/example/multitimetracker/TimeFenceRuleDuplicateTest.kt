package com.example.multitimetracker

import com.example.multitimetracker.capsules.alerts.controller.hasDuplicateTimeFenceRuleForTriggerAndTags
import com.example.multitimetracker.model.TimeFenceDelivery
import com.example.multitimetracker.model.TimeFenceMatchMode
import com.example.multitimetracker.model.TimeFenceRule
import com.example.multitimetracker.model.TimeFenceScope
import com.example.multitimetracker.model.TimeFenceTrigger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeFenceRuleDuplicateTest {
    @Test
    fun activeRuleBlocksAnotherAlertForSameTriggerAndSameTagSet() {
        val existing = rule(id = 1L, trigger = TimeFenceTrigger.ON_START, tagIds = setOf(14L))

        val duplicate = hasDuplicateTimeFenceRuleForTriggerAndTags(
            rules = listOf(existing),
            candidateRuleId = null,
            trigger = TimeFenceTrigger.ON_START,
            tagIds = setOf(14L),
        )

        assertTrue(duplicate)
    }

    @Test
    fun sameTagWithDifferentTriggerOrDifferentTagSetIsAllowed() {
        val existing = rule(id = 1L, trigger = TimeFenceTrigger.ON_START, tagIds = setOf(1L, 14L))

        val differentTrigger = hasDuplicateTimeFenceRuleForTriggerAndTags(
            rules = listOf(existing),
            candidateRuleId = null,
            trigger = TimeFenceTrigger.ON_STOP,
            tagIds = setOf(1L, 14L),
        )
        val overlappingButDifferentSet = hasDuplicateTimeFenceRuleForTriggerAndTags(
            rules = listOf(existing),
            candidateRuleId = null,
            trigger = TimeFenceTrigger.ON_START,
            tagIds = setOf(14L, 22L),
        )

        assertFalse(differentTrigger)
        assertFalse(overlappingButDifferentSet)
    }

    @Test
    fun updatingSameRuleOrUsingDeletedRuleDoesNotCountAsDuplicate() {
        val existing = rule(id = 1L, tagIds = setOf(14L))
        val deleted = rule(id = 2L, tagIds = setOf(22L), isDeleted = true)

        assertFalse(
            hasDuplicateTimeFenceRuleForTriggerAndTags(
                rules = listOf(existing, deleted),
                candidateRuleId = 1L,
                trigger = TimeFenceTrigger.ON_STOP,
                tagIds = setOf(14L),
            )
        )
        assertFalse(
            hasDuplicateTimeFenceRuleForTriggerAndTags(
                rules = listOf(deleted),
                candidateRuleId = null,
                trigger = TimeFenceTrigger.ON_STOP,
                tagIds = setOf(22L),
            )
        )
    }

    private fun rule(
        id: Long,
        trigger: TimeFenceTrigger = TimeFenceTrigger.ON_STOP,
        tagIds: Set<Long>,
        isDeleted: Boolean = false,
    ): TimeFenceRule = TimeFenceRule(
        id = id,
        message = "Timer alert",
        trigger = trigger,
        delivery = TimeFenceDelivery.NOTIFICATION,
        scope = TimeFenceScope.ALWAYS,
        matchMode = TimeFenceMatchMode.AND,
        tagIds = tagIds,
        timerMinutes = 1,
        isDeleted = isDeleted,
    )
}
