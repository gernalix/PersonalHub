package com.example.multitimetracker

import com.example.multitimetracker.capsules.alerts.core.buildTimeFenceAlarmReconciliation
import com.example.multitimetracker.capsules.alerts.core.scheduledTimeFenceFireAtMs
import com.example.multitimetracker.capsules.alerts.core.timeFenceRuleNotificationId
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.TimeFenceDelivery
import com.example.multitimetracker.model.TimeFenceMatchMode
import com.example.multitimetracker.model.TimeFenceRule
import com.example.multitimetracker.model.TimeFenceScope
import com.example.multitimetracker.model.TimeFenceTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeFenceAlarmReconciliationTest {
    @Test
    fun activePersistedRuleRestoresOneScheduleForRunningMatchingSession() {
        val rule = notificationRule(timerMinutes = 5)
        val session = runningSession(startMs = 1_000L)

        val reconciliation = buildTimeFenceAlarmReconciliation(
            beforeRules = listOf(rule),
            afterRules = listOf(rule),
            sessions = listOf(session),
            tags = listOf(tag()),
            nowMs = 2_000L,
        )

        assertEquals(1, reconciliation.scheduleTimers.size)
        assertEquals(1, reconciliation.cancelTimerKeys.size)
        assertEquals(scheduledTimeFenceFireAtMs(1_000L, 5), reconciliation.scheduleTimers.single().fireAtMs)
    }

    @Test
    fun deletedOrDisabledRuleCancelsAlarmAndNotificationWithoutRescheduling() {
        val before = notificationRule(timerMinutes = 5)
        val after = before.copy(isEnabled = false)

        val reconciliation = buildTimeFenceAlarmReconciliation(
            beforeRules = listOf(before),
            afterRules = listOf(after),
            sessions = listOf(runningSession()),
            tags = listOf(tag()),
            nowMs = 2_000L,
        )

        assertTrue(reconciliation.scheduleTimers.isEmpty())
        assertEquals(1, reconciliation.cancelTimerKeys.size)
        assertEquals(setOf(timeFenceRuleNotificationId(before.id)), reconciliation.cancelNotificationIds)
    }

    @Test
    fun alreadyFiredOneTimeRuleDoesNotRestoreDuplicateSchedule() {
        val session = runningSession(startMs = 1_000L)
        val rule = notificationRule(
            timerMinutes = 5,
            scope = TimeFenceScope.ONE_TIME,
            lastFiredAtMs = scheduledTimeFenceFireAtMs(session.startMs, 5),
        )

        val reconciliation = buildTimeFenceAlarmReconciliation(
            beforeRules = listOf(rule),
            afterRules = listOf(rule),
            sessions = listOf(session),
            tags = listOf(tag()),
            nowMs = 2_000L,
        )

        assertTrue(reconciliation.scheduleTimers.isEmpty())
        assertEquals(1, reconciliation.cancelTimerKeys.size)
    }

    private fun notificationRule(
        timerMinutes: Int,
        scope: TimeFenceScope = TimeFenceScope.ALWAYS,
        lastFiredAtMs: Long? = null,
    ): TimeFenceRule = TimeFenceRule(
        id = 100L,
        message = "check timer",
        trigger = TimeFenceTrigger.ON_START,
        delivery = TimeFenceDelivery.NOTIFICATION,
        scope = scope,
        matchMode = TimeFenceMatchMode.AND,
        tagIds = setOf(1L),
        timerMinutes = timerMinutes,
        lastFiredAtMs = lastFiredAtMs,
    )

    private fun runningSession(startMs: Long = 1_000L): SessionUi = SessionUi(
        id = 200L,
        title = "Focus",
        startMs = startMs,
        endMs = null,
        tagIds = setOf(1L),
    )

    private fun tag(): Tag = Tag(
        id = 1L,
        name = "Work",
        activeChildrenCount = 0,
        totalMs = 0L,
        lastStartedAtMs = null,
    )
}
