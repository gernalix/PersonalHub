package com.gernalix.personalhub.core.database

import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.core.database.capsules.sync.SyncJournal

object HubActivityUndoEffect {
    const val NONE = "NONE"
    const val DELETED = "DELETED"
    const val LIFECYCLE_CHANGED = "LIFECYCLE_CHANGED"
}

enum class HubActivityUndoConflict {
    NOT_FOUND,
    NOT_REVERSIBLE,
    ALREADY_REVERTED,
    STALE,
    REFERENCED,
    UNSUPPORTED,
    INVALID_PAYLOAD,
}

sealed interface HubActivityUndoResult {
    data class Success(
        val entityRef: HubEntityRef?,
        val effect: String = HubActivityUndoEffect.NONE,
    ) : HubActivityUndoResult

    data class Conflict(val reason: HubActivityUndoConflict) : HubActivityUndoResult
}

/**
 * Conservative compensating undo for the global activity register.
 *
 * The log is never rewritten or deleted. Undo first verifies the current canonical state, performs
 * the inverse mutation through the same SQLite transaction, lets activity capture append the
 * compensating event, and only then marks the original event REVERTED. Unsupported or stale cases
 * fail closed.
 */
object HubActivityUndoEngine {
    private val reversibleRowTables = setOf("places", "finance_accounts")

    suspend fun undo(database: PersonalHubDatabase, activityId: String): HubActivityUndoResult =
        database.withTransaction {
            val activity = database.activityDao().byId(activityId)
                ?: return@withTransaction HubActivityUndoResult.Conflict(HubActivityUndoConflict.NOT_FOUND)
            if (!activity.reversible) {
                return@withTransaction HubActivityUndoResult.Conflict(HubActivityUndoConflict.NOT_REVERSIBLE)
            }
            if (activity.status != HubActivityStatus.ACTIVE || activity.revertedAt != null) {
                return@withTransaction HubActivityUndoResult.Conflict(HubActivityUndoConflict.ALREADY_REVERTED)
            }

            when (activity.payloadKind) {
                HubActivityPayloadKind.ROW_V1 -> undoCanonicalRow(database, activity)
                HubActivityPayloadKind.PEOPLE_EVENT_V1 -> undoPeopleContact(database, activity)
                else -> HubActivityUndoResult.Conflict(HubActivityUndoConflict.UNSUPPORTED)
            }
        }

    private suspend fun undoCanonicalRow(
        database: PersonalHubDatabase,
        activity: HubActivityEntity,
    ): HubActivityUndoResult {
        if (activity.sourceTable !in reversibleRowTables) {
            return HubActivityUndoResult.Conflict(HubActivityUndoConflict.UNSUPPORTED)
        }
        val columns = activity.payloadColumns?.split(',')?.filter(String::isNotBlank).orEmpty()
        val afterPayload = activity.afterPayload
            ?: return HubActivityUndoResult.Conflict(HubActivityUndoConflict.INVALID_PAYLOAD)
        if (columns.isEmpty()) {
            return HubActivityUndoResult.Conflict(HubActivityUndoConflict.INVALID_PAYLOAD)
        }
        val afterValues = runCatching { SyncJournal.keyValues(afterPayload).toList() }.getOrNull()
            ?: return HubActivityUndoResult.Conflict(HubActivityUndoConflict.INVALID_PAYLOAD)
        if (afterValues.size != columns.size) {
            return HubActivityUndoResult.Conflict(HubActivityUndoConflict.INVALID_PAYLOAD)
        }

        val db = database.openHelper.writableDatabase
        val primaryKeys = primaryKeys(db, activity.sourceTable)
        if (primaryKeys.size != 1) {
            return HubActivityUndoResult.Conflict(HubActivityUndoConflict.UNSUPPORTED)
        }
        val primaryKey = primaryKeys.single()
        val primaryKeyIndex = columns.indexOf(primaryKey)
        if (primaryKeyIndex < 0) {
            return HubActivityUndoResult.Conflict(HubActivityUndoConflict.INVALID_PAYLOAD)
        }
        val primaryKeyValue = afterValues[primaryKeyIndex]
        val currentPayload = currentPayload(db, activity.sourceTable, columns, primaryKey, primaryKeyValue)
            ?: return markStale(database, activity)
        if (currentPayload != afterPayload) {
            return markStale(database, activity)
        }

        setUndoContext(db, activity.id)
        try {
            if (activity.beforePayload == null) {
                if (hasForeignKeyReference(db, activity.sourceTable, primaryKey, primaryKeyValue)) {
                    database.activityDao().updateStatus(activity.id, HubActivityStatus.CONFLICT, null)
                    return HubActivityUndoResult.Conflict(HubActivityUndoConflict.REFERENCED)
                }
                db.execSQL(
                    "DELETE FROM ${identifier(activity.sourceTable)} WHERE ${identifier(primaryKey)}=?",
                    arrayOf(primaryKeyValue),
                )
            } else {
                val beforeValues = runCatching { SyncJournal.keyValues(activity.beforePayload).toList() }.getOrNull()
                    ?: return HubActivityUndoResult.Conflict(HubActivityUndoConflict.INVALID_PAYLOAD)
                if (beforeValues.size != columns.size) {
                    return HubActivityUndoResult.Conflict(HubActivityUndoConflict.INVALID_PAYLOAD)
                }
                val mutableColumns = columns.filterNot { it == primaryKey }
                if (mutableColumns.isEmpty()) {
                    return HubActivityUndoResult.Conflict(HubActivityUndoConflict.UNSUPPORTED)
                }
                val assignments = mutableColumns.joinToString(",") { "${identifier(it)}=?" }
                val args = mutableColumns.map { beforeValues[columns.indexOf(it)] }.toTypedArray() + primaryKeyValue
                db.execSQL(
                    "UPDATE ${identifier(activity.sourceTable)} SET $assignments WHERE ${identifier(primaryKey)}=?",
                    args,
                )
            }
        } finally {
            clearUndoContext(db)
        }

        val revertedAt = System.currentTimeMillis()
        database.activityDao().updateStatus(activity.id, HubActivityStatus.REVERTED, revertedAt)
        val ref = activity.toEntityRef()
        val effect = if (activity.beforePayload == null) HubActivityUndoEffect.DELETED else HubActivityUndoEffect.LIFECYCLE_CHANGED
        return HubActivityUndoResult.Success(ref, effect)
    }

    private suspend fun undoPeopleContact(
        database: PersonalHubDatabase,
        activity: HubActivityEntity,
    ): HubActivityUndoResult {
        if (activity.sourceTable != "contact_events") {
            return HubActivityUndoResult.Conflict(HubActivityUndoConflict.UNSUPPORTED)
        }
        val eventId = activity.sourceRowKey?.toLongOrNull()
            ?: return HubActivityUndoResult.Conflict(HubActivityUndoConflict.INVALID_PAYLOAD)
        val db = database.openHelper.writableDatabase
        val event = db.query(
            "SELECT contact_id, lower(entity_type), lower(action_type), occurred_at FROM contact_events WHERE id=? LIMIT 1",
            arrayOf(eventId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else PeopleEvent(
                contactId = cursor.getLong(0),
                entityType = cursor.getString(1),
                actionType = cursor.getString(2),
                occurredAt = cursor.getLong(3),
            )
        } ?: return markStale(database, activity)

        if (event.entityType != "contact" || event.actionType !in setOf("created", "deleted")) {
            return HubActivityUndoResult.Conflict(HubActivityUndoConflict.UNSUPPORTED)
        }
        val hasLaterSemanticChange = db.query(
            """
            SELECT 1 FROM contact_events
            WHERE contact_id=? AND occurred_at>? AND lower(action_type) <> 'opened'
            LIMIT 1
            """.trimIndent(),
            arrayOf(event.contactId, event.occurredAt),
        ).use { it.moveToFirst() }
        if (hasLaterSemanticChange) {
            return markStale(database, activity)
        }

        val contactState = db.query(
            "SELECT deleted_at FROM contacts WHERE id=? LIMIT 1",
            arrayOf(event.contactId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                false to null
            } else {
                true to if (cursor.isNull(0)) null else cursor.getLong(0)
            }
        }
        if (!contactState.first) {
            return markStale(database, activity)
        }
        val currentDeletedAt = contactState.second
        val undoingCreate = event.actionType == "created"
        if ((undoingCreate && currentDeletedAt != null) || (!undoingCreate && currentDeletedAt == null)) {
            return markStale(database, activity)
        }

        val now = System.currentTimeMillis()
        setUndoContext(db, activity.id)
        try {
            if (undoingCreate) {
                db.execSQL("UPDATE contacts SET deleted_at=?, updated_at=? WHERE id=?", arrayOf(now, now, event.contactId))
                insertPeopleCompensation(db, event.contactId, "Deleted", "hub_undo_delete", now)
            } else {
                db.execSQL("UPDATE contacts SET deleted_at=NULL, updated_at=? WHERE id=?", arrayOf(now, event.contactId))
                insertPeopleCompensation(db, event.contactId, "Restored", "hub_undo_restore", now)
            }
        } finally {
            clearUndoContext(db)
        }
        database.activityDao().updateStatus(activity.id, HubActivityStatus.REVERTED, now)
        return HubActivityUndoResult.Success(
            activity.toEntityRef(),
            if (undoingCreate) HubActivityUndoEffect.DELETED else HubActivityUndoEffect.LIFECYCLE_CHANGED,
        )
    }

    private suspend fun markStale(
        database: PersonalHubDatabase,
        activity: HubActivityEntity,
    ): HubActivityUndoResult.Conflict {
        database.activityDao().updateStatus(activity.id, HubActivityStatus.CONFLICT, null)
        return HubActivityUndoResult.Conflict(HubActivityUndoConflict.STALE)
    }

    private fun insertPeopleCompensation(
        db: SupportSQLiteDatabase,
        contactId: Long,
        actionType: String,
        eventType: String,
        occurredAt: Long,
    ) {
        db.execSQL(
            """
            INSERT INTO contact_events(
                contact_id, entity_type, action_type, event_type, field_type,
                old_value, new_value, occurred_at, metadata_json
            ) VALUES(?, 'Contact', ?, ?, NULL, NULL, NULL, ?, NULL)
            """.trimIndent(),
            arrayOf(contactId, actionType, eventType, occurredAt),
        )
    }

    private fun HubActivityEntity.toEntityRef(): HubEntityRef? {
        val kind = entityKind ?: return null
        val canonical = entityId ?: return null
        return HubEntityRef(moduleId, kind, canonical)
    }

    private fun setUndoContext(db: SupportSQLiteDatabase, activityId: String) {
        db.execSQL(
            "INSERT OR REPLACE INTO ${identifier(HubActivityCapture.UNDO_CONTEXT_TABLE)}(id, original_activity_id) VALUES(1, ?)",
            arrayOf(activityId),
        )
    }

    private fun clearUndoContext(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM ${identifier(HubActivityCapture.UNDO_CONTEXT_TABLE)} WHERE id=1")
    }

    private fun primaryKeys(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info(${identifier(table)})").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val position = cursor.getInt(5)
                    if (position > 0) add(position to cursor.getString(1))
                }
            }.sortedBy { it.first }.map { it.second }
        }

    private fun currentPayload(
        db: SupportSQLiteDatabase,
        table: String,
        columns: List<String>,
        primaryKey: String,
        primaryKeyValue: Any?,
    ): String? {
        val expression = columns.joinToString(" || ':' || ") { "hex(quote(${identifier(it)}))" }
        return db.query(
            "SELECT $expression FROM ${identifier(table)} WHERE ${identifier(primaryKey)}=? LIMIT 1",
            arrayOf(primaryKeyValue),
        ).use { if (it.moveToFirst()) it.getString(0) else null }
    }

    private fun hasForeignKeyReference(
        db: SupportSQLiteDatabase,
        parentTable: String,
        parentColumn: String,
        parentValue: Any?,
    ): Boolean {
        val tables = db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        for (child in tables) {
            if (child == parentTable) continue
            val childColumns = db.query("PRAGMA foreign_key_list(${identifier(child)})").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val referencedTable = cursor.getString(2)
                        val fromColumn = cursor.getString(3)
                        val toColumn = cursor.getString(4)
                        if (referencedTable == parentTable && toColumn == parentColumn) add(fromColumn)
                    }
                }
            }
            for (childColumn in childColumns) {
                val exists = db.query(
                    "SELECT 1 FROM ${identifier(child)} WHERE ${identifier(childColumn)}=? LIMIT 1",
                    arrayOf(parentValue),
                ).use { it.moveToFirst() }
                if (exists) return true
            }
        }
        return false
    }

    private fun identifier(value: String): String = "`" + value.replace("`", "``") + "`"

    private data class PeopleEvent(
        val contactId: Long,
        val entityType: String,
        val actionType: String,
        val occurredAt: Long,
    )
}
