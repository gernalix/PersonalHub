package com.gernalix.personalhub.core.database.capsules.mutationevents

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject
import java.util.UUID

/** Semantic facts shared by History, Audit and Undo. Raw row journals remain separate. */
data class MutationEvent(
    val eventId: String,
    val occurredAt: Long,
    val transactionId: String,
    val sequence: Int,
    val module: String,
    val eventType: String,
    val entityType: String,
    val entityId: String?,
    val actorType: String,
    val actorSource: String?,
    val beforeJson: String?,
    val afterJson: String?,
    val contextJson: String,
    val schemaVersion: Int,
)

data class MutationEventDraft(
    val transactionId: String,
    val module: String,
    val eventType: String,
    val entityType: String,
    val entityId: String?,
    val actorType: String = "user",
    val actorSource: String? = "personalhub",
    val beforeJson: String? = null,
    val afterJson: String? = null,
    val contextJson: String = "{}",
    val occurredAt: Long = System.currentTimeMillis(),
)

object MutationEventStore {
    const val TABLE = "mutation_events"
    const val SCHEMA_VERSION = 1

    fun newTransactionId(): String = UUID.randomUUID().toString()

    fun install(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS mutation_events (
                event_id TEXT NOT NULL PRIMARY KEY,
                occurred_at INTEGER NOT NULL,
                transaction_id TEXT NOT NULL,
                sequence INTEGER NOT NULL,
                module TEXT NOT NULL,
                event_type TEXT NOT NULL,
                entity_type TEXT NOT NULL,
                entity_id TEXT,
                actor_type TEXT NOT NULL,
                actor_source TEXT,
                before_json TEXT,
                after_json TEXT,
                context_json TEXT NOT NULL DEFAULT '{}',
                schema_version INTEGER NOT NULL,
                UNIQUE(transaction_id, sequence)
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_mutation_events_occurred_at ON mutation_events(occurred_at DESC, event_id DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_mutation_events_transaction_id ON mutation_events(transaction_id, sequence)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_mutation_events_event_type ON mutation_events(event_type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_mutation_events_entity ON mutation_events(entity_type, entity_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_mutation_events_module ON mutation_events(module, occurred_at DESC)")
    }

    /** Caller keeps this in the same database transaction as the domain write. */
    fun append(db: SupportSQLiteDatabase, draft: MutationEventDraft): MutationEvent {
        require(draft.transactionId.isNotBlank() && draft.module.isNotBlank() && draft.entityType.isNotBlank())
        require(draft.eventType.startsWith("${draft.module}."))
        require(draft.eventType.count { it == '.' } >= 2)
        require(draft.actorType in setOf("user", "system", "import", "migration", "sync", "automation"))
        val before = draft.beforeJson?.let(::JSONObject)
        val after = draft.afterJson?.let(::JSONObject)
        JSONObject(draft.contextJson)
        val action = draft.eventType.substringAfterLast('.')
        when (action) {
            "created", "added" -> require(before == null && after != null)
            "deleted", "removed" -> require(before != null && after == null)
            else -> {
                require(before != null && after != null)
                val keys = before.keys().asSequence().toSet()
                require(keys == after.keys().asSequence().toSet())
                require(keys.isNotEmpty())
                require(keys.any { before.opt(it) != after.opt(it) })
            }
        }
        val sequence = db.query(
            "SELECT COALESCE(MAX(sequence), 0) + 1 FROM mutation_events WHERE transaction_id=?",
            arrayOf(draft.transactionId),
        ).use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) }
        val event = MutationEvent(
            eventId = UUID.randomUUID().toString(),
            occurredAt = draft.occurredAt,
            transactionId = draft.transactionId,
            sequence = sequence,
            module = draft.module,
            eventType = draft.eventType,
            entityType = draft.entityType,
            entityId = draft.entityId,
            actorType = draft.actorType,
            actorSource = draft.actorSource,
            beforeJson = before?.toString(),
            afterJson = after?.toString(),
            contextJson = JSONObject(draft.contextJson).toString(),
            schemaVersion = SCHEMA_VERSION,
        )
        db.execSQL(
            """INSERT INTO mutation_events(event_id,occurred_at,transaction_id,sequence,module,event_type,
                entity_type,entity_id,actor_type,actor_source,before_json,after_json,context_json,schema_version)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)""".trimIndent(),
            arrayOf(event.eventId, event.occurredAt, event.transactionId, event.sequence, event.module,
                event.eventType, event.entityType, event.entityId, event.actorType, event.actorSource,
                event.beforeJson, event.afterJson, event.contextJson, event.schemaVersion),
        )
        return event
    }

    fun recent(db: SupportSQLiteDatabase, limit: Int = 1000): List<MutationEvent> {
        require(limit in 1..10_000)
        return db.query(
            """SELECT event_id,occurred_at,transaction_id,sequence,module,event_type,entity_type,
                entity_id,actor_type,actor_source,before_json,after_json,context_json,schema_version
                FROM mutation_events ORDER BY occurred_at DESC,event_id DESC LIMIT ?""".trimIndent(),
            arrayOf(limit),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(read(cursor))
            }
        }
    }

    fun byTransaction(db: SupportSQLiteDatabase, transactionId: String): List<MutationEvent> = db.query(
        """SELECT event_id,occurred_at,transaction_id,sequence,module,event_type,entity_type,
            entity_id,actor_type,actor_source,before_json,after_json,context_json,schema_version
            FROM mutation_events WHERE transaction_id=? ORDER BY sequence""".trimIndent(),
        arrayOf(transactionId),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(read(cursor)) } }

    private fun read(cursor: Cursor) = MutationEvent(
        eventId = cursor.getString(0), occurredAt = cursor.getLong(1),
        transactionId = cursor.getString(2), sequence = cursor.getInt(3),
        module = cursor.getString(4), eventType = cursor.getString(5),
        entityType = cursor.getString(6), entityId = cursor.getString(7),
        actorType = cursor.getString(8), actorSource = cursor.getString(9),
        beforeJson = cursor.getString(10), afterJson = cursor.getString(11),
        contextJson = cursor.getString(12), schemaVersion = cursor.getInt(13),
    )
}
