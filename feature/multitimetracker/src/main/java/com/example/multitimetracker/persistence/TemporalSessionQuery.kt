package com.example.multitimetracker.persistence

import android.content.Context
import com.example.multitimetracker.model.SessionUi

/** Bounded keyset reader used by the Hub temporal capsule through SessionCore. */
internal class TemporalSessionQuery(private val context: Context) {
    fun read(fromMs: Long, toMs: Long, limit: Int, cursorStartMs: Long?, cursorId: Long?): List<SessionUi> {
        require(fromMs < toMs)
        require(limit in 1..201)
        SnapshotSqlite.ensureSessionTables(context)
        val db = SnapshotSqlite.openReadableDb(context)
        try {
            val args = mutableListOf(toMs.toString(), fromMs.toString())
            val cursorClause = if (cursorStartMs != null && cursorId != null) {
                args += cursorStartMs.toString()
                args += cursorStartMs.toString()
                args += cursorId.toString()
                "AND (start_ms < ? OR (start_ms = ? AND id < ?))"
            } else ""
            args += limit.toString()
            val sql = """
                SELECT id,title,start_ms,end_ms,expected_end_ms,deleted_at_ms
                FROM ${SnapshotSqlite.SESSIONS_TABLE}
                WHERE deleted_at_ms IS NULL
                  AND start_ms < ?
                  AND (end_ms IS NULL OR end_ms > ?)
                  $cursorClause
                ORDER BY start_ms DESC,id DESC
                LIMIT ?
            """.trimIndent()
            return db.rawQuery(sql, args.toTypedArray()).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            SessionUi(
                                id = c.getLong(0),
                                title = c.getString(1),
                                startMs = c.getLong(2),
                                endMs = if (c.isNull(3)) null else c.getLong(3),
                                expectedEndMs = if (c.isNull(4)) null else c.getLong(4),
                                tagIds = emptySet(),
                                deletedAtMs = if (c.isNull(5)) null else c.getLong(5),
                            ),
                        )
                    }
                }
            }
        } finally {
            db.close()
        }
    }
}
