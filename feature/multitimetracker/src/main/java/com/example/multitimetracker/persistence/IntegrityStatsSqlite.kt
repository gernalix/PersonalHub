// v471
// v315
package com.example.multitimetracker.persistence

import android.content.ContentValues
import android.content.Context
import com.gernalix.personalhub.core.database.LegacyDatabase as SQLiteDatabase
import org.json.JSONObject
import java.io.File

/**
 * Persistence integrity stats.
 *
 * Strategy:
 * - Keep a single-row table (id=1) with deterministic counters derived from the DB + snapshot JSON.
 * - Before exporting the DB vault copy, refresh this row so the exported file is self-describing.
 * - After importing a DB, compare expected vs actual and report mismatches.
 */
object IntegrityStatsSqlite {

    private const val TABLE = "integrity_stats"

    data class Stats(
        val sessions: Long,
        val sessionTags: Long,
        val tasks: Long,
        val tags: Long,
        val closedSessions: Long,
        val tagSessions: Long,
        val timeFenceRules: Long,
        val tagParents: Long,
        val lifePeriods: Long,
        val chains: Long,
        val totalClosedSessionMs: Long,
        val runningSessions: Long,
        val computedAtMs: Long
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("sessions", sessions)
            .put("sessionTags", sessionTags)
            .put("tasks", tasks)
            .put("tags", tags)
            .put("closedSessions", closedSessions)
            .put("tagSessions", tagSessions)
            .put("timeFenceRules", timeFenceRules)
            .put("tagParents", tagParents)
            .put("lifePeriods", lifePeriods)
            .put("chains", chains)
            .put("totalClosedSessionMs", totalClosedSessionMs)
            .put("runningSessions", runningSessions)
            .put("computedAtMs", computedAtMs)

        companion object {
            fun fromJson(obj: JSONObject): Stats = Stats(
                sessions = obj.optLong("sessions", -1L),
                sessionTags = obj.optLong("sessionTags", -1L),
                tasks = obj.optLong("tasks", -1L),
                tags = obj.optLong("tags", -1L),
                closedSessions = obj.optLong("closedSessions", -1L),
                tagSessions = obj.optLong("tagSessions", -1L),
                timeFenceRules = obj.optLong("timeFenceRules", -1L),
                tagParents = obj.optLong("tagParents", -1L),
                lifePeriods = obj.optLong("lifePeriods", 0L),
                chains = obj.optLong("chains", -1L),
                totalClosedSessionMs = obj.optLong("totalClosedSessionMs", -1L),
                runningSessions = obj.optLong("runningSessions", -1L),
                computedAtMs = obj.optLong("computedAtMs", 0L)
            )
        }
    }

    fun ensureTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE (
              id INTEGER PRIMARY KEY CHECK(id = 1),
              json TEXT NOT NULL,
              computed_at_ms INTEGER NOT NULL
            );
            """.trimIndent()
        )
    }

    /** Recompute and store stats into the INTERNAL DB (best effort). */
    fun refreshInternal(context: Context): Stats {
        val db = SnapshotSqlite.openWritableDb(context)
        try {
            ensureTable(db)
            val stats = computeFromOpenDb(db)
            writeOpenDb(db, stats)
            return stats
        } finally {
            db.close()
        }
    }

    /** Compute stats from INTERNAL DB without mutating it. */
    fun computeInternal(context: Context): Stats {
        val db = SnapshotSqlite.openReadableDb(context)
        try {
            ensureTable(db)
            return computeFromOpenDb(db)
        } finally {
            db.close()
        }
    }

    /** Read expected stats from an arbitrary DB FILE (used during import before swapping). */
    fun readExpectedFromFile(dbFile: File): Stats {
        val db = SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
        try {
            return readExpectedFromOpenDb(db)
        } finally {
            db.close()
        }
    }

    internal fun readExpectedFromOpenDb(db: SQLiteDatabase): Stats {
        val hasTable = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(TABLE)
        ).use { it.moveToFirst() }
        if (hasTable) {
            db.rawQuery("SELECT json FROM $TABLE WHERE id=1", null).use { c ->
                if (c.moveToFirst()) {
                    val json = c.getString(0)
                    return Stats.fromJson(JSONObject(json))
                }
            }
        }
        return computeFromOpenDb(db)
    }

    private fun writeOpenDb(db: SQLiteDatabase, stats: Stats) {
        val cv = ContentValues().apply {
            put("id", 1)
            put("json", stats.toJson().toString())
            put("computed_at_ms", stats.computedAtMs)
        }
        db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun computeFromOpenDb(db: SQLiteDatabase): Stats {
        val now = System.currentTimeMillis()

        fun longQuery(sql: String, args: Array<String>? = null): Long {
            return db.rawQuery(sql, args).use { c ->
                if (!c.moveToFirst()) 0L else c.getLong(0)
            }
        }

        val sessions = longQuery(
            "SELECT COUNT(*) FROM ${SnapshotSqlite.SESSIONS_TABLE} WHERE deleted_at_ms IS NULL"
        )
        val running = longQuery(
            "SELECT COUNT(*) FROM ${SnapshotSqlite.SESSIONS_TABLE} WHERE deleted_at_ms IS NULL AND end_ms IS NULL"
        )
        val sessionTags = longQuery("SELECT COUNT(*) FROM ${SnapshotSqlite.SESSION_TAGS_TABLE}")

        val totalClosedMs = db.rawQuery(
            "SELECT start_ms, end_ms FROM ${SnapshotSqlite.SESSIONS_TABLE} WHERE deleted_at_ms IS NULL AND end_ms IS NOT NULL",
            null
        ).use { c ->
            var sum = 0L
            while (c.moveToNext()) {
                val s = c.getLong(0)
                val e = c.getLong(1)
                val d = e - s
                if (d > 0) sum += d
            }
            sum
        }

        // Snapshot JSON counts (tasks/tags/etc.) - safe defaults on parse error.
        val snapshotJson = runCatching {
            db.rawQuery("SELECT json FROM snapshot WHERE id=1", null).use { c ->
                if (!c.moveToFirst()) null else c.getString(0)
            }
        }.getOrNull()

        var tasks = 0L
        var tags = 0L
        var closedSessions = 0L
        var tagSessions = 0L
        var timeFenceRules = 0L
        var tagParents = 0L
        var lifePeriods = 0L
        var chains = 0L

        if (!snapshotJson.isNullOrBlank()) {
            runCatching {
                val root = JSONObject(snapshotJson)
                tasks = root.optJSONArray("tasks")?.length()?.toLong() ?: 0L
                tags = root.optJSONArray("tags")?.length()?.toLong() ?: 0L
                closedSessions = root.optJSONArray("closedSessions")?.length()?.toLong() ?: 0L
                tagSessions = root.optJSONArray("tagSessions")?.length()?.toLong() ?: 0L
                timeFenceRules = root.optJSONArray("timeFenceRules")?.length()?.toLong() ?: 0L
                tagParents = root.optJSONArray("tagParents")?.length()?.toLong() ?: 0L
                lifePeriods = root.optJSONArray("lifePeriods")?.length()?.toLong() ?: 0L
                chains = root.optJSONArray("chains")?.length()?.toLong() ?: 0L
            }
        }

        return Stats(
            sessions = sessions,
            sessionTags = sessionTags,
            tasks = tasks,
            tags = tags,
            closedSessions = closedSessions,
            tagSessions = tagSessions,
            timeFenceRules = timeFenceRules,
            tagParents = tagParents,
            lifePeriods = lifePeriods,
            chains = chains,
            totalClosedSessionMs = totalClosedMs,
            runningSessions = running,
            computedAtMs = now
        )
    }
}

