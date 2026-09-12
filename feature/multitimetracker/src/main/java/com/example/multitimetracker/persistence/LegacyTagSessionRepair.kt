package com.example.multitimetracker.persistence

import android.content.ContentValues
import android.content.Context
import com.example.multitimetracker.core.contracts.TaggedSessionRecord
import com.example.multitimetracker.util.CapsuleAudit
import com.example.multitimetracker.util.CapsuleWriteApi
import com.gernalix.personalhub.core.database.LegacyDatabase as SQLiteDatabase

/**
 * Repairs legacy snapshot tag/session edges that were omitted when session-only
 * tables were bootstrapped by old builds.
 *
 * Safety rules:
 * - additive only: never deletes sessions, tags, or session_tags;
 * - exact interval match is required;
 * - if multiple modern rows share an interval, an exact title match must make
 *   the mapping unique; otherwise the record is left untouched;
 * - only tag ids still present in the persisted snapshot are considered.
 *
 * The CriticalDataGuard remains authoritative. This repair restores the
 * missing modern relation instead of weakening the guard.
 */
internal object LegacyTagSessionRepair {
    data class Result(
        val examined: Int,
        val inserted: Int,
        val alreadyPresent: Int,
        val ambiguousOrMissingSession: Int,
        val missingTag: Int,
        val error: String? = null,
    ) {
        val changed: Boolean get() = inserted > 0
    }

    internal data class ClosedSessionRow(
        val id: Long,
        val title: String,
        val startMs: Long,
        val endMs: Long,
    )

    internal fun resolveModernSessionId(
        record: TaggedSessionRecord,
        candidates: List<ClosedSessionRow>,
    ): Long? {
        val intervalMatches = candidates.filter {
            it.startMs == record.startTs && it.endMs == record.endTs
        }
        if (intervalMatches.size == 1) return intervalMatches.single().id
        if (intervalMatches.isEmpty()) return null

        val wantedTitle = record.sessionTitle.trim()
        if (wantedTitle.isEmpty()) return null
        return intervalMatches
            .filter { it.title.trim() == wantedTitle }
            .singleOrNull()
            ?.id
    }

    @OptIn(CapsuleWriteApi::class)
    fun repairIfNeeded(context: Context): Result {
        val snapshot = runCatching { SnapshotStore.load(context) }.getOrNull()
            ?: return Result(0, 0, 0, 0, 0)
        val legacyRecords = snapshot.tagSessions.filter { it.endTs > it.startTs }
        if (legacyRecords.isEmpty()) return Result(0, 0, 0, 0, 0)

        val validTagIds = snapshot.tags.asSequence().map { it.id }.toHashSet()
        if (validTagIds.isEmpty()) {
            return Result(
                examined = legacyRecords.size,
                inserted = 0,
                alreadyPresent = 0,
                ambiguousOrMissingSession = 0,
                missingTag = legacyRecords.size,
            )
        }

        return runCatching {
            SnapshotSqlite.ensureSessionTables(context)
            CapsuleAudit.auditPersistenceWrite("LegacyTagSessionRepair.repairIfNeeded")
            val db = SQLiteDatabase.get(context)
            db.beginTransaction()
            try {
                val closedRows = ArrayList<ClosedSessionRow>()
                db.rawQuery(
                    "SELECT id, title, start_ms, end_ms FROM ${SnapshotSqlite.SESSIONS_TABLE} " +
                        "WHERE deleted_at_ms IS NULL AND end_ms IS NOT NULL",
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        closedRows += ClosedSessionRow(
                            id = cursor.getLong(0),
                            title = cursor.getString(1) ?: "",
                            startMs = cursor.getLong(2),
                            endMs = cursor.getLong(3),
                        )
                    }
                }

                if (closedRows.isEmpty()) {
                    return@runCatching Result(
                        examined = legacyRecords.size,
                        inserted = 0,
                        alreadyPresent = 0,
                        ambiguousOrMissingSession = legacyRecords.size,
                        missingTag = 0,
                    )
                }

                val rowsByInterval = closedRows.groupBy { it.startMs to it.endMs }
                val existingEdges = HashSet<Pair<Long, Long>>()
                db.rawQuery(
                    "SELECT session_id, tag_id FROM ${SnapshotSqlite.SESSION_TAGS_TABLE}",
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        existingEdges += cursor.getLong(0) to cursor.getLong(1)
                    }
                }

                var inserted = 0
                var alreadyPresent = 0
                var ambiguousOrMissingSession = 0
                var missingTag = 0

                for (record in legacyRecords) {
                    if (record.tagId !in validTagIds) {
                        missingTag += 1
                        continue
                    }
                    val intervalCandidates = rowsByInterval[record.startTs to record.endTs].orEmpty()
                    val modernSessionId = resolveModernSessionId(record, intervalCandidates)
                    if (modernSessionId == null) {
                        ambiguousOrMissingSession += 1
                        continue
                    }

                    val edge = modernSessionId to record.tagId
                    if (edge in existingEdges) {
                        alreadyPresent += 1
                        continue
                    }

                    val values = ContentValues().apply {
                        put("session_id", modernSessionId)
                        put("tag_id", record.tagId)
                    }
                    val rowId = db.insertWithOnConflict(
                        SnapshotSqlite.SESSION_TAGS_TABLE,
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_IGNORE,
                    )
                    if (rowId == -1L) {
                        alreadyPresent += 1
                    } else {
                        existingEdges += edge
                        inserted += 1
                    }
                }

                db.setTransactionSuccessful()
                Result(
                    examined = legacyRecords.size,
                    inserted = inserted,
                    alreadyPresent = alreadyPresent,
                    ambiguousOrMissingSession = ambiguousOrMissingSession,
                    missingTag = missingTag,
                )
            } finally {
                db.endTransaction()
                db.close()
            }
        }.getOrElse { error ->
            Result(
                examined = legacyRecords.size,
                inserted = 0,
                alreadyPresent = 0,
                ambiguousOrMissingSession = 0,
                missingTag = 0,
                error = error.message ?: error::class.java.simpleName,
            )
        }
    }
}
