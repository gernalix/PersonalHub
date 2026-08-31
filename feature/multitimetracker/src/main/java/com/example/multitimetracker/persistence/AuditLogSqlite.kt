package com.example.multitimetracker.persistence

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import org.json.JSONObject
import com.example.multitimetracker.util.CapsuleAudit
import com.example.multitimetracker.util.CapsuleWriteApi

data class AuditEventRow(
    val id: Long,
    val tsMs: Long,
    val isSystem: Boolean,
    val action: String,
    val entityType: String?,
    val entityId: Long?,
    val summary: String,
    val payloadJson: String?,
    val undoneAtMs: Long?
)

object AuditLogSqlite {

    /**
     * LOG LAYER ONLY.
     *
     * Architectural invariant (v390):
     * - AuditLog is an append-only *log*.
     * - Current app state must be read from the Snapshot/Session tables, never from the log.
     *
     * Why this exists:
     * - It makes future "Time Travel" (replay log into a past snapshot) possible.
     * - It makes future Multi-DB isolation possible (state vaults vs log streams).
     */

    @CapsuleWriteApi
    fun insert(
        context: Context,
        tsMs: Long = System.currentTimeMillis(),
        isSystem: Boolean,
        action: String,
        entityType: String? = null,
        entityId: Long? = null,
        summary: String,
        payload: JSONObject? = null
    ): Long {
        // Ensure DB file exists (SnapshotSqlite uses the same name).
        SnapshotSqlite.internalDbFile(context)

        // v390: use the SAME DB access path as the State layer to avoid helper/version drift.
        val db = SnapshotSqlite.openWritableDb(context)
        return try {
            val cv = ContentValues().apply {
                put("ts_ms", tsMs)
                put("is_system", if (isSystem) 1 else 0)
                put("action", action)
                put("entity_type", entityType)
                if (entityId != null) put("entity_id", entityId)
                put("summary", summary)
                if (payload != null) put("payload_json", payload.toString())
            }
            val id = db.insert(SnapshotSqlite.AUDIT_TABLE, null, cv)
            PersistentMutationTracker.record(context, "AuditLogSqlite.insert")
            id
        } finally {
            db.close()
        }
    }

    fun listRecent(context: Context, includeSystem: Boolean, includeUndone: Boolean, limit: Int = 500): List<AuditEventRow> {
        val db = SnapshotSqlite.openReadableDb(context)
        return try {
            val clauses = mutableListOf<String>()
            if (!includeSystem) clauses.add("is_system = 0")
            if (!includeUndone) clauses.add("undone_at_ms IS NULL")
            val where = if (clauses.isEmpty()) "" else "WHERE " + clauses.joinToString(" AND ")
            val q = "SELECT id, ts_ms, is_system, action, entity_type, entity_id, summary, payload_json, undone_at_ms " +
                "FROM ${SnapshotSqlite.AUDIT_TABLE} $where ORDER BY id DESC LIMIT $limit"
            db.rawQuery(q, emptyArray()).use { c ->
                val out = ArrayList<AuditEventRow>(c.count.coerceAtLeast(0))
                while (c.moveToNext()) {
                    out.add(c.toRow())
                }
                out
            }
        } finally {
            db.close()
        }
    }

    /**
     * Returns true if there exists a NOT-undone event for the given entity AFTER the given audit row id.
     * Used to decide whether an action can be undone safely.
     */
    fun hasLaterEventsForEntity(
        context: Context,
        entityType: String,
        entityId: Long,
        afterId: Long
    ): Boolean {
        val db = SnapshotSqlite.openReadableDb(context)
        return try {
            db.rawQuery(
                "SELECT 1 FROM ${SnapshotSqlite.AUDIT_TABLE} WHERE entity_type = ? AND entity_id = ? AND id > ? AND undone_at_ms IS NULL LIMIT 1",
                arrayOf(entityType, entityId.toString(), afterId.toString())
            ).use { c ->
                c.moveToFirst()
            }
        } finally {
            db.close()
        }
    }

    @CapsuleWriteApi
    fun clearAll(context: Context) {
        CapsuleAudit.auditPersistenceWrite("AuditLogSqlite.clearAll")
        val db = SnapshotSqlite.openWritableDb(context)
        try {
            db.delete(SnapshotSqlite.AUDIT_TABLE, null, null)
        } finally {
            db.close()
        }
        PersistentMutationTracker.record(context, "AuditLogSqlite.clearAll")
    }

fun getById(context: Context, id: Long): AuditEventRow? {
        val db = SnapshotSqlite.openReadableDb(context)
        return try {
            db.rawQuery(
                "SELECT id, ts_ms, is_system, action, entity_type, entity_id, summary, payload_json, undone_at_ms " +
                    "FROM ${SnapshotSqlite.AUDIT_TABLE} WHERE id = ? LIMIT 1",
                arrayOf(id.toString())
            ).use { c ->
                if (c.moveToFirst()) c.toRow() else null
            }
        } finally {
            db.close()
        }
    }

    fun markUndone(context: Context, id: Long, undoneAtMs: Long = System.currentTimeMillis()) {
        val db = SnapshotSqlite.openWritableDb(context)
        try {
            val cv = ContentValues().apply { put("undone_at_ms", undoneAtMs) }
            db.update(SnapshotSqlite.AUDIT_TABLE, cv, "id = ?", arrayOf(id.toString()))
        } finally {
            db.close()
        }
        PersistentMutationTracker.record(context, "AuditLogSqlite.markUndone")
    }

    private fun Cursor.toRow(): AuditEventRow {
        val id = getLong(0)
        val ts = getLong(1)
        val isSys = getInt(2) != 0
        val action = getString(3)
        val entityType = if (isNull(4)) null else getString(4)
        val entityId = if (isNull(5)) null else getLong(5)
        val summary = getString(6)
        val payload = if (isNull(7)) null else getString(7)
        val undoneAt = if (isNull(8)) null else getLong(8)
        return AuditEventRow(id, ts, isSys, action, entityType, entityId, summary, payload, undoneAt)
    }
}
