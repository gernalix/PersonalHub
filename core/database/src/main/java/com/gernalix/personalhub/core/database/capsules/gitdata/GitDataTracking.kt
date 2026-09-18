package com.gernalix.personalhub.core.database.capsules.gitdata

import androidx.sqlite.db.SupportSQLiteDatabase
import com.gernalix.personalhub.core.database.capsules.sync.SyncJournal

/**
 * Transactional table-level dirty journal for Git snapshots.
 *
 * It is deliberately separate from Datasette's row journal so either sync can acknowledge work
 * independently without making the other lose a change.
 */
object GitDataTracking {
    const val TABLE = "hub_git_pending"

    fun tables(db: SupportSQLiteDatabase): List<String> =
        SyncJournal.tables(db).filterNot { it == TABLE }

    fun trigger(table: String, op: String): String {
        require(op in setOf("INSERT", "UPDATE", "DELETE"))
        return "CREATE TRIGGER `hub_git_dirty_${table}_$op` AFTER $op ON `$table` BEGIN " +
            "INSERT OR IGNORE INTO $TABLE(table_name,revision) VALUES ('$table',0); " +
            "UPDATE $TABLE SET revision=revision+1 WHERE table_name='$table'; END"
    }

    fun install(db: SupportSQLiteDatabase, enqueueAll: Boolean) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `$TABLE` (" +
                "`table_name` TEXT NOT NULL, `revision` INTEGER NOT NULL, PRIMARY KEY(`table_name`))",
        )
        tables(db).forEach { table ->
            listOf("INSERT", "UPDATE", "DELETE").forEach { op ->
                db.execSQL("DROP TRIGGER IF EXISTS `hub_git_dirty_${table}_$op`")
                db.execSQL(trigger(table, op))
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

    fun acknowledge(db: SupportSQLiteDatabase, pending: List<Pair<String, Long>>) {
        pending.forEach { (table, revision) ->
            db.delete(TABLE, "table_name=? AND revision=?", arrayOf(table, revision))
        }
    }
}
