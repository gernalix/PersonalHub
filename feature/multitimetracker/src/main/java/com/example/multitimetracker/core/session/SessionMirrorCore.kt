// v471
package com.example.multitimetracker.core.session

import android.content.Context
import com.example.multitimetracker.core.contracts.ClosedSessionRecord
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.Task
import com.example.multitimetracker.persistence.SessionMirrorSqlite
import com.example.multitimetracker.util.CapsuleWriteApi

/**
 * FEATURE CAPSULE: Session Mirror Core — START
 *
 * Bridge API for session-table mirroring and high-level session-only stats.
 *
 * Architectural rule: call sites must use this core instead of touching SessionMirrorSqlite.
 *
 * Invariants:
 * - Mirroring is treated as a write-path (CapsuleWriteApi).
 * - Callers provide legacy snapshot DTOs; DB strategy is encapsulated.
 * FEATURE CAPSULE: Session Mirror Core — END
 */
object SessionMirrorCore {

    data class SessionStats(
        val intervals: List<Pair<Long, Long>>,
        val sessionsTotal: Long,
        val runningTotal: Long,
        val deletedTotal: Long
    )

    fun readSessionStats(context: Context, nowMs: Long): SessionStats {
        val s = SessionMirrorSqlite.readSessionStats(context = context, nowMs = nowMs)
        return SessionStats(
            intervals = s.intervals,
            sessionsTotal = s.sessionsTotal,
            runningTotal = s.runningTotal,
            deletedTotal = s.deletedTotal
        )
    }

    fun buildDeveloperIntegrityReport(context: Context, nowMs: Long): String =
        SessionMirrorSqlite.buildDeveloperIntegrityReport(context = context, nowMs = nowMs)

    fun buildDiagnosticsReport(
        context: Context,
        sessionOnlyModeEnabled: Boolean,
        tasks: List<Task>,
        tags: List<Tag>,
        closedSessions: List<ClosedSessionRecord>,
        nowMs: Long,
        maxRows: Int = 200
    ): String =
        SessionMirrorSqlite.buildDiagnosticsReport(
            context = context,
            sessionOnlyModeEnabled = sessionOnlyModeEnabled,
            tasks = tasks,
            tags = tags,
            closedSessions = closedSessions,
            nowMs = nowMs,
            maxRows = maxRows
        )

    @OptIn(CapsuleWriteApi::class)
    fun mirrorFromLegacySnapshot(
        context: Context,
        tasks: List<Task>,
        tags: List<Tag>,
        closedSessions: List<ClosedSessionRecord>,
        nowMs: Long
    ) {
        SessionMirrorSqlite.mirrorFromSnapshot(
            context = context,
            tasks = tasks,
            tags = tags,
            closedSessions = closedSessions,
            nowMs = nowMs
        )
    }
}



