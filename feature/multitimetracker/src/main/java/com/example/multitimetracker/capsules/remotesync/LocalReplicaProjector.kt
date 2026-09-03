package com.example.multitimetracker.capsules.remotesync

import android.content.Context
import android.database.Cursor
import com.gernalix.personalhub.core.database.LegacyDatabase as SQLiteDatabase
import com.example.multitimetracker.BuildConfig
import com.example.multitimetracker.persistence.SnapshotSqlite
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal class LocalReplicaProjector(private val context: Context) {
    fun project(): List<RemoteEntity> {
        SnapshotSqlite.ensureStartupSchemas(context)
        val deviceId = deviceId()
        val db = SnapshotSqlite.openReadableDb(context)
        return try {
            buildList {
                RemoteSyncContract.localEntityTables.forEach { table ->
                    db.query(table, null, null, null, null, null, "id").use { cursor ->
                        while (cursor.moveToNext()) add(projectRow(db, cursor, table, deviceId))
                    }
                }
                addAll(projectSnapshotTags(db, deviceId))
            }
        } finally {
            db.close()
        }
    }

    private fun projectRow(db: SQLiteDatabase, cursor: Cursor, table: String, deviceId: String): RemoteEntity {
        val localId = cursor.getLong(cursor.getColumnIndexOrThrow("id"))
        val updatedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at_ms"))
        val deletedIndex = cursor.getColumnIndex("deleted_at_ms")
        val deletedAtMs = if (deletedIndex >= 0 && !cursor.isNull(deletedIndex)) cursor.getLong(deletedIndex) else null
        val payload = cursorAsJson(cursor)
        appendRelations(db, table, localId, payload)
        return remoteEntity(deviceId, table, localId, payload, updatedAtMs, deletedAtMs)
    }

    private fun projectSnapshotTags(db: SQLiteDatabase, deviceId: String): List<RemoteEntity> =
        db.query("snapshot", arrayOf("json", "saved_at_ms"), "id = 1", null, null, null, null).use { cursor ->
            if (!cursor.moveToFirst()) emptyList()
            else projectTagsFromSnapshot(cursor.getString(0), cursor.getLong(1), deviceId)
        }

    internal fun projectTagsFromSnapshot(
        snapshotJson: String,
        snapshotSavedAtMs: Long,
        deviceId: String,
    ): List<RemoteEntity> {
        val tags = JSONObject(snapshotJson).optJSONArray("tags") ?: return emptyList()
        return buildList {
            for (index in 0 until tags.length()) {
                val tag = tags.optJSONObject(index) ?: continue
                if (!tag.has("id") || tag.isNull("id")) continue
                val localId = tag.getLong("id")
                val deletedAtMs = when {
                    !tag.optBoolean("isDeleted", false) -> null
                    tag.has("deletedAtMs") && !tag.isNull("deletedAtMs") -> tag.getLong("deletedAtMs")
                    else -> snapshotSavedAtMs
                }
                val payload = JSONObject()
                    .put("id", localId)
                    .put("name", tag.optString("name", ""))
                    .put("timed_duration_minutes", tag.opt("timedDurationMinutes") ?: JSONObject.NULL)
                    .put("notification_type", tag.optString("notificationType", "NONE"))
                    .put("is_archived", tag.optBoolean("isArchived", false))
                    .put("show_in_timeline", tag.optBoolean("showInTimeline", true))
                    .put("is_deleted", deletedAtMs != null)
                    .put("deleted_at_ms", deletedAtMs ?: JSONObject.NULL)
                add(
                    remoteEntity(
                        deviceId = deviceId,
                        entityType = RemoteSyncContract.TAG_ENTITY_TYPE,
                        localId = localId,
                        payload = payload,
                        updatedAtMs = maxOf(snapshotSavedAtMs, deletedAtMs ?: Long.MIN_VALUE),
                        deletedAtMs = deletedAtMs,
                    )
                )
            }
        }
    }

    private fun remoteEntity(
        deviceId: String,
        entityType: String,
        localId: Long,
        payload: JSONObject,
        updatedAtMs: Long,
        deletedAtMs: Long?,
    ): RemoteEntity {
        val syncId = RemoteSyncContract.stableSyncId(deviceId, entityType, localId)
        val row = JSONObject()
            .put("sync_id", syncId)
            .put("device_id", deviceId)
            .put("entity_type", entityType)
            .put("local_id", localId)
            .put("payload_json", payload.toString())
            .put("updated_at_ms", updatedAtMs)
            .put("updated_at", RemoteTime.utcZ(updatedAtMs))
            .put("deleted_at_ms", deletedAtMs ?: JSONObject.NULL)
            .put("deleted_at", deletedAtMs?.let(RemoteTime::utcZ) ?: JSONObject.NULL)
            .put("source_app_version", BuildConfig.PATCH_VERSION)
        val rowJson = row.toString()
        return RemoteEntity(syncId, rowJson, RemoteSyncContract.sha256(rowJson))
    }

    private fun appendRelations(db: SQLiteDatabase, table: String, localId: Long, payload: JSONObject) {
        when (table) {
            "sessions" -> payload.put("tag_ids", scalarArray(db, "session_tags", "session_id", localId, "tag_id"))
            "quick_event_templates" -> payload.put(
                "tag_ids",
                scalarArray(db, "quick_event_template_tags", "template_id", localId, "tag_id"),
            )
            "quick_event_entries" -> {
                payload.put("tag_ids", scalarArray(db, "quick_event_entry_tags", "entry_id", localId, "tag_id"))
                payload.put(
                    "field_values",
                    objectArray(db, "quick_event_entry_field_values", "entry_id", localId, "display_order, id"),
                )
            }
            "quick_event_macros" -> {
                payload.put("tag_ids", scalarArray(db, "quick_event_macro_tags", "macro_id", localId, "tag_id"))
                payload.put(
                    "actions",
                    objectArray(db, "quick_event_macro_actions", "macro_id", localId, "display_order, template_id"),
                )
            }
        }
    }

    private fun scalarArray(
        db: SQLiteDatabase,
        table: String,
        foreignKey: String,
        localId: Long,
        valueColumn: String,
    ): JSONArray = JSONArray().also { array ->
        db.query(table, arrayOf(valueColumn), "$foreignKey = ?", arrayOf(localId.toString()), null, null, valueColumn)
            .use { cursor -> while (cursor.moveToNext()) array.put(cursor.getLong(0)) }
    }

    private fun objectArray(
        db: SQLiteDatabase,
        table: String,
        foreignKey: String,
        localId: Long,
        orderBy: String,
    ): JSONArray = JSONArray().also { array ->
        db.query(table, null, "$foreignKey = ?", arrayOf(localId.toString()), null, null, orderBy)
            .use { cursor -> while (cursor.moveToNext()) array.put(cursorAsJson(cursor)) }
    }

    private fun cursorAsJson(cursor: Cursor): JSONObject = JSONObject().also { json ->
        cursor.columnNames.forEachIndexed { index, name ->
            val value: Any = when (cursor.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                Cursor.FIELD_TYPE_BLOB -> throw IllegalArgumentException("BLOB columns are not supported for remote sync")
                else -> cursor.getString(index)
            }
            json.put(name, value)
        }
    }

    private fun deviceId(): String {
        val prefs = context.applicationContext.getSharedPreferences("mtt_remote_sync_identity", Context.MODE_PRIVATE)
        prefs.getString("device_id", null)?.let { existing ->
            if (runCatching { UUID.fromString(existing) }.isSuccess) return existing
        }
        val generated = UUID.randomUUID().toString()
        check(prefs.edit().putString("device_id", generated).commit()) { "Could not persist remote sync device identity" }
        return generated
    }
}
