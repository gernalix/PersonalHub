// v317
package com.example.multitimetracker.core.session

import android.content.Context
import com.example.multitimetracker.TimeFenceTimerScheduler
import com.example.multitimetracker.TimedSessionSupport
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.TimedTagNotificationType
import com.example.multitimetracker.persistence.SnapshotStore
import com.example.multitimetracker.persistence.SessionRepository
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.core.hubcontext.HubContextRuntime
import kotlinx.coroutines.runBlocking

/**
 * Default implementation backed by [SessionRepository].
 *
 * NOTE: This class is intentionally thin. It exists to enforce architectural boundaries:
 * callers depend on [SessionCore], not on [SessionRepository] directly.
 */
@OptIn(com.example.multitimetracker.util.CapsuleWriteApi::class)
class DefaultSessionCore(private val context: Context) : SessionCore {

    // v317 PERF: avoid allocating a new repository instance for every call.
    private val repo: SessionRepository by lazy(LazyThreadSafetyMode.NONE) {
        SessionRepository(context)
    }

    override fun readSessionById(sessionId: Long): SessionUi? =
        repo.readSessionById(sessionId)

    override fun readAllSessions(): List<SessionUi> =
        repo.readAllSessions()

    override fun searchSessions(query: String, limit: Int): List<SessionUi> =
        repo.searchSessions(query, limit)

    override fun readRunningSessions(): List<SessionUi> =
        repo.readRunningSessions()

    override fun insertSession(title: String, startMs: Long, endMs: Long?, tagIds: Set<Long>): Long {
        val sessionId = repo.insertSession(title = title, startMs = startMs, endMs = endMs, tagIds = tagIds)
        syncTimedSessionAlarm(sessionId)
        return sessionId
    }

    override fun updateSessionMeta(sessionId: Long, title: String, tagIds: Set<Long>) {
        repo.updateSessionMeta(sessionId = sessionId, title = title, tagIds = tagIds)
        syncTimedSessionAlarm(sessionId)
    }

    override fun updateSessionTimes(sessionId: Long, startMs: Long, endMs: Long?) {
        repo.updateSessionTimes(sessionId = sessionId, startMs = startMs, endMs = endMs)
        syncTimedSessionAlarm(sessionId)
    }

    override fun softDeleteSession(sessionId: Long) {
        repo.softDeleteSession(sessionId = sessionId)
        TimeFenceTimerScheduler.cancelTimedSession(context, sessionId)
        runBlocking { HubContextRuntime.canonicalDeletedIfInitialized(HubEntityRef("timer", "session", sessionId.toString())) }
    }

    override fun ensureRunningSessionRow(title: String, startMs: Long, tagIds: Set<Long>, nowMs: Long): Long {
        val sessionId = repo.ensureRunningSessionRow(title = title, startMs = startMs, tagIds = tagIds, nowMs = nowMs)
        syncTimedSessionAlarm(sessionId)
        return sessionId
    }

    override fun computeUnionTotalsClosedForTagIds(tagIds: Set<Long>): Map<Long, Long> =
        repo.computeUnionTotalsClosedForTagIds(tagIds = tagIds)

    private fun syncTimedSessionAlarm(sessionId: Long) {
        val session = repo.readSessionById(sessionId)
        if (session == null) {
            TimeFenceTimerScheduler.cancelTimedSession(context, sessionId)
            return
        }
        val tags = SnapshotStore.load(context)?.tags.orEmpty()
        val nowMs = System.currentTimeMillis()
        val timedMatch = TimedSessionSupport.findTimedTagMatchForSession(session, tags)
        val expectedEndMs = session.expectedEndMs
        val shouldSchedule = session.endMs == null &&
            expectedEndMs != null &&
            expectedEndMs > nowMs &&
            timedMatch != null &&
            timedMatch.notificationType != TimedTagNotificationType.NONE

        if (shouldSchedule) {
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
}
