package com.gernalix.personalhub.core.database.capsules.sync

import androidx.room.Entity
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "hub_sync_pending", primaryKeys = ["table_name", "row_key"])
data class HubSyncPending(val table_name: String, val row_key: String, val revision: Long)

@Entity(tableName = "hub_sync_known", primaryKeys = ["table_name", "row_key"])
data class HubSyncKnown(val table_name: String, val row_key: String)

/** Transactional, coalescing journal shared by every DAO and the timer compatibility adapter. */
object SyncJournal {
    val excluded = setOf("room_master_table", "android_metadata", "hub_generation", "hub_sync_pending", "hub_sync_known", "sync_queue", "sync_shadow", "sync_meta", "hub_activity_undo_context", "hub_git_pending", "hub_git_events", "hub_git_edit_context", "hub_git_applied_patches")

    fun create(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `hub_sync_pending` (`table_name` TEXT NOT NULL, `row_key` TEXT NOT NULL, `revision` INTEGER NOT NULL, PRIMARY KEY(`table_name`, `row_key`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `hub_sync_known` (`table_name` TEXT NOT NULL, `row_key` TEXT NOT NULL, PRIMARY KEY(`table_name`, `row_key`))")
    }

    fun tables(db: SupportSQLiteDatabase): List<String> = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").use { c ->
        buildList { while (c.moveToNext()) if (c.getString(0) !in excluded) add(c.getString(0)) }
    }

    fun primaryKeys(db: SupportSQLiteDatabase, table: String): List<String> = db.query("PRAGMA table_info(`$table`)").use { c ->
        buildList { while (c.moveToNext()) if (c.getInt(5) > 0) add(c.getInt(5) to c.getString(1)) }.sortedBy { it.first }.map { it.second }
    }

    // quote() retains SQLite types; hex() makes composite keys unambiguous and safe to bind.
    fun keyExpression(keys: List<String>, prefix: String = "") = keys.joinToString(" || ':' || ") { "hex(quote($prefix`$it`))" }

    /** Decode quote() output into bound values, so row reads use the existing primary-key index. */
    fun keyValues(key: String): Array<Any?> {
        fun bytes(hex: String): ByteArray {
            require(hex.length % 2 == 0)
            return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
        return key.split(':').map { part ->
            val literal = bytes(part).toString(Charsets.UTF_8)
            when {
                literal == "NULL" -> null
                literal.startsWith("'") && literal.endsWith("'") -> literal.substring(1, literal.length - 1).replace("''", "'")
                literal.startsWith("X'") && literal.endsWith("'") -> bytes(literal.substring(2, literal.length - 1))
                else -> literal.toLongOrNull() ?: literal.toDouble().also { require(it.isFinite()) }
            }
        }.toTypedArray()
    }

    fun trigger(table: String, keys: List<String>, op: String, legacy: Boolean = false): String {
        fun enqueue(prefix: String): String {
            val key = keyExpression(keys, prefix)
            // SQLite inherits an outer INSERT OR ABORT policy inside triggers. A guarded
            // INSERT avoids the duplicate entirely instead of relying on OR IGNORE.
            val insertion = if (legacy) "INSERT OR IGNORE INTO hub_sync_pending(table_name,row_key,revision) SELECT '$table',$key,1;"
                else "INSERT INTO hub_sync_pending(table_name,row_key,revision) SELECT '$table',$key,1 WHERE NOT EXISTS (SELECT 1 FROM hub_sync_pending WHERE table_name='$table' AND row_key=$key);"
            return "UPDATE hub_sync_pending SET revision=revision+1 WHERE table_name='$table' AND row_key=$key; " +
                insertion
        }
        // An UPDATE can change a primary key: retain the old identity as a deletion too.
        val body = when (op) { "INSERT" -> enqueue("NEW."); "DELETE" -> enqueue("OLD."); else -> enqueue("OLD.") + " " + enqueue("NEW.") }
        return "CREATE TRIGGER `hub_sync_${table}_$op` AFTER $op ON `$table` BEGIN $body END"
    }

    fun install(db: SupportSQLiteDatabase) {
        tables(db).forEach { table ->
            val keys = primaryKeys(db, table)
            check(keys.isNotEmpty()) { "Sync requires a primary key" }
            listOf("INSERT", "UPDATE", "DELETE").forEach { op ->
                db.execSQL("DROP TRIGGER IF EXISTS `hub_sync_${table}_$op`")
                db.execSQL(trigger(table, keys, op))
            }
        }
    }

    fun enqueueAll(db: SupportSQLiteDatabase) {
        db.beginTransaction()
        try {
            // Known identities include deletions and requests whose response was lost.
            db.execSQL("INSERT OR IGNORE INTO hub_sync_pending SELECT table_name,row_key,1 FROM hub_sync_known")
            tables(db).forEach { table ->
                db.execSQL("INSERT OR IGNORE INTO hub_sync_pending SELECT '$table',${keyExpression(primaryKeys(db, table))},1 FROM `$table`")
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
