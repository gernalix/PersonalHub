package com.example.multitimetracker.core.session

import com.example.multitimetracker.model.SessionUi

/**
 * FEATURE CAPSULE: Session Core — START
 *
 * Session Core is the only allowed write-path owner for session-only tables (sessions + session_tags).
 * Other capsules must talk to sessions ONLY through this interface (bridge), not by directly touching
 * SessionRepository or SnapshotSqlite.
 *
 * Invariants:
 * - All writes to sessions/session_tags are centralized here.
 * - Callers provide intent-level data; DB strategy remains encapsulated.
 * FEATURE CAPSULE: Session Core — END
 */
interface SessionCore {

    // --- Reads ---
    fun readSessionById(sessionId: Long): SessionUi?
    fun readAllSessions(): List<SessionUi>
    fun searchSessions(query: String, limit: Int): List<SessionUi>
    fun readTemporalSessions(fromMs: Long, toMs: Long, limit: Int, offset: Int): List<SessionUi>
    fun readRunningSessions(): List<SessionUi>

    // --- Writes ---
    /** Inserts a new session row and returns the new session id. */
    fun insertSession(title: String, startMs: Long, endMs: Long?, tagIds: Set<Long>): Long
    fun updateSessionMeta(sessionId: Long, title: String, tagIds: Set<Long>)
    fun updateSessionTimes(sessionId: Long, startMs: Long, endMs: Long?)
    fun softDeleteSession(sessionId: Long)

    /**
     * Repository-level "start session" entry point.
     * Ensures a running session row exists for the given [startMs] and [title], with [tagIds].
     */
    fun ensureRunningSessionRow(title: String, startMs: Long, tagIds: Set<Long>, nowMs: Long): Long

    /**
     * Computes union totals for the given [tagIds] across sessions closed up to [nowMs].
     * Used by NOW screen for "Active Tags" aggregates in session-only mode.
     */
    fun computeUnionTotalsClosedForTagIds(tagIds: Set<Long>): Map<Long, Long>
}
