package com.gernalix.personalhub.core.database.capsules.gitdata

import androidx.sqlite.db.SupportSQLiteDatabase

data class GitHistoryCommitMeta(
    val id: String,
    val historyPath: String,
    val changedColumns: String,
)

data class GitHistoryItem(
    val id: String,
    val occurredAt: Long,
    val author: String,
    val groupId: String?,
    val table: String,
    val operation: String,
    val rowKey: String,
    val changedColumns: String,
    val historyPath: String,
    val commitSha: String,
    val revertedBy: String?,
)

data class GitHistoryCount(val key: String, val count: Long)

/**
 * Compact, disposable local projection of immutable Git history.
 *
 * Full before/after payloads live in Git. This table exists only to keep History, blame and
 * statistics instant on-device; it can be rebuilt from history/*.jsonl without data loss.
 */
object GitHistoryStore {
    const val TABLE = "hub_git_history_index"

    fun install(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `" + TABLE + "` (" +
                "`id` TEXT NOT NULL PRIMARY KEY, `occurred_at` INTEGER NOT NULL, " +
                "`author` TEXT NOT NULL, `group_id` TEXT, `table_name` TEXT NOT NULL, " +
                "`operation` TEXT NOT NULL, `row_key` TEXT NOT NULL, " +
                "`changed_columns` TEXT NOT NULL, `history_path` TEXT NOT NULL, " +
                "`commit_sha` TEXT NOT NULL, `reverted_by` TEXT)",
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
            db.execSQL(
                "INSERT OR REPLACE INTO " + TABLE + "(" +
                    "id,occurred_at,author,group_id,table_name,operation,row_key,changed_columns," +
                    "history_path,commit_sha,reverted_by) VALUES(?,?,?,?,?,?,?,?,?,?,NULL)",
                arrayOf(
                    event.id,
                    event.occurredAt,
                    event.author,
                    event.groupId,
                    event.table,
                    event.operation,
                    event.rowKey,
                    committed.changedColumns,
                    committed.historyPath,
                    commitSha,
                ),
            )
        }
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
            "SELECT id,occurred_at,author,group_id,table_name,operation,row_key,changed_columns," +
                "history_path,commit_sha,reverted_by FROM " + TABLE + where +
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
                            groupId = if (cursor.isNull(3)) null else cursor.getString(3),
                            table = cursor.getString(4),
                            operation = cursor.getString(5),
                            rowKey = cursor.getString(6),
                            changedColumns = cursor.getString(7),
                            historyPath = cursor.getString(8),
                            commitSha = cursor.getString(9),
                            revertedBy = if (cursor.isNull(10)) null else cursor.getString(10),
                        ),
                    )
                }
            }
        }
    }

    fun find(db: SupportSQLiteDatabase, id: String): GitHistoryItem? =
        recent(db, limit = 1).takeIf { false }?.firstOrNull()
            ?: db.query(
                "SELECT id,occurred_at,author,group_id,table_name,operation,row_key,changed_columns," +
                    "history_path,commit_sha,reverted_by FROM " + TABLE + " WHERE id=? LIMIT 1",
                arrayOf(id),
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else GitHistoryItem(
                    id = cursor.getString(0),
                    occurredAt = cursor.getLong(1),
                    author = cursor.getString(2),
                    groupId = if (cursor.isNull(3)) null else cursor.getString(3),
                    table = cursor.getString(4),
                    operation = cursor.getString(5),
                    rowKey = cursor.getString(6),
                    changedColumns = cursor.getString(7),
                    historyPath = cursor.getString(8),
                    commitSha = cursor.getString(9),
                    revertedBy = if (cursor.isNull(10)) null else cursor.getString(10),
                )
            }

    fun markReverted(db: SupportSQLiteDatabase, id: String, revertedBy: String) {
        db.execSQL(
            "UPDATE " + TABLE + " SET reverted_by=? WHERE id=? AND reverted_by IS NULL",
            arrayOf(revertedBy, id),
        )
    }

    fun countsByAuthor(db: SupportSQLiteDatabase, limit: Int = 20): List<GitHistoryCount> =
        counts(db, "author", limit)

    fun countsByTable(db: SupportSQLiteDatabase, limit: Int = 50): List<GitHistoryCount> =
        counts(db, "table_name", limit)

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
