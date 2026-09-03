package com.example.multitimetracker.capsules.remotesync

import android.content.ContentValues
import android.content.Context
import com.gernalix.personalhub.core.database.LegacyDatabase as SQLiteDatabase
import org.json.JSONObject

internal class RemoteSyncQueueSqlite(context: Context, dbName: String = DB_NAME) {
    private val helper = Helper(context.applicationContext, dbName)

    data class QueueItem(val syncId: String, val rowJson: String, val revision: Long)

    fun refresh(entities: List<RemoteEntity>, nowMs: Long = System.currentTimeMillis()) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val seen = entities.mapTo(linkedSetOf()) { it.syncId }
            entities.forEach { entity ->
                val previousFingerprint = db.rawQuery(
                    "SELECT fingerprint FROM sync_shadow WHERE sync_id = ?",
                    arrayOf(entity.syncId),
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                if (previousFingerprint != entity.fingerprint) {
                    enqueueLocked(db, entity.syncId, entity.rowJson, nextRevisionLocked(db), nowMs)
                    upsertShadowLocked(db, entity)
                }
            }

            db.rawQuery("SELECT sync_id, row_json FROM sync_shadow", emptyArray()).use { cursor ->
                while (cursor.moveToNext()) {
                    val syncId = cursor.getString(0)
                    if (syncId in seen) continue
                    val prior = JSONObject(cursor.getString(1))
                    if (!prior.isNull("deleted_at_ms")) continue
                    prior.put("updated_at_ms", nowMs)
                    prior.put("updated_at", RemoteTime.utcZ(nowMs))
                    prior.put("deleted_at_ms", nowMs)
                    prior.put("deleted_at", RemoteTime.utcZ(nowMs))
                    val rowJson = prior.toString()
                    val tombstone = RemoteEntity(syncId, rowJson, RemoteSyncContract.sha256(rowJson))
                    enqueueLocked(db, syncId, rowJson, nextRevisionLocked(db), nowMs)
                    upsertShadowLocked(db, tombstone)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    fun nextBatch(limit: Int = RemoteSyncContract.BATCH_SIZE): List<QueueItem> {
        val db = helper.readableDatabase
        return try {
            db.rawQuery(
                "SELECT sync_id, row_json, revision FROM sync_queue ORDER BY revision LIMIT ?",
                arrayOf(limit.coerceIn(1, RemoteSyncContract.BATCH_SIZE).toString()),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(QueueItem(cursor.getString(0), cursor.getString(1), cursor.getLong(2)))
                    }
                }
            }
        } finally {
            db.close()
        }
    }

    fun markAttempt(items: List<QueueItem>, failureClass: String?) {
        if (items.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            items.forEach { item ->
                db.execSQL(
                    "UPDATE sync_queue SET attempt_count = attempt_count + 1, last_attempt_at_ms = ?, last_failure_class = ? WHERE sync_id = ? AND revision = ?",
                    arrayOf<Any?>(System.currentTimeMillis(), failureClass?.take(60), item.syncId, item.revision),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    fun acknowledge(items: List<QueueItem>) {
        if (items.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            items.forEach { item ->
                db.delete(
                    "sync_queue",
                    "sync_id = ? AND revision = ?",
                    arrayOf(item.syncId, item.revision.toString()),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    fun pendingCount(): Int {
        val db = helper.readableDatabase
        return try {
            db.rawQuery("SELECT count(*) FROM sync_queue", emptyArray()).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        } finally {
            db.close()
        }
    }

    fun integrityCheck(): String {
        val db = helper.readableDatabase
        return try {
            db.rawQuery("PRAGMA integrity_check", emptyArray()).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else "missing-result"
            }
        } finally {
            db.close()
        }
    }

    internal fun clearForTests() {
        val db = helper.writableDatabase
        try {
            db.execSQL("DELETE FROM sync_queue")
            db.execSQL("DELETE FROM sync_shadow")
            db.execSQL("UPDATE sync_meta SET long_value = 0 WHERE key = 'revision'")
        } finally {
            db.close()
        }
    }

    private fun enqueueLocked(db: SQLiteDatabase, syncId: String, rowJson: String, revision: Long, nowMs: Long) {
        db.insertWithOnConflict(
            "sync_queue",
            null,
            ContentValues().apply {
                put("sync_id", syncId)
                put("row_json", rowJson)
                put("revision", revision)
                put("attempt_count", 0)
                put("created_at_ms", nowMs)
                putNull("last_attempt_at_ms")
                putNull("last_failure_class")
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun upsertShadowLocked(db: SQLiteDatabase, entity: RemoteEntity) {
        db.insertWithOnConflict(
            "sync_shadow",
            null,
            ContentValues().apply {
                put("sync_id", entity.syncId)
                put("fingerprint", entity.fingerprint)
                put("row_json", entity.rowJson)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun nextRevisionLocked(db: SQLiteDatabase): Long {
        db.execSQL("UPDATE sync_meta SET long_value = long_value + 1 WHERE key = 'revision'")
        return db.rawQuery("SELECT long_value FROM sync_meta WHERE key = 'revision'", emptyArray()).use { cursor ->
            check(cursor.moveToFirst()) { "Missing remote sync revision" }
            cursor.getLong(0)
        }
    }

    private class Helper(private val context: Context, dbName: String) {
        val readableDatabase get() = SQLiteDatabase.get(context)
        val writableDatabase get() = SQLiteDatabase.get(context)
    }

    companion object {
        const val DB_NAME = "personalhub.db"
        const val DB_VERSION = 1
    }
}
