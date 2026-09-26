package com.gernalix.personalhub.core.database.capsules.gitdata

import androidx.sqlite.db.SupportSQLiteDatabase
import com.gernalix.personalhub.core.database.capsules.sync.SyncJournal
import org.json.JSONObject

data class GitHistoryCommitMeta(
    val id: String,
    val historyPath: String,
    val changedColumns: String,
)

data class GitHistoryItem(
    val id: String,
    val occurredAt: Long,
    val author: String,
    val source: String,
    val reason: String?,
    val groupId: String?,
    val table: String,
    val operation: String,
    val rowKey: String,
    val changedColumns: String,
    val historyPath: String,
    val commitSha: String,
    val revertedBy: String?,
    val displayBefore: String? = null,
    val displayAfter: String? = null,
)

data class GitHistoryCount(val key: String, val count: Long)

/**
 * Compact, disposable local projection of immutable Git history.
 *
 * Full before/after payloads live in Git. This table exists only to keep History, blame and
 * statistics instant on-device; it can be rebuilt from the history JSONL files without data loss.
 */
object GitHistoryStore {
    const val TABLE = "hub_git_history_index"
    const val FIELD_STATS_TABLE = "hub_git_history_field_stats"

    fun install(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `" + TABLE + "` (" +
                "`id` TEXT NOT NULL PRIMARY KEY, `occurred_at` INTEGER NOT NULL, " +
                "`author` TEXT NOT NULL, `source` TEXT NOT NULL DEFAULT 'unknown', `reason` TEXT, `group_id` TEXT, `table_name` TEXT NOT NULL, " +
                "`operation` TEXT NOT NULL, `row_key` TEXT NOT NULL, " +
                "`changed_columns` TEXT NOT NULL, `history_path` TEXT NOT NULL, " +
                "`commit_sha` TEXT NOT NULL, `reverted_by` TEXT, `display_before` TEXT, `display_after` TEXT)",
        )
        ensureColumn(db, "source", "TEXT NOT NULL DEFAULT 'unknown'")
        ensureColumn(db, "reason", "TEXT")
        ensureColumn(db, "display_before", "TEXT")
        ensureColumn(db, "display_after", "TEXT")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `" + FIELD_STATS_TABLE + "` (" +
                "`table_name` TEXT NOT NULL, `row_key` TEXT NOT NULL, " +
                "`column_name` TEXT NOT NULL, `last_occurred_at` INTEGER NOT NULL, " +
                "`interval_sum_ms` INTEGER NOT NULL DEFAULT 0, " +
                "`interval_count` INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(`table_name`,`row_key`,`column_name`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_hub_git_history_time` ON `" +
                TABLE + "`(`occurred_at`,`id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_hub_git_history_entity` ON `" +
                TABLE + "`(`table_name`,`row_key`,`occurred_at`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_hub_git_history_author` ON `" +
                TABLE + "`(`author`,`occurred_at`)",
        )
    }

    fun indexCommitted(
        db: SupportSQLiteDatabase,
        events: List<GitEditEvent>,
        meta: List<GitHistoryCommitMeta>,
        commitSha: String,
    ) {
        val metaById = meta.associateBy { it.id }
        events.forEach { event ->
            val committed = metaById[event.id] ?: return@forEach
            val alreadyIndexed = db.query(
                "SELECT 1 FROM " + TABLE + " WHERE id=? LIMIT 1",
                arrayOf(event.id),
            ).use { it.moveToFirst() }
            db.execSQL(
                "INSERT OR REPLACE INTO " + TABLE + "(" +
                    "id,occurred_at,author,source,reason,group_id,table_name,operation,row_key,changed_columns," +
                    "history_path,commit_sha,reverted_by,display_before,display_after) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,NULL,?,?)",
                arrayOf<Any?>(
                    event.id,
                    event.occurredAt,
                    event.author,
                    event.source,
                    event.reason,
                    event.groupId,
                    event.table,
                    event.operation,
                    event.rowKey,
                    committed.changedColumns,
                    committed.historyPath,
                    commitSha,
                    projectPayload(event.beforePayload, event.columns, committed.changedColumns),
                    projectPayload(event.afterPayload, event.columns, committed.changedColumns),
                ),
            )
            if (!alreadyIndexed) {
                updateFieldStats(
                    db = db,
                    table = event.table,
                    rowKey = event.rowKey,
                    changedColumns = committed.changedColumns,
                    occurredAt = event.occurredAt,
                )
            }
        }
    }


    // This is a disposable, bounded display projection. The signed Git event remains authoritative.
    private fun displayFieldAllowed(key: String): Boolean =
        key !in setOf("id", "uuid", "source", "source_app", "version", "metadata") &&
            !key.endsWith("_id") && !key.endsWith("_uuid") && !key.endsWith("_json") &&
            !key.endsWith("_at") && !key.endsWith("_ms") &&
            !key.contains("hash") && !key.contains("payload") && !key.contains("cursor")

    internal fun projectPayload(payload: String?, columnsCsv: String, changedCsv: String): String? {
        if (payload == null) return null
        val columns = columnsCsv.split(',').filter(String::isNotBlank)
        val values = runCatching { SyncJournal.keyValues(payload) }.getOrNull() ?: return null
        if (values.size != columns.size) return null
        val wanted = changedCsv.split(',').toSet() + setOf("name", "nickname", "title", "label")
        val result = JSONObject()
        columns.forEachIndexed { index, key ->
            if (key !in wanted || !displayFieldAllowed(key)) return@forEachIndexed
            val value = values[index]
            if (value is String && value.length <= 240) result.put(key, value)
            else if (value is Number || value is Boolean) result.put(key, value)
        }
        return result.toString()
    }

    internal fun projectJson(row: JSONObject?, changedCsv: String): String? {
        if (row == null) return null
        val wanted = changedCsv.split(',').toSet() + setOf("name", "nickname", "title", "label")
        val result = JSONObject()
        wanted.filter(::displayFieldAllowed).forEach { key ->
            val value = row.opt(key)
            if (value is String && value.length <= 240) result.put(key, value)
            else if (value is Number || value is Boolean) result.put(key, value)
        }
        return result.toString()
    }

    fun recent(
        db: SupportSQLiteDatabase,
        limit: Int = 200,
        author: String? = null,
        table: String? = null,
        rowKey: String? = null,
    ): List<GitHistoryItem> {
        require(limit in 1..1000)
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        author?.let { clauses += "author=?"; args += it }
        table?.let { clauses += "table_name=?"; args += it }
        rowKey?.let { clauses += "row_key=?"; args += it }
        val where = if (clauses.isEmpty()) "" else " WHERE " + clauses.joinToString(" AND ")
        return db.query(
            "SELECT id,occurred_at,author,source,reason,group_id,table_name,operation,row_key,changed_columns," +
                "history_path,commit_sha,reverted_by,display_before,display_after FROM " + TABLE + where +
                " ORDER BY occurred_at DESC,id DESC LIMIT " + limit,
            args.toTypedArray(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        GitHistoryItem(
                            id = cursor.getString(0),
                            occurredAt = cursor.getLong(1),
                            author = cursor.getString(2),
                            source = cursor.getString(3),
                            reason = if (cursor.isNull(4)) null else cursor.getString(4),
                            groupId = if (cursor.isNull(5)) null else cursor.getString(5),
                            table = cursor.getString(6),
                            operation = cursor.getString(7),
                            rowKey = cursor.getString(8),
                            changedColumns = cursor.getString(9),
                            historyPath = cursor.getString(10),
                            commitSha = cursor.getString(11),
                            revertedBy = if (cursor.isNull(12)) null else cursor.getString(12),
                            displayBefore = if (cursor.isNull(13)) null else cursor.getString(13),
                            displayAfter = if (cursor.isNull(14)) null else cursor.getString(14),
                        ),
                    )
                }
            }
        }
    }

    fun find(db: SupportSQLiteDatabase, id: String): GitHistoryItem? =
        db.query(
            "SELECT id,occurred_at,author,source,reason,group_id,table_name,operation,row_key,changed_columns," +
                "history_path,commit_sha,reverted_by,display_before,display_after FROM " + TABLE + " WHERE id=? LIMIT 1",
            arrayOf(id),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else GitHistoryItem(
                id = cursor.getString(0),
                occurredAt = cursor.getLong(1),
                author = cursor.getString(2),
                source = cursor.getString(3),
                reason = if (cursor.isNull(4)) null else cursor.getString(4),
                groupId = if (cursor.isNull(5)) null else cursor.getString(5),
                table = cursor.getString(6),
                operation = cursor.getString(7),
                rowKey = cursor.getString(8),
                changedColumns = cursor.getString(9),
                historyPath = cursor.getString(10),
                commitSha = cursor.getString(11),
                revertedBy = if (cursor.isNull(12)) null else cursor.getString(12),
                            displayBefore = if (cursor.isNull(13)) null else cursor.getString(13),
                            displayAfter = if (cursor.isNull(14)) null else cursor.getString(14),
            )
        }

    fun byGroup(db: SupportSQLiteDatabase, groupId: String): List<GitHistoryItem> =
        db.query(
            "SELECT id,occurred_at,author,source,reason,group_id,table_name,operation,row_key," +
                "changed_columns,history_path,commit_sha,reverted_by,display_before,display_after FROM " + TABLE +
                " WHERE group_id=? ORDER BY occurred_at DESC,id DESC",
            arrayOf(groupId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    GitHistoryItem(
                        id = cursor.getString(0),
                        occurredAt = cursor.getLong(1),
                        author = cursor.getString(2),
                        source = cursor.getString(3),
                        reason = if (cursor.isNull(4)) null else cursor.getString(4),
                        groupId = if (cursor.isNull(5)) null else cursor.getString(5),
                        table = cursor.getString(6),
                        operation = cursor.getString(7),
                        rowKey = cursor.getString(8),
                        changedColumns = cursor.getString(9),
                        historyPath = cursor.getString(10),
                        commitSha = cursor.getString(11),
                        revertedBy = if (cursor.isNull(12)) null else cursor.getString(12),
                            displayBefore = if (cursor.isNull(13)) null else cursor.getString(13),
                            displayAfter = if (cursor.isNull(14)) null else cursor.getString(14),
                    ),
                )
            }
        }

    fun between(
        db: SupportSQLiteDatabase,
        fromMs: Long,
        toMs: Long,
        table: String? = null,
        limit: Int = 1000,
    ): List<GitHistoryItem> {
        require(fromMs <= toMs && limit in 1..5000)
        val tableClause = if (table == null) "" else " AND table_name=?"
        val args = if (table == null) arrayOf<Any?>(fromMs, toMs)
            else arrayOf<Any?>(fromMs, toMs, table)
        return db.query(
            "SELECT id,occurred_at,author,source,reason,group_id,table_name,operation,row_key," +
                "changed_columns,history_path,commit_sha,reverted_by,display_before,display_after FROM " + TABLE +
                " WHERE occurred_at>=? AND occurred_at<=?" + tableClause +
                " ORDER BY occurred_at ASC,id ASC LIMIT " + limit,
            args,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    GitHistoryItem(
                        id = cursor.getString(0),
                        occurredAt = cursor.getLong(1),
                        author = cursor.getString(2),
                        source = cursor.getString(3),
                        reason = if (cursor.isNull(4)) null else cursor.getString(4),
                        groupId = if (cursor.isNull(5)) null else cursor.getString(5),
                        table = cursor.getString(6),
                        operation = cursor.getString(7),
                        rowKey = cursor.getString(8),
                        changedColumns = cursor.getString(9),
                        historyPath = cursor.getString(10),
                        commitSha = cursor.getString(11),
                        revertedBy = if (cursor.isNull(12)) null else cursor.getString(12),
                            displayBefore = if (cursor.isNull(13)) null else cursor.getString(13),
                            displayAfter = if (cursor.isNull(14)) null else cursor.getString(14),
                    ),
                )
            }
        }
    }

    fun updateDisplayProjection(
        db: SupportSQLiteDatabase, id: String, before: String?, after: String?,
    ) {
        db.execSQL(
            "UPDATE " + TABLE + " SET display_before=?,display_after=? WHERE id=? " +
                "AND display_before IS NULL AND display_after IS NULL",
            arrayOf(before, after, id),
        )
    }

    fun markReverted(db: SupportSQLiteDatabase, id: String, revertedBy: String) {
        db.execSQL(
            "UPDATE " + TABLE + " SET reverted_by=? WHERE id=? AND reverted_by IS NULL",
            arrayOf(revertedBy, id),
        )
    }

    private fun ensureColumn(db: SupportSQLiteDatabase, column: String, definition: String) {
        val present = db.query("PRAGMA table_info(`" + TABLE + "`)").use { cursor ->
            var found = false
            while (cursor.moveToNext()) if (cursor.getString(1) == column) found = true
            found
        }
        if (!present) db.execSQL("ALTER TABLE `" + TABLE + "` ADD COLUMN `" + column + "` " + definition)
    }

    fun countsByAuthor(db: SupportSQLiteDatabase, limit: Int = 20): List<GitHistoryCount> =
        counts(db, "author", limit)

    fun countsByTable(db: SupportSQLiteDatabase, limit: Int = 50): List<GitHistoryCount> =
        counts(db, "table_name", limit)

    fun countsBySource(db: SupportSQLiteDatabase, limit: Int = 20): List<GitHistoryCount> =
        counts(db, "source", limit)

    fun countSince(db: SupportSQLiteDatabase, fromMs: Long): Long =
        db.query(
            "SELECT COUNT(*) FROM " + TABLE + " WHERE occurred_at>=?",
            arrayOf(fromMs),
        ).use { cursor ->
            require(cursor.moveToFirst())
            cursor.getLong(0)
        }

    fun averageFieldValueLifetimeMs(db: SupportSQLiteDatabase): Long? {
        ensureFieldStatsReady(db)
        return db.query(
            "SELECT SUM(interval_sum_ms),SUM(interval_count) FROM " + FIELD_STATS_TABLE,
        ).use { cursor ->
            require(cursor.moveToFirst())
            val count = if (cursor.isNull(1)) 0L else cursor.getLong(1)
            if (count == 0L) null else cursor.getLong(0) / count
        }
    }

    fun rebuildFieldStats(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM " + FIELD_STATS_TABLE)
        db.query(
            "SELECT table_name,row_key,changed_columns,occurred_at FROM " + TABLE +
                " ORDER BY table_name,row_key,occurred_at,id",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                updateFieldStats(
                    db = db,
                    table = cursor.getString(0),
                    rowKey = cursor.getString(1),
                    changedColumns = cursor.getString(2),
                    occurredAt = cursor.getLong(3),
                )
            }
        }
    }

    private fun ensureFieldStatsReady(db: SupportSQLiteDatabase) {
        val statsRows = db.query("SELECT COUNT(*) FROM " + FIELD_STATS_TABLE).use {
            require(it.moveToFirst())
            it.getLong(0)
        }
        if (statsRows > 0L) return
        val historyRows = db.query("SELECT COUNT(*) FROM " + TABLE).use {
            require(it.moveToFirst())
            it.getLong(0)
        }
        if (historyRows > 0L) rebuildFieldStats(db)
    }

    private fun updateFieldStats(
        db: SupportSQLiteDatabase,
        table: String,
        rowKey: String,
        changedColumns: String,
        occurredAt: Long,
    ) {
        changedColumns.split(',').filter { it.isNotBlank() }.forEach { column ->
            val previous = db.query(
                "SELECT last_occurred_at,interval_sum_ms,interval_count FROM " +
                    FIELD_STATS_TABLE +
                    " WHERE table_name=? AND row_key=? AND column_name=? LIMIT 1",
                arrayOf(table, rowKey, column),
            ).use { cursor ->
                if (!cursor.moveToFirst()) null
                else Triple(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2))
            }
            val validDelta = previous
                ?.first
                ?.takeIf { occurredAt >= it }
                ?.let { occurredAt - it }
            val sum = (previous?.second ?: 0L) + (validDelta ?: 0L)
            val count = (previous?.third ?: 0L) + if (validDelta == null) 0L else 1L
            db.execSQL(
                "INSERT OR REPLACE INTO " + FIELD_STATS_TABLE +
                    "(table_name,row_key,column_name,last_occurred_at,interval_sum_ms,interval_count)" +
                    " VALUES(?,?,?,?,?,?)",
                arrayOf<Any?>(table, rowKey, column, occurredAt, sum, count),
            )
        }
    }

    fun countsByEntity(db: SupportSQLiteDatabase, limit: Int = 20): List<GitHistoryCount> =
        db.query(
            "SELECT table_name || ':' || row_key,COUNT(*) FROM " + TABLE +
                " GROUP BY table_name,row_key ORDER BY COUNT(*) DESC LIMIT " + limit,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(GitHistoryCount(cursor.getString(0), cursor.getLong(1)))
            }
        }

    private fun counts(
        db: SupportSQLiteDatabase,
        column: String,
        limit: Int,
    ): List<GitHistoryCount> =
        db.query(
            "SELECT " + column + ",COUNT(*) FROM " + TABLE +
                " GROUP BY " + column + " ORDER BY COUNT(*) DESC LIMIT " + limit,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(GitHistoryCount(cursor.getString(0), cursor.getLong(1)))
            }
        }
}
