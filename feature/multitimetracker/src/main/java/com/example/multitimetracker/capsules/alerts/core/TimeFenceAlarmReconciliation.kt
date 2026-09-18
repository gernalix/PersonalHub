package com.example.multitimetracker.capsules.alerts.core

import android.content.Context
import com.example.multitimetracker.TimeFenceNotifier
import com.example.multitimetracker.TimeFenceTimerScheduler
import com.example.multitimetracker.capsules.alerts.public.TimeFenceEvent
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.TimedTagNotificationType
import com.example.multitimetracker.model.TimeFenceMatchMode
import com.example.multitimetracker.model.TimeFenceRule
import com.example.multitimetracker.model.TimeFenceTrigger
import com.gernalix.personalhub.core.alerts.AlertMatchMode
import com.gernalix.personalhub.core.alerts.AlertMatching

internal data class LegacyTimerAlertKey(
    val ruleId: Long,
    val sessionId: Long,
    val expectedSessionStartAtMs: Long,
)

internal data class LegacyTimerAlertCleanup(
    val cancelAlarmKeys: Set<LegacyTimerAlertKey>,
    val cancelNotificationIds: Set<Int>,
)

internal data class TimedSessionAlarmRestore(
    val sessionId: Long,
    val fireAtMs: Long,
    val alarmStyle: Boolean,
)

internal data class TimedSessionRestorePlan(
    val expiredSessionIds: Set<Long>,
    val alarms: List<TimedSessionAlarmRestore>,
)

internal data class TimeFenceRuntimeMatch(
    val title: String,
)

internal fun timeFenceRuleNotificationId(ruleId: Long): Int =
    (ruleId % Int.MAX_VALUE).toInt().coerceAtLeast(1)

internal fun isLiveTimeFenceRule(rule: TimeFenceRule): Boolean =
    !rule.isDeleted && rule.isEnabled

internal fun matchTimeFenceRuleForEvent(
    rule: TimeFenceRule,
    event: TimeFenceEvent,
    nowMs: Long,
    tagNameById: Map<Long, String>,
): TimeFenceRuntimeMatch? {
    if (!isLiveTimeFenceRule(rule)) return null
    if (rule.trigger != event.trigger) return null
    val matchesTags = AlertMatching.tags(
        mode = when (rule.matchMode) {
            TimeFenceMatchMode.AND -> AlertMatchMode.ALL
            TimeFenceMatchMode.OR -> AlertMatchMode.ANY
        },
        requiredIds = rule.tagIds.mapTo(linkedSetOf()) { it.toString() },
        actualIds = event.sessionTagIds.mapTo(linkedSetOf()) { it.toString() },
        emptyAnyMatches = true,
    )
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

internal fun buildLegacyTimerAlertCleanup(
    rules: List<TimeFenceRule>,
    sessions: List<SessionUi>,
): LegacyTimerAlertCleanup {
    val legacyRules = rules.filter { rule ->
        rule.trigger == TimeFenceTrigger.ON_START && rule.timerMinutes > 0
    }
    val runningSessions = sessions.filter { it.endMs == null }
    return LegacyTimerAlertCleanup(
        cancelAlarmKeys = buildSet {
            legacyRules.forEach { rule ->
                runningSessions.forEach { session ->
                    add(
                        LegacyTimerAlertKey(
                            ruleId = rule.id,
                            sessionId = session.id,
                            expectedSessionStartAtMs = session.startMs,
                        )
                    )
                }
            }
        },
        cancelNotificationIds = legacyRules.mapTo(linkedSetOf()) { rule ->
            timeFenceRuleNotificationId(rule.id)
        },
    )
}

internal fun executeLegacyTimerAlertCleanup(
    context: Context,
    cleanup: LegacyTimerAlertCleanup,
) {
    cleanup.cancelNotificationIds.forEach { notificationId ->
        TimeFenceNotifier.cancelNotification(context, notificationId)
    }
    cleanup.cancelAlarmKeys.forEach { key ->
        TimeFenceTimerScheduler.cancelLegacyTimerAlert(
            context = context,
            ruleId = key.ruleId,
            sessionId = key.sessionId,
            expectedSessionStartAtMs = key.expectedSessionStartAtMs,
        )
    }
}

internal fun buildTimedSessionRestorePlan(
    sessions: List<SessionUi>,
    tags: List<Tag>,
    nowMs: Long,
    randomSessionIds: Set<Long> = emptySet(),
): TimedSessionRestorePlan {
    val liveTagsById = tags.filterNot { it.isDeleted }.associateBy { it.id }
    val running = sessions.filter { it.endMs == null && it.expectedEndMs != null }
    val expiredIds = running
        .filter { session -> session.expectedEndMs!! <= nowMs }
        .mapTo(linkedSetOf()) { it.id }
    val alarms = running.mapNotNull { session ->
        val expectedEndMs = session.expectedEndMs ?: return@mapNotNull null
        if (expectedEndMs <= nowMs) return@mapNotNull null

        if (session.id in randomSessionIds) {
            return@mapNotNull TimedSessionAlarmRestore(
                sessionId = session.id,
                fireAtMs = expectedEndMs,
                alarmStyle = false,
            )
        }

        val timedTags = session.tagIds.mapNotNull(liveTagsById::get)
            .filter { (it.timedDurationMinutes ?: 0) > 0 }
        val timedTag = timedTags.singleOrNull() ?: return@mapNotNull null
        if (timedTag.notificationType == TimedTagNotificationType.NONE) return@mapNotNull null
        TimedSessionAlarmRestore(
            sessionId = session.id,
            fireAtMs = expectedEndMs,
            alarmStyle = timedTag.notificationType == TimedTagNotificationType.ALARM,
        )
    }
    return TimedSessionRestorePlan(expiredSessionIds = expiredIds, alarms = alarms)
}
