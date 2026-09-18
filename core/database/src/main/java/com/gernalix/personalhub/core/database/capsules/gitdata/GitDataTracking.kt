package com.gernalix.personalhub.core.database.capsules.gitdata

import androidx.sqlite.db.SupportSQLiteDatabase
import com.gernalix.personalhub.core.database.capsules.sync.SyncJournal

data class GitEditEvent(
    val id: String,
    val occurredAt: Long,
    val author: String,
    val groupId: String?,
    val table: String,
    val operation: String,
    val rowKey: String,
    val columns: String,
    val beforePayload: String?,
    val afterPayload: String?,
)

object GitDataTracking {
    const val TABLE = "hub_git_pending"
    const val EVENTS_TABLE = "hub_git_events"
    const val CONTEXT_TABLE = "hub_git_edit_context"
    const val APPLIED_PATCHES_TABLE = "hub_git_applied_patches"

    val operationalTables = setOf(TABLE, EVENTS_TABLE, CONTEXT_TABLE, APPLIED_PATCHES_TABLE)

    fun tables(db: SupportSQLiteDatabase): List<String> =
        SyncJournal.tables(db).filterNot { it in operationalTables }

    fun columns(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }

    private fun encodedPayload(columns: List<String>, prefix: String): String =
        columns.joinToString(" || ':' || ") { "hex(quote($prefix`$it`))" }

    private fun randomIdSql() =
        "lower(hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-' || " +
            "hex(randomblob(2)) || '-' || hex(randomblob(2)) || '-' || hex(randomblob(6)))"

    private fun nowMsSql() =
        "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"

    fun trigger(
        table: String,
        columns: List<String>,
        keys: List<String>,
        op: String,
    ): String {
        require(op in setOf("INSERT", "UPDATE", "DELETE"))
        require(columns.isNotEmpty() && keys.isNotEmpty())
        val keyPrefix = if (op == "DELETE") "OLD." else "NEW."
        val key = SyncJournal.keyExpression(keys, keyPrefix)
        val before = if (op == "INSERT") "NULL" else encodedPayload(columns, "OLD.")
        val after = if (op == "DELETE") "NULL" else encodedPayload(columns, "NEW.")
        val columnCsv = columns.joinToString(",").replace("'", "''")
        return "CREATE TRIGGER `hub_git_dirty_${table}_$op` AFTER $op ON `$table` BEGIN " +
            "INSERT OR IGNORE INTO $TABLE(table_name,revision) VALUES ('$table',0); " +
            "UPDATE $TABLE SET revision=revision+1 WHERE table_name='$table'; " +
            "INSERT INTO $EVENTS_TABLE(" +
            "id,occurred_at,author,group_id,table_name,operation,row_key,columns,before_payload,after_payload" +
            ") VALUES(" +
            "${randomIdSql()},${nowMsSql()}," +
            "COALESCE((SELECT actor FROM $CONTEXT_TABLE WHERE id=1),'user')," +
            "(SELECT group_id FROM $CONTEXT_TABLE WHERE id=1)," +
            "'$table','$op',$key,'$columnCsv',$before,$after" +
            "); END"
    }

    fun install(db: SupportSQLiteDatabase, enqueueAll: Boolean) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `$TABLE` (" +
                "`table_name` TEXT NOT NULL, `revision` INTEGER NOT NULL, " +
                "PRIMARY KEY(`table_name`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `$EVENTS_TABLE` (" +
                "`id` TEXT NOT NULL, `occurred_at` INTEGER NOT NULL, " +
                "`author` TEXT NOT NULL, `group_id` TEXT, " +
                "`table_name` TEXT NOT NULL, `operation` TEXT NOT NULL, " +
                "`row_key` TEXT NOT NULL, `columns` TEXT NOT NULL, " +
                "`before_payload` TEXT, `after_payload` TEXT, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `$CONTEXT_TABLE` (" +
                "`id` INTEGER NOT NULL PRIMARY KEY, `actor` TEXT NOT NULL, `group_id` TEXT)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `$APPLIED_PATCHES_TABLE` (" +
                "`id` TEXT NOT NULL PRIMARY KEY, `applied_at` INTEGER NOT NULL)",
        )
        db.execSQL("DELETE FROM $CONTEXT_TABLE")
        tables(db).forEach { table ->
            val columns = columns(db, table)
            val keys = SyncJournal.primaryKeys(db, table)
            check(keys.isNotEmpty()) { "Git sync requires a primary key: $table" }
            listOf("INSERT", "UPDATE", "DELETE").forEach { op ->
                db.execSQL("DROP TRIGGER IF EXISTS `hub_git_dirty_${table}_$op`")
                db.execSQL(trigger(table, columns, keys, op))
            }
        }
        if (enqueueAll) enqueueAll(db)
    }

    fun uninstall(db: SupportSQLiteDatabase) {
        tables(db).forEach { table ->
            listOf("INSERT", "UPDATE", "DELETE").forEach { op ->
                db.execSQL("DROP TRIGGER IF EXISTS `hub_git_dirty_${table}_$op`")
            }
        }
        db.execSQL("DELETE FROM $CONTEXT_TABLE")
    }

    fun enqueueAll(db: SupportSQLiteDatabase) {
        tables(db).forEach { table ->
            db.execSQL(
                "INSERT OR IGNORE INTO $TABLE(table_name,revision) VALUES (?,0)",
                arrayOf(table),
            )
            db.execSQL(
                "UPDATE $TABLE SET revision=revision+1 WHERE table_name=?",
                arrayOf(table),
            )
        }
    }

    fun pending(db: SupportSQLiteDatabase): List<Pair<String, Long>> =
        db.query("SELECT table_name,revision FROM $TABLE ORDER BY table_name").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getLong(1))
            }
        }

    fun events(db: SupportSQLiteDatabase): List<GitEditEvent> =
        db.query(
            "SELECT id,occurred_at,author,group_id,table_name,operation,row_key,columns," +
                "before_payload,after_payload FROM $EVENTS_TABLE ORDER BY occurred_at,id",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        GitEditEvent(
                            id = cursor.getString(0),
                            occurredAt = cursor.getLong(1),
                            author = cursor.getString(2),
                            groupId = if (cursor.isNull(3)) null else cursor.getString(3),
                            table = cursor.getString(4),
                            operation = cursor.getString(5),
                            rowKey = cursor.getString(6),
                            columns = cursor.getString(7),
                            beforePayload = if (cursor.isNull(8)) null else cursor.getString(8),
                            afterPayload = if (cursor.isNull(9)) null else cursor.getString(9),
                        ),
                    )
                }
            }
        }

    fun acknowledge(
        db: SupportSQLiteDatabase,
        pending: List<Pair<String, Long>>,
        eventIds: List<String>,
    ) {
        pending.forEach { (table, revision) ->
            db.delete(TABLE, "table_name=? AND revision=?", arrayOf(table, revision))
        }
        eventIds.forEach { id ->
            db.delete(EVENTS_TABLE, "id=?", arrayOf(id))
        }
    }

    fun setEditContext(db: SupportSQLiteDatabase, author: String, groupId: String?) {
        require(author.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "Invalid edit author" }
        db.execSQL(
            "INSERT OR REPLACE INTO $CONTEXT_TABLE(id,actor,group_id) VALUES(1,?,?)",
            arrayOf(author, groupId),
        )
    }

    fun clearEditContext(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM $CONTEXT_TABLE WHERE id=1")
    }

    fun isPatchApplied(db: SupportSQLiteDatabase, patchId: String): Boolean =
        db.query(
            "SELECT 1 FROM $APPLIED_PATCHES_TABLE WHERE id=? LIMIT 1",
            arrayOf(patchId),
        ).use { it.moveToFirst() }

    fun markPatchApplied(db: SupportSQLiteDatabase, patchId: String) {
        db.execSQL(
            "INSERT INTO $APPLIED_PATCHES_TABLE(id,applied_at) VALUES(?,?)",
            arrayOf(patchId, System.currentTimeMillis()),
        )
    }
}
