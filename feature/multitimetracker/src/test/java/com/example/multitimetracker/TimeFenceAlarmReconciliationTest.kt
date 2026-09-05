package com.example.multitimetracker

import com.example.multitimetracker.capsules.alerts.controller.AlertsCapsuleViewModel
import com.example.multitimetracker.capsules.alerts.core.buildLegacyTimerAlertCleanup
import com.example.multitimetracker.capsules.alerts.core.buildTimedSessionRestorePlan
import com.example.multitimetracker.capsules.alerts.public.TimeFenceEvent
import com.example.multitimetracker.capsules.alerts.state.AlertsHostState
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.TimedTagNotificationType
import com.example.multitimetracker.model.TimeFenceDelivery
import com.example.multitimetracker.model.TimeFenceMatchMode
import com.example.multitimetracker.model.TimeFenceRule
import com.example.multitimetracker.model.TimeFenceScope
import com.example.multitimetracker.model.TimeFenceTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimeFenceAlarmReconciliationTest {
    @Test
    fun legacyDelayIsIgnoredAndMatchingEventQueuesImmediatePrompt() {
        val tag = tag(notificationType = TimedTagNotificationType.NONE)
        val vm = AlertsCapsuleViewModel(
            hostStateFlow = MutableStateFlow(AlertsHostState(tags = listOf(tag), tagLastUsedMsByTagId = emptyMap())),
            runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            getContext = { null },
            resolveSessionStartAtMs = { _, _ -> null },
            getTags = { listOf(tag) },
            getRunningSessions = { emptyList() },
            persist = {},
            persistAsync = {},
            scheduleAutoBackup = {},
            logUserEvent = { _, _, _, _, _, _ -> },
            logSystemEvent = { _, _, _, _, _ -> },
            elapsedRealtimeMs = { 0L },
        )
        vm.replaceTimeFenceRules(listOf(alertRule(timerMinutes = 15)))

        vm.handleTimeFenceEvents(
            events = listOf(
                TimeFenceEvent(
                    trigger = TimeFenceTrigger.ON_START,
                    sessionId = 200L,
                    sessionTitle = "Focus",
                    sessionTagIds = setOf(tag.id),
                )
            ),
            nowMs = 5_000L,
        )

        assertEquals(1, vm.uiState.value.preFencePrompts.size)
        assertEquals("check timer", vm.uiState.value.preFencePrompts.single().message)
        assertEquals(5_000L, vm.rules().single().lastFiredAtMs)
    }

    @Test
    fun cleanupCancelsLegacyAlarmButNeverBuildsANewTimerAlertSchedule() {
        val cleanup = buildLegacyTimerAlertCleanup(
            rules = listOf(alertRule(timerMinutes = 5)),
            sessions = listOf(session(id = 200L, expectedEndMs = null)),
        )

        assertEquals(1, cleanup.cancelAlarmKeys.size)
        assertEquals(setOf(100), cleanup.cancelNotificationIds)
    }

    @Test
    fun timedSessionRestoreSeparatesExpiredAndFutureSessions() {
        val timedTag = tag(notificationType = TimedTagNotificationType.ALARM)
        val plan = buildTimedSessionRestorePlan(
            sessions = listOf(
                session(id = 1L, expectedEndMs = 900L),
                session(id = 2L, expectedEndMs = 2_000L),
            ),
            tags = listOf(timedTag),
            nowMs = 1_000L,
        )

        assertEquals(setOf(1L), plan.expiredSessionIds)
        assertEquals(1, plan.alarms.size)
        assertEquals(2L, plan.alarms.single().sessionId)
        assertEquals(2_000L, plan.alarms.single().fireAtMs)
        assertTrue(plan.alarms.single().alarmStyle)
        assertFalse(plan.expiredSessionIds.contains(2L))
    }

    private fun alertRule(timerMinutes: Int): TimeFenceRule = TimeFenceRule(
        id = 100L,
        message = "check timer",
        trigger = TimeFenceTrigger.ON_START,
        delivery = TimeFenceDelivery.NOTIFICATION,
        scope = TimeFenceScope.ALWAYS,
        matchMode = TimeFenceMatchMode.AND,
        tagIds = setOf(1L),
        timerMinutes = timerMinutes,
    )

    private fun session(id: Long, expectedEndMs: Long?): SessionUi = SessionUi(
        id = id,
        title = "Focus",
        startMs = 100L,
        endMs = null,
        expectedEndMs = expectedEndMs,
        tagIds = setOf(1L),
    )

    private fun tag(notificationType: TimedTagNotificationType): Tag = Tag(
        id = 1L,
        name = "Work",
        timedDurationMinutes = 10,
        notificationType = notificationType,
        activeChildrenCount = 0,
        totalMs = 0L,
        lastStartedAtMs = null,
    )
}
