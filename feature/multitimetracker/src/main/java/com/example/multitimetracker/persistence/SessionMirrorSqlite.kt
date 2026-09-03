// v471
package com.example.multitimetracker.persistence

import android.content.ContentValues
import android.content.Context
import com.gernalix.personalhub.core.database.LegacyDatabase as SQLiteDatabase
import com.example.multitimetracker.core.contracts.ClosedSessionRecord
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.Task
import com.example.multitimetracker.util.CapsuleAudit
import com.example.multitimetracker.util.CapsuleWriteApi

/**
 * SESSION-ONLY MIGRATION (PHASE 1)
 *
 * Mirrors the current snapshot (tasks + closedSessions) into the new session-only tables:
 * - sessions
 * - session_tags
 *
 * This does NOT change the app runtime model yet; it only keeps a consistent shadow copy
 * in SQLite so we can switch readers gradually without data loss.
 */
/**
 * Internal persistence helper.
 *
 * Architectural rule: call sites must go through SessionMirrorCore (bridge) rather than invoking
 * this object directly.
 */
internal object SessionMirrorSqlite {

    private class Helper(private val context: Context) {
        val readableDatabase get() = SQLiteDatabase.get(context)
        val writableDatabase get() = SQLiteDatabase.get(context)
    }

    private fun helper(context: Context): Helper = Helper(context.applicationContext)

    
internal data class SessionStats(
    val intervals: List<Pair<Long, Long>>,
    val sessionsTotal: Long,
    val runningTotal: Long,
    val deletedTotal: Long
)

/**
 * Session-only authoritative reader for high-level stats.
 *
 * Reads directly from `sessions` (and `deleted_at_ms`) so UI totals do not depend on legacy engine tables.
 */
fun readSessionStats(
    context: Context,
    nowMs: Long
): SessionStats {
    // Ensure schema exists (idempotent) before querying.
    runCatching { SnapshotSqlite.ensureSessionTables(context) }

    val db = runCatching {
        SQLiteDatabase.get(context)
    }.getOrNull() ?: return SessionStats(emptyList(), -1, -1, -1)

    db.use { sqlDb ->
        val intervals = mutableListOf<Pair<Long, Long>>()

        // Non-deleted sessions only.
        runCatching {
            sqlDb.rawQuery(
                "SELECT start_ms, COALESCE(end_ms, ?) FROM sessions WHERE deleted_at_ms IS NULL",
                arrayOf(nowMs.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val s = c.getLong(0)
                    val e = c.getLong(1)
                    if (e > s) intervals.add(s to e)
                }
            }
        }

        fun count(q: String, args: Array<String>? = null): Long {
            return runCatching {
                sqlDb.rawQuery(q, args).use { c ->
                    c.moveToFirst()
                    c.getLong(0)
                }
            }.getOrElse { -1L }
        }

        val sessionsTotal = count("SELECT COUNT(*) FROM sessions WHERE deleted_at_ms IS NULL")
        val runningTotal = count("SELECT COUNT(*) FROM sessions WHERE end_ms IS NULL AND deleted_at_ms IS NULL")
        val deletedTotal = count("SELECT COUNT(*) FROM sessions WHERE deleted_at_ms IS NOT NULL")
        return SessionStats(intervals = intervals, sessionsTotal = sessionsTotal, runningTotal = runningTotal, deletedTotal = deletedTotal)
    }
}

@CapsuleWriteApi
fun mirrorFromSnapshot(
        context: Context,
        tasks: List<Task>,
        tags: List<Tag>,
        closedSessions: List<ClosedSessionRecord>,
        nowMs: Long
    ) {
        // v249: expand capsule boundary self-check coverage for session table mirroring.
        CapsuleAudit.auditPersistenceWrite("SessionMirrorSqlite.mirrorFromSnapshot")
        val tagNameById = tags.associateBy({ it.id }, { it.name })
        val taskById = tasks.associateBy { it.id }

        val db = helper(context).writableDatabase
        db.beginTransaction()
        try {
            // Replace-all strategy: simplest & safest for phase 1.
            db.delete(SnapshotSqlite.SESSION_TAGS_TABLE, null, null)
            db.delete(SnapshotSqlite.SESSIONS_TABLE, null, null)

            // 1) Closed sessions (from closedSessions)
            for (s in closedSessions) {
                val t = taskById[s.sessionId] ?: continue

                val start = s.startTs
                val end = s.endTs
                if (end <= start) continue

                val title = buildSessionTitle(task = t, tagNameById = tagNameById)
                val deletedAt = t.deletedAtMs
                val updatedAt = end

                val sessionId = insertSessionRow(
                    db = db,
                    title = title,
                    startMs = start,
                    endMs = end,
                    createdAtMs = start,
                    updatedAtMs = updatedAt,
                    deletedAtMs = deletedAt
                )

                insertSessionTags(db, sessionId, t.tagIds)
            }

            // 2) Running sessions (derived from currently running tasks)
            for (t in tasks) {
                if (!t.isRunning) continue
                val start = t.lastStartedAtMs ?: continue
                val title = buildSessionTitle(task = t, tagNameById = tagNameById)
                val deletedAt = t.deletedAtMs

                val sessionId = insertSessionRow(
                    db = db,
                    title = title,
                    startMs = start,
                    endMs = null,
                    createdAtMs = start,
                    updatedAtMs = nowMs,
                    deletedAtMs = deletedAt
                )
                insertSessionTags(db, sessionId, t.tagIds)
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    private fun insertSessionRow(
        db: SQLiteDatabase,
        title: String,
        startMs: Long,
        endMs: Long?,
        createdAtMs: Long,
        updatedAtMs: Long,
        deletedAtMs: Long?
    ): Long {
        val cv = ContentValues().apply {
            // id omitted => auto rowid
            put("title", title)
            put("start_ms", startMs)
            if (endMs == null) putNull("end_ms") else put("end_ms", endMs)
            put("created_at_ms", createdAtMs)
            put("updated_at_ms", updatedAtMs)
            if (deletedAtMs == null) putNull("deleted_at_ms") else put("deleted_at_ms", deletedAtMs)
        }
        return db.insertOrThrow(SnapshotSqlite.SESSIONS_TABLE, null, cv)
    }

    private fun insertSessionTags(db: SQLiteDatabase, sessionId: Long, tagIds: Set<Long>) {
        for (tagId in tagIds) {
            val cv = ContentValues().apply {
                put("session_id", sessionId)
                put("tag_id", tagId)
            }
            db.insertWithOnConflict(
                SnapshotSqlite.SESSION_TAGS_TABLE,
                null,
                cv,
                SQLiteDatabase.CONFLICT_IGNORE
            )
        }
    }

    

data class TagTotalsComparisonRow(
    val tagId: Long,
    val tagName: String,
    val engineTotalMs: Long,
    val sessionTableTotalMs: Long
) {
    val deltaMs: Long get() = sessionTableTotalMs - engineTotalMs
}

/**
 * Diagnostics helper: compares tag totals computed from the in-memory snapshot (engine view)
 * vs totals computed from the mirrored session-only tables (sessions + session_tags).
 *
 * Both sides exclude deleted tasks/sessions (deleted_at_ms != null).
 * Running intervals use [nowMs] as effective end.
 */
fun compareTagTotals(
    context: Context,
    tasks: List<Task>,
    tags: List<Tag>,
    closedSessions: List<ClosedSessionRecord>,
    nowMs: Long
): List<TagTotalsComparisonRow> {
    val deletedTaskIds = tasks.asSequence().filter { it.isDeleted }.map { it.id }.toSet()
    val taskById = tasks.associateBy { it.id }

    // 1) Engine totals (from snapshot tasks + closedSessions)
    val engineIntervalsByTag = HashMap<Long, MutableList<Pair<Long, Long>>>()

    for (s in closedSessions) {
        if (s.sessionId in deletedTaskIds) continue
        val t = taskById[s.sessionId] ?: continue
        val start = s.startTs
        val end = s.endTs
        if (end <= start) continue
        for (tagId in t.tagIds) {
            engineIntervalsByTag.getOrPut(tagId) { mutableListOf() }.add(start to end)
        }
    }

    for (t in tasks) {
        if (t.isDeleted) continue
        if (!t.isRunning) continue
        val start = t.lastStartedAtMs ?: continue
        val end = nowMs
        if (end <= start) continue
        for (tagId in t.tagIds) {
            engineIntervalsByTag.getOrPut(tagId) { mutableListOf() }.add(start to end)
        }
    }

    val engineTotalByTag = engineIntervalsByTag.mapValues { (_, intervals) -> unionTotalMs(intervals) }

    // 2) Session table totals (from mirrored sessions + session_tags)
    val sessionTableTotalByTag = HashMap<Long, Long>()
    val db = SnapshotSqlite.openReadableDb(context)
    try {
        val sql = """
            SELECT s.start_ms, COALESCE(s.end_ms, ?) AS end_ms
            FROM ${SnapshotSqlite.SESSIONS_TABLE} s
            JOIN ${SnapshotSqlite.SESSION_TAGS_TABLE} st ON st.session_id = s.id
            WHERE st.tag_id = ? AND s.deleted_at_ms IS NULL
        """.trimIndent()

        for (tag in tags) {
            if (tag.isDeleted) continue
            val intervals = mutableListOf<Pair<Long, Long>>()
            val c = db.rawQuery(sql, arrayOf(nowMs.toString(), tag.id.toString()))
            c.use {
                while (it.moveToNext()) {
                    val start = it.getLong(0)
                    val end = it.getLong(1)
                    if (end > start) intervals.add(start to end)
                }
            }
            if (intervals.isNotEmpty()) {
                sessionTableTotalByTag[tag.id] = unionTotalMs(intervals)
            }
        }
    } finally {
        db.close()
    }

    // 3) Merge into rows (only non-deleted tags)
    return tags.asSequence()
        .filter { !it.isDeleted }
        .map { tag ->
            TagTotalsComparisonRow(
                tagId = tag.id,
                tagName = tag.name,
                engineTotalMs = engineTotalByTag[tag.id] ?: 0L,
                sessionTableTotalMs = sessionTableTotalByTag[tag.id] ?: 0L
            )
        }
        .toList()
        .sortedByDescending { kotlin.math.abs(it.deltaMs) }
}

fun buildTagTotalsComparisonReport(
    context: Context,
    tasks: List<Task>,
    tags: List<Tag>,
    closedSessions: List<ClosedSessionRecord>,
    nowMs: Long,
    maxRows: Int = 50
): String {
    val rows = compareTagTotals(context, tasks, tags, closedSessions, nowMs)
    val mismatches = rows.filter { it.deltaMs != 0L }
    val sb = StringBuilder()
    sb.appendLine("Tag totals comparison (engine vs session tables)")
    sb.appendLine("nowMs=$nowMs")
    sb.appendLine("rows=${rows.size} mismatches=${mismatches.size}")
    sb.appendLine()
    val top = mismatches.take(maxRows)
    if (top.isEmpty()) {
        sb.appendLine("No mismatches.")
        return sb.toString()
    }
    sb.appendLine("Top mismatches (sessionTable - engine):")
    for (r in top) {
        sb.appendLine("tagId=${r.tagId} name='${r.tagName}' engineMs=${r.engineTotalMs} sessionMs=${r.sessionTableTotalMs} deltaMs=${r.deltaMs}")
    }
    return sb.toString()
}



data class SessionOnlyTagTotalsRow(
    val tagId: Long,
    val tagName: String,
    val totalMs: Long,
    val sessionsCount: Int,
    val runningSessionsCount: Int
)

/**
 * Computes tag totals directly from session-only tables (sessions + session_tags).
 *
 * - Excludes deleted sessions (deleted_at_ms IS NULL).
 * - Running intervals (end_ms IS NULL) use [nowMs] as effective end.
 *
 * Note: totals use an interval union per tag to avoid double counting overlaps (rare but possible).
 */
fun computeSessionOnlyTagTotals(
    context: Context,
    tags: List<Tag>,
    nowMs: Long
): List<SessionOnlyTagTotalsRow> {
    val db = SnapshotSqlite.openReadableDb(context)
    try {
        val sql = """
            SELECT s.id, s.start_ms, s.end_ms
            FROM ${SnapshotSqlite.SESSIONS_TABLE} s
            JOIN ${SnapshotSqlite.SESSION_TAGS_TABLE} st ON st.session_id = s.id
            WHERE st.tag_id = ? AND s.deleted_at_ms IS NULL
        """.trimIndent()

        val out = mutableListOf<SessionOnlyTagTotalsRow>()
        for (tag in tags) {
            if (tag.isDeleted) continue
            val intervals = mutableListOf<Pair<Long, Long>>()
            val sessionIds = HashSet<Long>()
            val runningIds = HashSet<Long>()

            db.rawQuery(sql, arrayOf(tag.id.toString())).use { c ->
                while (c.moveToNext()) {
                    val sessionId = c.getLong(0)
                    val start = c.getLong(1)
                    val endOrNull = if (c.isNull(2)) null else c.getLong(2)
                    val endEff = endOrNull ?: nowMs
                    if (endEff > start) {
                        intervals.add(start to endEff)
                        sessionIds.add(sessionId)
                        if (endOrNull == null) runningIds.add(sessionId)
                    }
                }
            }

            out.add(
                SessionOnlyTagTotalsRow(
                    tagId = tag.id,
                    tagName = tag.name,
                    totalMs = unionTotalMs(intervals),
                    sessionsCount = sessionIds.size,
                    runningSessionsCount = runningIds.size
                )
            )
        }
        return out.sortedByDescending { it.totalMs }
    } finally {
        db.close()
    }
}

fun buildSessionOnlyTagTotalsReport(
    context: Context,
    tags: List<Tag>,
    nowMs: Long,
    maxRows: Int = 50,
    includeIntegrityReport: Boolean = true
): String {
    val rows = computeSessionOnlyTagTotals(context, tags, nowMs)
    val sb = StringBuilder()
    sb.appendLine("Tag totals (session-only tables)")
    sb.appendLine("nowMs=$nowMs")
    sb.appendLine("rows=${rows.size}")
    sb.appendLine()
    sb.appendLine("NOTE: in session-only mode the legacy engine/snapshot closedSessions are not authoritative.")
    sb.appendLine("      Use this report to validate mirrored session tables.")
    sb.appendLine()

    val top = rows.take(maxRows)
    for (r in top) {
        sb.appendLine("tagId=${r.tagId} name='${r.tagName}' totalMs=${r.totalMs} sessions=${r.sessionsCount} running=${r.runningSessionsCount}")
    }

    if (includeIntegrityReport) {
        // Append integrity report for additional context.
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine(buildDeveloperIntegrityReport(context = context, nowMs = nowMs))
    }

    return sb.toString()
}

/**
 * Unified entry point used by the UI diagnostics dialog.
 * - Task-mode: compare engine vs session tables.
 * - Session-only: avoid engine comparison and show session-table totals instead.
 */
fun buildDiagnosticsReport(
    context: Context,
    sessionOnlyModeEnabled: Boolean,
    tasks: List<Task>,
    tags: List<Tag>,
    closedSessions: List<ClosedSessionRecord>,
    nowMs: Long,
    maxRows: Int = 200
): String {
    // Always start from the session-tables integrity report (single source of truth).
    val sb = StringBuilder()
    runCatching {
        val dbFile = SnapshotSqlite.internalDbFile(context)
        val manual = UiPrefsStore.getLastManualExportMeta(context)
        val importMeta = UiPrefsStore.getLastImportMeta(context)
        val lastAuto = UiPrefsStore.getLastAutoExportMs(context)
        fun tableCount(db: com.gernalix.personalhub.core.database.LegacyDatabase, table: String): Long {
            val exists = db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
                arrayOf(table)
            ).use { c -> c.moveToFirst() }
            if (!exists) return 0L
            return db.rawQuery("SELECT COUNT(*) FROM $table", null).use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            }
        }

        sb.appendLine("Data diagnostics")
        sb.appendLine("appVersion=${com.example.multitimetracker.BuildConfig.VERSION_NAME} (${com.example.multitimetracker.BuildConfig.VERSION_CODE})")
        sb.appendLine("patchVersion=${com.example.multitimetracker.AppPatchVersion.current(context)}")
        sb.appendLine("dbVersion=${SnapshotSqlite.DB_VERSION}")
        sb.appendLine("dbPath=${dbFile.absolutePath}")
        sb.appendLine("dbBytes=${if (dbFile.exists()) dbFile.length() else 0L}")
        sb.appendLine("lastBackupAutoMs=$lastAuto")
        sb.appendLine("lastBackupManualZip=${manual.zipName.orEmpty()}")
        sb.appendLine("lastImportDb=${importMeta.dbFileName.orEmpty()}")
        SnapshotSqlite.openReadableDb(context).use { db ->
            sb.appendLine("sessionCount=${tableCount(db, "sessions")}")
            sb.appendLine("tagCount=${tags.size}")
            sb.appendLine("sessionTagsCount=${tableCount(db, "session_tags")}")
            sb.appendLine("quickEventsCount=${tableCount(db, "quick_event_entries")}")
            sb.appendLine("quickActionsCount=${tableCount(db, "quick_event_templates")}")
            sb.appendLine("macroCount=${tableCount(db, "quick_event_macros")}")
            sb.appendLine("auditEventsCount=${tableCount(db, "audit_events")}")
            sb.appendLine("snapshotHistoryCount=${tableCount(db, "snapshot_history")}")
        }
        sb.appendLine()
    }.onFailure {
        sb.appendLine("Data diagnostics unavailable: ${it.message ?: it::class.java.simpleName}")
        sb.appendLine()
    }
    sb.append(buildDeveloperIntegrityReport(context = context, nowMs = nowMs))

    // v271: show backup/import context to make drift reports actionable.
    runCatching {
        val exp = UiPrefsStore.getLastManualExportMeta(context)
        val imp = UiPrefsStore.getLastImportMeta(context)
        if (!exp.zipName.isNullOrBlank() || !imp.dbFileName.isNullOrBlank()) {
            sb.appendLine()
            sb.appendLine()
            sb.appendLine("Backup/Import context (UI prefs)")
            if (!exp.zipName.isNullOrBlank()) {
                sb.appendLine("lastExportZip=${exp.zipName}")
                if (!exp.zipSha256.isNullOrBlank()) sb.appendLine("lastExportZipSha256=${exp.zipSha256}")
                if (!exp.backupSignature.isNullOrBlank()) sb.appendLine("lastExportSignature=${exp.backupSignature}")
            }
            if (!imp.dbFileName.isNullOrBlank()) {
                sb.appendLine("lastImportDb=${imp.dbFileName}")
                if (!imp.dbSha256.isNullOrBlank()) sb.appendLine("lastImportDbSha256=${imp.dbSha256}")
                if (!imp.beforeSignature.isNullOrBlank()) sb.appendLine("lastImportBeforeSignature=${imp.beforeSignature}")
                if (!imp.afterSignature.isNullOrBlank()) sb.appendLine("lastImportAfterSignature=${imp.afterSignature}")
            }
        }
    }

    sb.appendLine()
    sb.appendLine()
    sb.appendLine("Session-only tag totals (from session tables)")
    sb.appendLine("nowMs=$nowMs")
    sb.appendLine(
        buildSessionOnlyTagTotalsReport(
            context = context,
            tags = tags,
            nowMs = nowMs,
            maxRows = maxRows,
            includeIntegrityReport = false
        )
    )


    sb.appendLine()
    sb.appendLine()
    sb.appendLine("NOW/Chronology sync counters (SyncInvariants)")
    val sync = SyncCountersStore.readSnapshot(context = context)
    sb.appendLine("total=${sync.total} firstMs=${sync.firstMs}")
    for (e in sync.entries) {
        sb.appendLine("${e.action}: count=${e.count} lastMs=${e.lastMs}")
    }
    // When session-only mode is disabled, we *may* include a drift comparison vs the legacy engine snapshot.
    // If the engine snapshot lists are empty (common in modern session-only usage), the comparison is meaningless and
    // would show 0ms for "engine" totals. In that case, we skip with an explicit note to avoid false alarms.
    if (!sessionOnlyModeEnabled) {
        val hasEngineSnapshot = tasks.isNotEmpty() || closedSessions.isNotEmpty()
        if (hasEngineSnapshot) {
            sb.appendLine()
            sb.appendLine()
            sb.appendLine("Snapshot drift check (engine snapshot vs session tables)")
            sb.appendLine(
                buildTagTotalsComparisonReport(
                    context = context,
                    tasks = tasks,
                    tags = tags,
                    closedSessions = closedSessions,
                    nowMs = nowMs,
                    maxRows = maxRows
                )
            )
        } else {
            sb.appendLine()
            sb.appendLine()
            sb.appendLine("Snapshot drift check skipped: engine snapshot lists are empty (tasks/closedSessions).")
        }
    }

    // v219: Capsule boundary self-check (debug-only)
    val bypass = CapsuleAudit.dumpRecentWriteBypass()
    if (bypass.isNotEmpty()) {
        sb.appendLine()
        sb.appendLine()
        sb.appendLine("Capsule boundary self-check (recent write bypass warnings)")
        sb.appendLine("count=${bypass.size}")
        for (line in bypass.takeLast(50)) {
            sb.appendLine(line)
        }
    }

    return sb.toString()
}
private fun unionTotalMs(intervals: List<Pair<Long, Long>>): Long {
    if (intervals.isEmpty()) return 0L
    val sorted = intervals.sortedBy { it.first }
    var total = 0L
    var curStart = sorted[0].first
    var curEnd = sorted[0].second
    for (i in 1 until sorted.size) {
        val (s, e) = sorted[i]
        if (e <= s) continue
        if (s <= curEnd) {
            if (e > curEnd) curEnd = e
        } else {
            total += (curEnd - curStart)
            curStart = s
            curEnd = e
        }
    }
    total += (curEnd - curStart)
    return total.coerceAtLeast(0L)
}

private fun buildSessionTitle(task: Task, tagNameById: Map<Long, String>): String {
        val raw = task.name.trim()
        if (raw.isNotEmpty()) return raw
        val tagNames = task.tagIds.mapNotNull { tagNameById[it] }
        return tagNames.joinToString(" · ").ifBlank { "" }
    }

fun buildDeveloperIntegrityReport(
    context: Context,
    nowMs: Long
): String {
    // Keep this report resilient: never throw, always return a readable string.
    val sb = StringBuilder()
    sb.appendLine("Session tables integrity report")
    sb.appendLine("nowMs=$nowMs")

    // 1) Ensure schema exists (idempotent)
    runCatching {
        SnapshotSqlite.ensureSessionTables(context)
    }.onFailure {
        sb.appendLine("ERROR: ensureSessionTables failed: ${it.message}")
        return sb.toString()
    }

    val db = runCatching { SQLiteDatabase.get(context) }.getOrNull()
    if (db == null) {
        sb.appendLine("ERROR: cannot open db")
        return sb.toString()
    }

    db.use { sqlDb ->
        fun tableExists(name: String): Boolean {
            return runCatching {
                sqlDb.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
                    arrayOf(name)
                ).use { c -> c.moveToFirst() }
            }.getOrDefault(false)
        }

        val hasSessions = tableExists("sessions")
        val hasSessionTags = tableExists("session_tags")
        sb.appendLine("hasSessions=$hasSessions hasSessionTags=$hasSessionTags")

        if (!hasSessions || !hasSessionTags) {
            sb.appendLine("ERROR: missing required tables")
            return sb.toString()
        }

        val sessionsCount = runCatching {
            sqlDb.rawQuery("SELECT COUNT(*) FROM sessions", null).use { c ->
                c.moveToFirst()
                c.getLong(0)
            }
        }.getOrElse { -1L }

        val sessionTagsCount = runCatching {
            sqlDb.rawQuery("SELECT COUNT(*) FROM session_tags", null).use { c ->
                c.moveToFirst()
                c.getLong(0)
            }
        }.getOrElse { -1L }

        val runningCount = runCatching {
            sqlDb.rawQuery("SELECT COUNT(*) FROM sessions WHERE end_ms IS NULL AND deleted_at_ms IS NULL", null).use { c ->
                c.moveToFirst()
                c.getLong(0)
            }
        }.getOrElse { -1L }

        sb.appendLine("sessions=$sessionsCount session_tags=$sessionTagsCount running=$runningCount")

        val badIntervalCount = runCatching {
            sqlDb.rawQuery(
                "SELECT COUNT(*) FROM sessions WHERE end_ms IS NOT NULL AND end_ms < start_ms AND deleted_at_ms IS NULL",
                null
            ).use { c ->
                c.moveToFirst()
                c.getLong(0)
            }
        }.getOrElse { -1L }

        if (badIntervalCount > 0L) {
            sb.appendLine("WARN: sessions with end_ms < start_ms: $badIntervalCount")
        }

        val orphanTags = runCatching {
            sqlDb.rawQuery(
                "SELECT COUNT(*) FROM session_tags st LEFT JOIN sessions s ON s.id = st.session_id WHERE s.id IS NULL",
                null
            ).use { c ->
                c.moveToFirst()
                c.getLong(0)
            }
        }.getOrElse { -1L }

        if (orphanTags > 0L) {
            sb.appendLine("WARN: orphan session_tags rows (missing session): $orphanTags")
        }

        val dupEdges = runCatching {
            sqlDb.rawQuery(
                "SELECT COUNT(*) FROM (SELECT session_id, tag_id, COUNT(*) c FROM session_tags GROUP BY session_id, tag_id HAVING c > 1)",
                null
            ).use { c ->
                c.moveToFirst()
                c.getLong(0)
            }
        }.getOrElse { -1L }

        if (dupEdges > 0L) {
            sb.appendLine("WARN: duplicate (session_id, tag_id) edges: $dupEdges")
        }

        // v271: actionable suggestions (never auto-mutate the DB from diagnostics).
        if (badIntervalCount > 0L || orphanTags > 0L || dupEdges > 0L) {
            sb.appendLine()
            sb.appendLine("Suggested investigation queries (read-only)")
            if (badIntervalCount > 0L) {
                sb.appendLine("- Sessions with end_ms < start_ms:")
                sb.appendLine("  SELECT id, start_ms, end_ms, updated_at_ms FROM sessions WHERE end_ms IS NOT NULL AND end_ms < start_ms AND deleted_at_ms IS NULL ORDER BY updated_at_ms DESC LIMIT 50;")
            }
            if (orphanTags > 0L) {
                sb.appendLine("- Orphan session_tags rows:")
                sb.appendLine("  SELECT st.session_id, st.tag_id FROM session_tags st LEFT JOIN sessions s ON s.id = st.session_id WHERE s.id IS NULL LIMIT 50;")
            }
            if (dupEdges > 0L) {
                sb.appendLine("- Duplicate (session_id, tag_id) edges:")
                sb.appendLine("  SELECT session_id, tag_id, COUNT(*) c FROM session_tags GROUP BY session_id, tag_id HAVING c > 1 ORDER BY c DESC LIMIT 50;")
            }
            sb.appendLine("NOTE: fixes should be done via app-level rebuild/migration, not manual SQL, unless you are explicitly doing dev recovery.")
        }
    }

    // 2) Compare totals (engine vs session tables) from the persisted snapshot
    val snap = runCatching { SnapshotStore.load(context) }.getOrNull()
    if (snap == null) {
        sb.appendLine("WARN: SnapshotStore.load returned null; cannot compare totals.")
        return sb.toString()
    }

    val rows = runCatching {
        compareTagTotals(
            context = context,
            tasks = snap.tasks,
            tags = snap.tags,
            closedSessions = snap.closedSessions,
            nowMs = nowMs
        )
    }.getOrElse { err ->
        sb.appendLine("ERROR: compareTagTotals failed: ${err.message}")
        return sb.toString()
    }

    val mismatches = rows.count { it.deltaMs != 0L }
    sb.appendLine("")
    sb.appendLine("Tag totals comparison (engine vs session tables)")
    sb.appendLine("rows=${rows.size} mismatches=$mismatches")
    if (mismatches == 0) {
        sb.appendLine("")
        sb.appendLine("No mismatches.")
        return sb.toString()
    }

    sb.appendLine("")
    sb.appendLine("Top mismatches (abs diff ms):")
    rows.asSequence()
        .filter { it.deltaMs != 0L }
        .sortedByDescending { kotlin.math.abs(it.deltaMs) }
        .take(15)
        .forEach { r ->
            sb.appendLine("tagId=${r.tagId} '${r.tagName}': engine=${r.engineTotalMs} sessionTables=${r.sessionTableTotalMs} diff=${r.deltaMs}")
        }

    return sb.toString()
}

}


