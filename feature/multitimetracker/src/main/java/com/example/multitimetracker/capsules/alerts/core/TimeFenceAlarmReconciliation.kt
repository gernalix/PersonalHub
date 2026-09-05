package com.example.multitimetracker.capsules.alerts.core

import android.content.Context
import com.example.multitimetracker.TimeFenceNotifier
import com.example.multitimetracker.TimeFenceTimerScheduler
import com.example.multitimetracker.capsules.alerts.public.TimeFenceEvent
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.TimeFenceDelivery
import com.example.multitimetracker.model.TimeFenceMatchMode
import com.example.multitimetracker.model.TimeFenceRule
import com.example.multitimetracker.model.TimeFenceTrigger

internal data class TimeFenceTimerKey(
    val ruleId: Long,
    val sessionId: Long,
    val expectedSessionStartAtMs: Long,
)

internal data class TimeFenceTimerSchedule(
    val key: TimeFenceTimerKey,
    val fireAtMs: Long,
    val title: String,
    val message: String,
)

internal data class TimeFenceAlarmReconciliation(
    val cancelTimerKeys: Set<TimeFenceTimerKey>,
    val scheduleTimers: List<TimeFenceTimerSchedule>,
    val cancelNotificationIds: Set<Int>,
)

internal data class TimeFenceRuntimeMatch(
    val title: String,
)

internal fun timeFenceRuleNotificationId(ruleId: Long): Int {
    return (ruleId % Int.MAX_VALUE).toInt().coerceAtLeast(1)
}

internal fun isLiveTimeFenceRule(rule: TimeFenceRule): Boolean {
    return !rule.isDeleted && rule.isEnabled
}

internal fun matchTimeFenceRuleForEvent(
    rule: TimeFenceRule,
    event: TimeFenceEvent,
    nowMs: Long,
    tagNameById: Map<Long, String>,
): TimeFenceRuntimeMatch? {
    if (!isLiveTimeFenceRule(rule)) return null
    if (rule.trigger != event.trigger) return null
    val matchesTags = when (rule.matchMode) {
        TimeFenceMatchMode.AND -> rule.tagIds.all { event.sessionTagIds.contains(it) }
        TimeFenceMatchMode.OR -> rule.tagIds.isEmpty() || rule.tagIds.any { event.sessionTagIds.contains(it) }
    }
    if (!matchesTags) return null
    val lastFired = rule.lastFiredAtMs ?: 0L
    if (rule.cooldownMs > 0L && (nowMs - lastFired) < rule.cooldownMs) return null
    val firstTagName = event.sessionTagIds.firstOrNull()
        ?.let { tagNameById[it] }
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    val title = if (!firstTagName.isNullOrBlank()) "${event.sessionTitle} • $firstTagName" else event.sessionTitle
    return TimeFenceRuntimeMatch(title = title)
}

internal fun scheduledTimeFenceFireAtMs(
    expectedSessionStartAtMs: Long,
    timerMinutes: Int,
): Long {
    return expectedSessionStartAtMs + (timerMinutes.toLong() * 60_000L)
}

internal fun hasScheduledTimeFenceAlreadyFired(
    rule: TimeFenceRule,
    expectedSessionStartAtMs: Long,
): Boolean {
    if (rule.timerMinutes <= 0) return false
    val lastFiredAtMs = rule.lastFiredAtMs ?: return false
    return lastFiredAtMs >= scheduledTimeFenceFireAtMs(
        expectedSessionStartAtMs = expectedSessionStartAtMs,
        timerMinutes = rule.timerMinutes,
    )
}

internal fun normalizeScheduledTimeFenceFireAtMs(
    expectedFireAtMs: Long,
    nowMs: Long,
): Long {
    return expectedFireAtMs.coerceAtLeast(nowMs)
}

internal fun buildTimeFenceAlarmReconciliation(
    beforeRules: List<TimeFenceRule>,
    afterRules: List<TimeFenceRule>,
    sessions: List<SessionUi>,
    tags: List<Tag>,
    nowMs: Long,
): TimeFenceAlarmReconciliation {
    val runningSessions = sessions.filter { it.endMs == null }
    val ruleIdsSeen = linkedSetOf<Long>()
    val candidateRules = buildList {
        (beforeRules + afterRules).forEach { rule ->
            if (ruleIdsSeen.add(rule.id)) {
                add(rule)
            }
        }
    }
    val timerCandidateRules = candidateRules.filter { rule ->
        rule.delivery == TimeFenceDelivery.NOTIFICATION &&
            rule.trigger == TimeFenceTrigger.ON_START &&
            rule.timerMinutes > 0
    }
    val candidateCancelTimerKeys = buildSet {
        timerCandidateRules.forEach { rule ->
            runningSessions.forEach { session ->
                add(
                    TimeFenceTimerKey(
                        ruleId = rule.id,
                        sessionId = session.id,
                        expectedSessionStartAtMs = session.startMs,
                    )
                )
            }
        }
    }

    val afterRulesById = afterRules.associateBy { it.id }
    val cancelNotificationIds = buildSet {
        candidateRules.forEach { beforeRule ->
            val afterRule = afterRulesById[beforeRule.id]
            val shouldCancel = afterRule == null ||
                !isLiveTimeFenceRule(afterRule) ||
                afterRule.delivery != TimeFenceDelivery.NOTIFICATION
            if (shouldCancel) {
                add(timeFenceRuleNotificationId(beforeRule.id))
            }
        }
    }

    return TimeFenceAlarmReconciliation(
        cancelTimerKeys = candidateCancelTimerKeys,
        scheduleTimers = emptyList(),
        cancelNotificationIds = cancelNotificationIds,
    )
}

internal fun executeTimeFenceAlarmReconciliation(
    context: Context,
    reconciliation: TimeFenceAlarmReconciliation,
): List<TimeFenceTimerScheduler.ScheduleResult> {
    reconciliation.cancelNotificationIds.forEach { notificationId ->
        TimeFenceNotifier.cancelNotification(context, notificationId)
    }
    reconciliation.cancelTimerKeys.forEach { key ->
        TimeFenceTimerScheduler.cancel(
            context = context,
            ruleId = key.ruleId,
            sessionId = key.sessionId,
            expectedSessionStartAtMs = key.expectedSessionStartAtMs,
        )
    }
    return reconciliation.scheduleTimers.map { timer ->
        TimeFenceTimerScheduler.schedule(
            context = context,
            ruleId = timer.key.ruleId,
            sessionId = timer.key.sessionId,
            expectedSessionStartAtMs = timer.key.expectedSessionStartAtMs,
            fireAtMs = timer.fireAtMs,
            title = timer.title,
            message = timer.message,
        )
    }
}
