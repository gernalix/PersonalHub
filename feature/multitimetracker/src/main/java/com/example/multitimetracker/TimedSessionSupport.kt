package com.example.multitimetracker

import android.content.Context
import com.example.multitimetracker.core.session.DefaultSessionCore
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.TimedTagNotificationType

private const val MINUTE_MS = 60_000L

data class TimedTagMatch(
    val tag: Tag,
    val durationMinutes: Int,
    val expectedEndMs: Long,
    val notificationType: TimedTagNotificationType,
)

fun Tag.isTimedTag(): Boolean = (timedDurationMinutes ?: 0) > 0

fun findSingleTimedTagMatch(
    tagIds: Set<Long>,
    tags: List<Tag>,
    startMs: Long,
): TimedTagMatch? {
    val timedTags = tags.filter { it.id in tagIds && !it.isDeleted && (it.timedDurationMinutes ?: 0) > 0 }
    if (timedTags.size != 1) return null
    val tag = timedTags.first()
    val durationMinutes = tag.timedDurationMinutes ?: return null
    return TimedTagMatch(
        tag = tag,
        durationMinutes = durationMinutes,
        expectedEndMs = startMs + (durationMinutes.toLong() * MINUTE_MS),
        notificationType = tag.notificationType,
    )
}

fun requireTimedSessionExpectation(
    tagIds: Set<Long>,
    tags: List<Tag>,
    startMs: Long,
): Long? {
    val timedTags = tags.filter { it.id in tagIds && !it.isDeleted && (it.timedDurationMinutes ?: 0) > 0 }
    require(timedTags.size <= 1) { "Only one timed tag is allowed per session." }
    val tag = timedTags.firstOrNull() ?: return null
    val durationMinutes = tag.timedDurationMinutes ?: return null
    return startMs + (durationMinutes.toLong() * MINUTE_MS)
}

fun formatTimedDurationMinutes(totalMinutes: Int?): String {
    val minutes = (totalMinutes ?: 0).coerceAtLeast(0)
    if (minutes <= 0) return ""
    val hoursPart = minutes / 60
    val minutesPart = minutes % 60
    return buildString {
        if (hoursPart > 0) append("${hoursPart}h")
        if (minutesPart > 0) {
            if (isNotEmpty()) append(" ")
            append("${minutesPart}m")
        }
        if (isEmpty()) append("0m")
    }
}

fun timedTagDisplayLabel(tag: Tag): String {
    val suffix = if (tag.isTimedTag()) " ⏱${formatTimedDurationMinutes(tag.timedDurationMinutes)}" else ""
    val base = tag.name.trim().ifBlank { tag.id.toString() }
    return "$base$suffix"
}

object TimedSessionSupport {
    fun findTimedTagMatchForSession(session: SessionUi, tags: List<Tag>): TimedTagMatch? =
        findSingleTimedTagMatch(session.tagIds, tags, session.startMs)

    fun reconcileExpiredSessions(context: Context, tags: List<Tag>, nowMs: Long, notify: Boolean) {
        val sessionCore = DefaultSessionCore(context)
        sessionCore.readRunningSessions()
            .filter { it.endMs == null && it.expectedEndMs != null && nowMs >= it.expectedEndMs }
            .forEach { session ->
                val expectedEndMs = session.expectedEndMs ?: return@forEach
                sessionCore.updateSessionTimes(
                    sessionId = session.id,
                    startMs = session.startMs,
                    endMs = expectedEndMs,
                )
                if (notify) {
                    showCompletionNotificationIfNeeded(context, session, tags)
                }
            }
    }

    fun syncScheduledAlarms(
        context: Context,
        sessions: List<SessionUi>,
        tags: List<Tag>,
        trackedSessionIds: MutableSet<Long>,
        nowMs: Long,
    ) {
        val desiredIds = mutableSetOf<Long>()
        sessions.forEach { session ->
            val timedMatch = findTimedTagMatchForSession(session, tags)
            val expectedEndMs = session.expectedEndMs
            val shouldSchedule = session.endMs == null &&
                expectedEndMs != null &&
                expectedEndMs > nowMs &&
                timedMatch != null &&
                timedMatch.notificationType != TimedTagNotificationType.NONE

            if (shouldSchedule) {
                desiredIds += session.id
                TimeFenceTimerScheduler.scheduleTimedSession(
                    context = context,
                    sessionId = session.id,
                    fireAtMs = expectedEndMs,
                    alarmStyle = timedMatch.notificationType == TimedTagNotificationType.ALARM,
                )
            } else {
                TimeFenceTimerScheduler.cancelTimedSession(context, session.id)
            }
        }

        trackedSessionIds
            .filter { it !in desiredIds }
            .forEach { TimeFenceTimerScheduler.cancelTimedSession(context, it) }

        trackedSessionIds.clear()
        trackedSessionIds.addAll(desiredIds)
    }

    fun showCompletionNotificationIfNeeded(context: Context, session: SessionUi, tags: List<Tag>) {
        val timedMatch = findTimedTagMatchForSession(session, tags) ?: return
        if (timedMatch.notificationType == TimedTagNotificationType.NONE) return

        val title = session.title.trim().ifBlank {
            timedTagDisplayLabel(timedMatch.tag)
        }
        val message = "Reached scheduled end time."
        TimeFenceNotifier.notifyTimedSession(
            context = context,
            notificationId = session.id.toInt(),
            title = title,
            message = message,
            notificationType = timedMatch.notificationType,
        )
    }
}
