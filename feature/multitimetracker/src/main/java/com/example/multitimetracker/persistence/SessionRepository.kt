// v317
package com.example.multitimetracker.persistence

import android.content.Context
import com.example.multitimetracker.requireTimedSessionExpectation
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.util.CapsuleAudit
import com.example.multitimetracker.util.CapsuleWriteApi
import org.json.JSONObject

/**
 * Session-only persistence implementation.
 *
 * Architectural rule: other layers must depend on the SessionCore bridge rather than using this
 * repository directly.
 */
internal class SessionRepository(private val context: Context) {

    /** Reads a single session (with tags) by ID. Returns null if not found / deleted. */
    fun readSessionById(sessionId: Long): SessionUi? {
        SnapshotSqlite.ensureSessionTables(context)
        val db = SnapshotSqlite.openReadableDb(context)
        try {
            val sql = """
                SELECT
                    s.id,
                    s.title,
                    s.start_ms,
                    s.end_ms,
                    s.expected_end_ms,
                    s.deleted_at_ms,
                    st.tag_id
                FROM ${SnapshotSqlite.SESSIONS_TABLE} s
                LEFT JOIN ${SnapshotSqlite.SESSION_TAGS_TABLE} st
                    ON st.session_id = s.id
                WHERE s.deleted_at_ms IS NULL
                  AND s.id = ?
            """.trimIndent()

            var acc: SessionAccumulator? = null
            db.rawQuery(sql, arrayOf(sessionId.toString())).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val title = c.getString(1)
                    val startMs = c.getLong(2)
                    val endMs = if (c.isNull(3)) null else c.getLong(3)
                    val expectedEndMs = if (c.isNull(4)) null else c.getLong(4)
                    val deletedAtMs = if (c.isNull(5)) null else c.getLong(5)
                    val tagId = if (c.isNull(6)) null else c.getLong(6)

                    if (acc == null) {
                        acc = SessionAccumulator(
                            id = id,
                            title = title,
                            startMs = startMs,
                            endMs = endMs,
                            expectedEndMs = expectedEndMs,
                            deletedAtMs = deletedAtMs
                        )
                    }
                    if (tagId != null) acc?.tagIds?.add(tagId)
                }
            }

            return acc?.toUi()
        } finally {
            db.close()
        }
    }

    fun readAllSessions(): List<SessionUi> {
        // Defensive: ensure tables exist even on weird DB states.
        SnapshotSqlite.ensureSessionTables(context)

        val db = SnapshotSqlite.openReadableDb(context)
        try {
            // One-pass join to avoid N+1 queries.
            val sql = """
                SELECT
                    s.id,
                    s.title,
                    s.start_ms,
                    s.end_ms,
                    s.expected_end_ms,
                    s.deleted_at_ms,
                    st.tag_id
                FROM ${SnapshotSqlite.SESSIONS_TABLE} s
                LEFT JOIN ${SnapshotSqlite.SESSION_TAGS_TABLE} st
                    ON st.session_id = s.id
                WHERE s.deleted_at_ms IS NULL
            """.trimIndent()

            val sessionsById = LinkedHashMap<Long, SessionAccumulator>()

            db.rawQuery(sql, emptyArray()).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val title = c.getString(1)
                    val startMs = c.getLong(2)

                    val endMs = if (c.isNull(3)) null else c.getLong(3)
                    val expectedEndMs = if (c.isNull(4)) null else c.getLong(4)
                    val deletedAtMs = if (c.isNull(5)) null else c.getLong(5)
                    val tagId = if (c.isNull(6)) null else c.getLong(6)

                    val acc = sessionsById.getOrPut(id) {
                        SessionAccumulator(
                            id = id,
                            title = title,
                            startMs = startMs,
                            endMs = endMs,
                            expectedEndMs = expectedEndMs,
                            deletedAtMs = deletedAtMs
                        )
                    }
                    if (tagId != null) acc.tagIds.add(tagId)
                }
            }

            return sessionsById.values.map { it.toUi() }
        } finally {
            db.close()
        }
    }

    fun searchSessions(query: String, limit: Int): List<SessionUi> {
        SnapshotSqlite.ensureSessionTables(context)
        val boundedLimit = limit.coerceIn(1, 100)
        val normalized = query.trim()
        val db = SnapshotSqlite.openReadableDb(context)
        try {
            val sql = """
                SELECT s.id,s.title,s.start_ms,s.end_ms,s.expected_end_ms,s.deleted_at_ms,st.tag_id
                FROM ${SnapshotSqlite.SESSIONS_TABLE} s
                LEFT JOIN ${SnapshotSqlite.SESSION_TAGS_TABLE} st ON st.session_id=s.id
                WHERE s.id IN (
                    SELECT id FROM ${SnapshotSqlite.SESSIONS_TABLE}
                    WHERE deleted_at_ms IS NULL
                      AND (? = '' OR instr(lower(title), lower(?)) > 0 OR CAST(id AS TEXT) = ?)
                    ORDER BY start_ms DESC,id DESC LIMIT ?
                )
                ORDER BY s.start_ms DESC,s.id DESC
            """.trimIndent()
            val sessions = LinkedHashMap<Long, SessionAccumulator>()
            db.rawQuery(sql, arrayOf(normalized, normalized, normalized, boundedLimit.toString())).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val value = sessions.getOrPut(id) {
                        SessionAccumulator(id, c.getString(1), c.getLong(2), if (c.isNull(3)) null else c.getLong(3), if (c.isNull(4)) null else c.getLong(4), if (c.isNull(5)) null else c.getLong(5))
                    }
                    if (!c.isNull(6)) value.tagIds.add(c.getLong(6))
                }
            }
            return sessions.values.map { it.toUi() }
        } finally {
            db.close()
        }
    }

    fun readTemporalSessions(fromMs: Long, toMs: Long, limit: Int, offset: Int): List<SessionUi> {
        require(fromMs < toMs && limit in 1..200 && offset >= 0)
        SnapshotSqlite.ensureSessionTables(context)
        val db = SnapshotSqlite.openReadableDb(context)
        try {
            val sql = """
                SELECT id,title,start_ms,end_ms,expected_end_ms,deleted_at_ms
                FROM ${SnapshotSqlite.SESSIONS_TABLE}
                WHERE deleted_at_ms IS NULL AND start_ms < ? AND (end_ms IS NULL OR end_ms > ?)
                ORDER BY start_ms DESC,id DESC LIMIT ? OFFSET ?
            """.trimIndent()
            return db.rawQuery(sql, arrayOf(toMs.toString(), fromMs.toString(), limit.toString(), offset.toString())).use { c ->
                buildList {
                    while (c.moveToNext()) add(SessionAccumulator(c.getLong(0), c.getString(1), c.getLong(2), if (c.isNull(3)) null else c.getLong(3), if (c.isNull(4)) null else c.getLong(4), if (c.isNull(5)) null else c.getLong(5)).toUi())
                }
            }
        } finally {
            db.close()
        }
    }

    /** Returns sessions where end_ms IS NULL (running sessions). */
    fun readRunningSessions(): List<SessionUi> {
        SnapshotSqlite.ensureSessionTables(context)
        val db = SnapshotSqlite.openReadableDb(context)
        try {
            val sql = """
                SELECT
                    s.id,
                    s.title,
                    s.start_ms,
                    s.end_ms,
                    s.expected_end_ms,
                    s.deleted_at_ms,
                    st.tag_id
                FROM ${SnapshotSqlite.SESSIONS_TABLE} s
                LEFT JOIN ${SnapshotSqlite.SESSION_TAGS_TABLE} st
                    ON st.session_id = s.id
                WHERE s.deleted_at_ms IS NULL
                  AND s.end_ms IS NULL
            """.trimIndent()

            val sessionsById = LinkedHashMap<Long, SessionAccumulator>()
            db.rawQuery(sql, emptyArray()).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val title = c.getString(1)
                    val startMs = c.getLong(2)
                    val endMs = if (c.isNull(3)) null else c.getLong(3)
                    val expectedEndMs = if (c.isNull(4)) null else c.getLong(4)
                    val deletedAtMs = if (c.isNull(5)) null else c.getLong(5)
                    val tagId = if (c.isNull(6)) null else c.getLong(6)

                    val acc = sessionsById.getOrPut(id) {
                        SessionAccumulator(
                            id = id,
                            title = title,
                            startMs = startMs,
                            endMs = endMs,
                            expectedEndMs = expectedEndMs,
                            deletedAtMs = deletedAtMs
                        )
                    }
                    if (tagId != null) acc.tagIds.add(tagId)
                }
            }
            return sessionsById.values.map { it.toUi() }
        } finally {
            db.close()
        }
    }

    /**
     * Computes (historic + live) totals for the given tag IDs as UNION of intervals.
     * Only considers sessions where deleted_at_ms IS NULL.
     * Running sessions (end_ms NULL) use [nowMs] as effective end.
     */
    /* FEATURE CAPSULE: Tag union totals (including running) (Repository) — START */
    fun computeUnionTotalsForTagIds(tagIds: Set<Long>, nowMs: Long): Map<Long, Long> {
        if (tagIds.isEmpty()) return emptyMap()
        SnapshotSqlite.ensureSessionTables(context)

        val db = SnapshotSqlite.openReadableDb(context)
        try {
            // Build IN clause placeholders.
            val placeholders = tagIds.joinToString(",") { "?" }
            val args = tagIds.map { it.toString() }.toTypedArray()

            val sql = """
                SELECT
                    st.tag_id,
                    s.start_ms,
                    s.end_ms
                FROM ${SnapshotSqlite.SESSIONS_TABLE} s
                JOIN ${SnapshotSqlite.SESSION_TAGS_TABLE} st
                    ON st.session_id = s.id
                WHERE s.deleted_at_ms IS NULL
                  AND st.tag_id IN ($placeholders)
            """.trimIndent()

            val intervalsByTagId = HashMap<Long, MutableList<Pair<Long, Long>>>()
            db.rawQuery(sql, args).use { c ->
                while (c.moveToNext()) {
                    val tagId = c.getLong(0)
                    val start = c.getLong(1)
                    val end = if (c.isNull(2)) nowMs else c.getLong(2)
                    if (end <= start) continue
                    intervalsByTagId.getOrPut(tagId) { mutableListOf() }.add(start to end)
                }
            }

            val totals = HashMap<Long, Long>()
            for ((tagId, intervals) in intervalsByTagId) {
                totals[tagId] = unionTotalMs(intervals)
            }
            // Ensure every requested tag exists in map (even if no sessions).
            for (tagId in tagIds) {
                totals.putIfAbsent(tagId, 0L)
            }
            return totals
        } finally {
            db.close()
        }
    }
    /* FEATURE CAPSULE: Tag union totals (including running) (Repository) — END */

    /**
     * Computes totals for the given tag IDs as UNION of CLOSED intervals only.
     * Only considers sessions where deleted_at_ms IS NULL AND end_ms IS NOT NULL.
     */
    /* FEATURE CAPSULE: Tag union totals (Repository) — START */
fun computeUnionTotalsClosedForTagIds(tagIds: Set<Long>): Map<Long, Long> {
        if (tagIds.isEmpty()) return emptyMap()
        SnapshotSqlite.ensureSessionTables(context)

        val db = SnapshotSqlite.openReadableDb(context)
        try {
            val placeholders = tagIds.joinToString(",") { "?" }
            val args = tagIds.map { it.toString() }.toTypedArray()

            val sql = """
                SELECT
                    st.tag_id,
                    s.start_ms,
                    s.end_ms
                FROM ${SnapshotSqlite.SESSIONS_TABLE} s
                JOIN ${SnapshotSqlite.SESSION_TAGS_TABLE} st
                    ON st.session_id = s.id
                WHERE s.deleted_at_ms IS NULL
                  AND s.end_ms IS NOT NULL
                  AND st.tag_id IN ($placeholders)
            """.trimIndent()

            val intervalsByTagId = HashMap<Long, MutableList<Pair<Long, Long>>>()
            db.rawQuery(sql, args).use { c ->
                while (c.moveToNext()) {
                    val tagId = c.getLong(0)
                    val start = c.getLong(1)
                    val end = c.getLong(2)
                    if (end <= start) continue
                    intervalsByTagId.getOrPut(tagId) { mutableListOf() }.add(start to end)
                }
            }

            val totals = HashMap<Long, Long>()
            for ((tagId, intervals) in intervalsByTagId) {
                totals[tagId] = unionTotalMs(intervals)
            }
            for (tagId in tagIds) totals.putIfAbsent(tagId, 0L)
            return totals
        } finally {
            db.close()
        }
    }



    /** Inserts a new session row and optional tag links. Returns the new session id. */
    @CapsuleWriteApi
    fun insertSession(title: String, startMs: Long, endMs: Long?, tagIds: Set<Long>): Long {
        // v249: expand capsule boundary self-check coverage for session-only write paths.
        CapsuleAudit.auditPersistenceWrite("SessionRepository.insertSession")
        SnapshotSqlite.ensureSessionTables(context)
        val db = SnapshotSqlite.openWritableDb(context)
        val now = System.currentTimeMillis()
        val expectedEndMs = computeExpectedEndMs(tagIds = tagIds, startMs = startMs)
        var insertedSessionId = -1L
        db.beginTransaction()
        try {
            val cv = android.content.ContentValues().apply {
                put("title", title)
                put("start_ms", startMs)
                if (endMs == null) putNull("end_ms") else put("end_ms", endMs)
                if (expectedEndMs == null) putNull("expected_end_ms") else put("expected_end_ms", expectedEndMs)
                put("created_at_ms", now)
                put("updated_at_ms", now)
                putNull("deleted_at_ms")
            }
            // v317: fail fast if insert fails (avoid session_id = -1 cascades).
            val sessionId = db.insertOrThrow(SnapshotSqlite.SESSIONS_TABLE, null, cv)
            insertedSessionId = sessionId

            // Replace tags (best-effort; idempotent for new session).
            for (tagId in tagIds) {
                val tagCv = android.content.ContentValues().apply {
                    put("session_id", sessionId)
                    put("tag_id", tagId)
                }
                db.insertWithOnConflict(
                    SnapshotSqlite.SESSION_TAGS_TABLE,
                    null,
                    tagCv,
                    com.gernalix.personalhub.core.database.LegacyDatabase.CONFLICT_IGNORE
                )
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
        PersistentMutationTracker.record(context, "SessionRepository.insertSession")
        return insertedSessionId
    }

// --- Write-path helpers (v89): session-only CRUD for Chronology ---

@CapsuleWriteApi
fun updateSessionMeta(sessionId: Long, title: String, tagIds: Set<Long>) {
    CapsuleAudit.auditPersistenceWrite("SessionRepository.updateSessionMeta")
    SnapshotSqlite.ensureSessionTables(context)
    val before = readSessionById(sessionId) ?: return
    val db = SnapshotSqlite.openWritableDb(context)
    val now = System.currentTimeMillis()
    val expectedEndMs = computeExpectedEndMs(tagIds = tagIds, startMs = before.startMs)
    db.beginTransaction()
    try {
        val cv = android.content.ContentValues().apply {
            put("title", title)
            if (expectedEndMs == null) putNull("expected_end_ms") else put("expected_end_ms", expectedEndMs)
            put("updated_at_ms", now)
        }
        db.update(
            SnapshotSqlite.SESSIONS_TABLE,
            cv,
            "id = ? AND deleted_at_ms IS NULL",
            arrayOf(sessionId.toString())
        )

        db.delete(
            SnapshotSqlite.SESSION_TAGS_TABLE,
            "session_id = ?",
            arrayOf(sessionId.toString())
        )

        for (tagId in tagIds) {
            val tagCv = android.content.ContentValues().apply {
                put("session_id", sessionId)
                put("tag_id", tagId)
            }
            db.insertWithOnConflict(
                SnapshotSqlite.SESSION_TAGS_TABLE,
                null,
                tagCv,
                com.gernalix.personalhub.core.database.LegacyDatabase.CONFLICT_IGNORE
            )
        }

        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
        db.close()
    }
    PersistentMutationTracker.record(context, "SessionRepository.updateSessionMeta")
}
/* FEATURE CAPSULE: Tag union totals (Repository) — END */

@CapsuleWriteApi
fun updateSessionTimes(sessionId: Long, startMs: Long, endMs: Long?) {
    CapsuleAudit.auditPersistenceWrite("SessionRepository.updateSessionTimes")
    SnapshotSqlite.ensureSessionTables(context)
    val before = readSessionById(sessionId) ?: return
    val db = SnapshotSqlite.openWritableDb(context)
    val now = System.currentTimeMillis()
    val expectedEndMs = computeExpectedEndMs(tagIds = before.tagIds, startMs = startMs)
    val resolvedEndMs = when {
        endMs == null -> null
        expectedEndMs != null && endMs >= expectedEndMs -> expectedEndMs
        else -> endMs
    }
    db.beginTransaction()
    try {
        val cv = android.content.ContentValues().apply {
            put("start_ms", startMs)
            if (resolvedEndMs == null) putNull("end_ms") else put("end_ms", resolvedEndMs)
            if (expectedEndMs == null) putNull("expected_end_ms") else put("expected_end_ms", expectedEndMs)
            put("updated_at_ms", now)
        }
        db.update(
            SnapshotSqlite.SESSIONS_TABLE,
            cv,
            "id = ? AND deleted_at_ms IS NULL",
            arrayOf(sessionId.toString())
        )
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
        db.close()
    }
    PersistentMutationTracker.record(context, "SessionRepository.updateSessionTimes")
}

@CapsuleWriteApi
fun softDeleteSession(sessionId: Long) {
    CapsuleAudit.auditPersistenceWrite("SessionRepository.softDeleteSession")
    SnapshotSqlite.ensureSessionTables(context)
    val db = SnapshotSqlite.openWritableDb(context)
    val now = System.currentTimeMillis()
    db.beginTransaction()
    try {
        val cv = android.content.ContentValues().apply {
            put("deleted_at_ms", now)
            put("updated_at_ms", now)
        }
        db.update(
            SnapshotSqlite.SESSIONS_TABLE,
            cv,
            "id = ? AND deleted_at_ms IS NULL",
            arrayOf(sessionId.toString())
        )
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
        db.close()
    }
    PersistentMutationTracker.record(context, "SessionRepository.softDeleteSession")
}

/**
 * Inserts a running session row if missing (id is auto-assigned).
 * Used by the legacy start/stop path while we complete the session-only write path.
 *
 * Invariants:
 * - A running session is identified primarily by (start_ms, end_ms IS NULL, deleted_at_ms IS NULL).
 */

@CapsuleWriteApi
fun createRunningSession(title: String, startMs: Long, tagIds: Set<Long>, nowMs: Long): Long {
    CapsuleAudit.auditPersistenceWrite("SessionRepository.createRunningSession")
    SnapshotSqlite.ensureSessionTables(context)
    val db = SnapshotSqlite.openWritableDb(context)
    val sessionId: Long
    db.beginTransaction()
    try {
        val cv = android.content.ContentValues().apply {
            put("title", title)
            put("start_ms", startMs)
            put("end_ms", null as Long?)
            put("deleted_at_ms", null as Long?)
            put("created_at_ms", nowMs)
            put("updated_at_ms", nowMs)
        }
        sessionId = db.insertOrThrow(SnapshotSqlite.SESSIONS_TABLE, null, cv)
        tagIds.forEach { tagId ->
            val cvTag = android.content.ContentValues().apply {
                put("session_id", sessionId)
                put("tag_id", tagId)
            }
            db.insertOrThrow(SnapshotSqlite.SESSION_TAGS_TABLE, null, cvTag)
        }
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
        db.close()
    }

    SyncInvariants.log(
        context,
        action = "session_create_running",
        summary = "created running session id=$sessionId startMs=$startMs tags=${tagIds.size}",
        payload = JSONObject().apply {
            put("session_id", sessionId)
            put("start_ms", startMs)
            put("tag_count", tagIds.size)
        }
    )
    PersistentMutationTracker.record(context, "SessionRepository.createRunningSession")
    return sessionId
}

@CapsuleWriteApi
fun insertRunningSessionIfMissing(title: String, startMs: Long, tagIds: Set<Long>, nowMs: Long): Long {
    CapsuleAudit.auditPersistenceWrite("SessionRepository.insertRunningSessionIfMissing")
    SnapshotSqlite.ensureSessionTables(context)
    val db = SnapshotSqlite.openWritableDb(context)
    val expectedEndMs = computeExpectedEndMs(tagIds = tagIds, startMs = startMs)
    var changed = false
    var resultSessionId = -1L
    db.beginTransaction()
    try {
        // If already present, do nothing.
        val existingId = db.rawQuery(
            "SELECT id FROM ${SnapshotSqlite.SESSIONS_TABLE} WHERE start_ms=? AND end_ms IS NULL AND deleted_at_ms IS NULL ORDER BY id DESC LIMIT 1",
            arrayOf(startMs.toString())
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

        val sessionId = if (existingId != null) {
            // v118 HOTFIX: keep title/timestamps in sync for running sessions.
            val cv = android.content.ContentValues().apply {
                put("title", title)
                if (expectedEndMs == null) putNull("expected_end_ms") else put("expected_end_ms", expectedEndMs)
                put("updated_at_ms", nowMs)
            }
            db.update(
                SnapshotSqlite.SESSIONS_TABLE,
                cv,
                "id = ? AND deleted_at_ms IS NULL",
                arrayOf(existingId.toString())
            )
            changed = true
            existingId
        } else {
            val cv = android.content.ContentValues().apply {
                put("title", title)
                put("start_ms", startMs)
                putNull("end_ms")
                if (expectedEndMs == null) putNull("expected_end_ms") else put("expected_end_ms", expectedEndMs)
                put("created_at_ms", startMs)
                put("updated_at_ms", nowMs)
                putNull("deleted_at_ms")
            }
            changed = true
            db.insertOrThrow(SnapshotSqlite.SESSIONS_TABLE, null, cv)
        }

        // Replace tags for this running session (cheap; tag set is small).
        db.delete(SnapshotSqlite.SESSION_TAGS_TABLE, "session_id = ?", arrayOf(sessionId.toString()))
        changed = true
        for (tagId in tagIds) {
            val tagCv = android.content.ContentValues().apply {
                put("session_id", sessionId)
                put("tag_id", tagId)
            }
            db.insertWithOnConflict(
                SnapshotSqlite.SESSION_TAGS_TABLE,
                null,
                tagCv,
                com.gernalix.personalhub.core.database.LegacyDatabase.CONFLICT_IGNORE
            )
        }

        db.setTransactionSuccessful()
        resultSessionId = sessionId
    } finally {
        db.endTransaction()
        db.close()
    }
    if (changed) PersistentMutationTracker.record(context, "SessionRepository.insertRunningSessionIfMissing")
    return resultSessionId
}

/* FEATURE CAPSULE: Start Session (Repository) — START */
// Repository-level entry point for “start session” persistence.
// Invariant: the ViewModel decides *when* to start; the repository only ensures DB rows exist.
@OptIn(CapsuleWriteApi::class)
fun ensureRunningSessionRow(
    title: String,
    startMs: Long,
    tagIds: Set<Long>,
    nowMs: Long
): Long {
    // Currently implemented via the proven HOTFIX helper. This wrapper exists so all callers
    // can converge on one name while we keep the internal DB strategy flexible.
    return insertRunningSessionIfMissing(
        title = title,
        startMs = startMs,
        tagIds = tagIds,
        nowMs = nowMs
    )
}
/* FEATURE CAPSULE: Start Session (Repository) — END */

/**
 * Soft-deletes the newest session row identified by [startMs] (best-effort).
 * Used as a bridge while NOW still calls legacy task APIs.
 */
fun softDeleteSessionByStart(startMs: Long, nowMs: Long) {
    CapsuleAudit.auditPersistenceWrite("SessionRepository.softDeleteSessionByStart")
    SnapshotSqlite.ensureSessionTables(context)
    val db = SnapshotSqlite.openWritableDb(context)
    db.beginTransaction()
    try {
        val sessionId = db.rawQuery(
            "SELECT id FROM ${SnapshotSqlite.SESSIONS_TABLE} WHERE start_ms=? AND deleted_at_ms IS NULL ORDER BY id DESC LIMIT 1",
            arrayOf(startMs.toString())
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

        if (sessionId != null) {
            val cv = android.content.ContentValues().apply {
                put("deleted_at_ms", nowMs)
                put("updated_at_ms", nowMs)
            }
            db.update(
                SnapshotSqlite.SESSIONS_TABLE,
                cv,
                "id = ? AND deleted_at_ms IS NULL",
                arrayOf(sessionId.toString())
            )
            // Keep join table small.
            db.delete(SnapshotSqlite.SESSION_TAGS_TABLE, "session_id = ?", arrayOf(sessionId.toString()))
        }

        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
        db.close()
    }
    PersistentMutationTracker.record(context, "SessionRepository.softDeleteSessionByStart")
}

/**
 * Closes a running session identified by [startMs] (best-effort).
 * If multiple sessions share the same startMs (unlikely), closes the newest (largest id).
 */
fun closeRunningSessionByStart(startMs: Long, endMs: Long, nowMs: Long) {
    CapsuleAudit.auditPersistenceWrite("SessionRepository.closeRunningSessionByStart")
    SnapshotSqlite.ensureSessionTables(context)
    val db = SnapshotSqlite.openWritableDb(context)
    db.beginTransaction()
    try {
        val sessionId = db.rawQuery(
            "SELECT id FROM ${SnapshotSqlite.SESSIONS_TABLE} WHERE start_ms=? AND end_ms IS NULL AND deleted_at_ms IS NULL ORDER BY id DESC LIMIT 1",
            arrayOf(startMs.toString())
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

        if (sessionId != null) {
            val cv = android.content.ContentValues().apply {
                put("end_ms", endMs)
                put("updated_at_ms", nowMs)
            }
            db.update(
                SnapshotSqlite.SESSIONS_TABLE,
                cv,
                "id = ? AND deleted_at_ms IS NULL",
                arrayOf(sessionId.toString())
            )
        }
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
        db.close()
    }
    PersistentMutationTracker.record(context, "SessionRepository.closeRunningSessionByStart")
}

/**
 * Soft-deletes the session that matches the given time range (best-effort).
 * This is used to keep session tables consistent when legacy code auto-deletes very short sessions.
 */
fun softDeleteSessionByTimeRange(startMs: Long, endMs: Long, nowMs: Long) {
    CapsuleAudit.auditPersistenceWrite("SessionRepository.softDeleteSessionByTimeRange")
    SnapshotSqlite.ensureSessionTables(context)
    val db = SnapshotSqlite.openWritableDb(context)
    db.beginTransaction()
    try {
        val sessionId = db.rawQuery(
            "SELECT id FROM ${SnapshotSqlite.SESSIONS_TABLE} WHERE start_ms=? AND end_ms=? AND deleted_at_ms IS NULL ORDER BY id DESC LIMIT 1",
            arrayOf(startMs.toString(), endMs.toString())
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

        if (sessionId != null) {
            val cv = android.content.ContentValues().apply {
                put("deleted_at_ms", nowMs)
                put("updated_at_ms", nowMs)
            }
            db.update(
                SnapshotSqlite.SESSIONS_TABLE,
                cv,
                "id = ? AND deleted_at_ms IS NULL",
                arrayOf(sessionId.toString())
            )
        }

        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
        db.close()
    }
    PersistentMutationTracker.record(context, "SessionRepository.softDeleteSessionByTimeRange")
}


    private fun unionTotalMs(intervals: List<Pair<Long, Long>>): Long {
        if (intervals.isEmpty()) return 0L
        val sorted = intervals.sortedWith(compareBy({ it.first }, { it.second }))
        var total = 0L
        var curStart = sorted[0].first
        var curEnd = sorted[0].second
        for (i in 1 until sorted.size) {
            val (s, e) = sorted[i]
            if (s <= curEnd) {
                if (e > curEnd) curEnd = e
            } else {
                total += (curEnd - curStart).coerceAtLeast(0L)
                curStart = s
                curEnd = e
            }
        }
        total += (curEnd - curStart).coerceAtLeast(0L)
        return total
    }

    private data class SessionAccumulator(
        val id: Long,
        val title: String,
        val startMs: Long,
        val endMs: Long?,
        val expectedEndMs: Long?,
        val deletedAtMs: Long?,
        val tagIds: LinkedHashSet<Long> = LinkedHashSet()
    ) {
        fun toUi(): SessionUi = SessionUi(
            id = id,
            title = title,
            startMs = startMs,
            endMs = endMs,
            expectedEndMs = expectedEndMs,
            tagIds = tagIds.toSet(),
            deletedAtMs = deletedAtMs
        )
    }

    private fun computeExpectedEndMs(tagIds: Set<Long>, startMs: Long): Long? {
        val tags = SnapshotStore.load(context)?.tags.orEmpty()
        return requireTimedSessionExpectation(
            tagIds = tagIds,
            tags = tags,
            startMs = startMs,
        )
    }
}
