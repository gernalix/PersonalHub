package com.gernalix.personalhub.core.database.capsules.mutationevents

import androidx.sqlite.db.SupportSQLiteDatabase

/** Captures domain facts in the same SQLite transaction as their source mutation. */
object MutationEventCapture {
    private data class Source(
        val table: String,
        val module: String,
        val entity: String,
        val key: String,
        val label: String?,
        val fields: List<String>,
    )

    private val sources = listOf(
        Source("sessions", "timer", "session", "id", "title", listOf("title", "start_ms", "end_ms", "expected_end_ms", "deleted_at_ms")),
        Source("hub_tags", "tags", "tag", "id", "name", listOf("name", "description", "icon", "color", "archived", "pinned")),
        Source("places", "places", "place", "uuid", "nickname", listOf("nickname", "address", "notes", "archived")),
        Source("substances", "substances", "substance", "id", "name", listOf("name", "stock_current", "stock_unit", "dose_per_intake", "dose_unit", "archived")),
        Source("intake_events", "substances", "intake", "id", null, listOf("substance_id", "dose", "dose_unit", "quantity", "timestamp_utc")),
        Source("finance_transactions", "money", "transaction", "id", null, listOf("amount", "currency", "notes", "occurredAt", "accountId", "titleId", "placeId")),
        Source("contact_fields", "people", "field", "id", null, listOf("contact_id", "field_type", "value", "description", "is_primary")),
    )

    fun install(db: SupportSQLiteDatabase) {
        MutationEventStore.install(db)
        sources.filter { tableExists(db, it.table) }.forEach { source ->
            val columns = columns(db, source.table)
            check(source.key in columns && source.fields.all { it in columns }) {
                "Mutation source schema changed: ${source.table}"
            }
            listOf("INSERT", "UPDATE", "DELETE").forEach { operation ->
                val name = "mutation_${source.table}_$operation"
                db.execSQL("DROP TRIGGER IF EXISTS `$name`")
                db.execSQL(trigger(name, source, operation))
            }
        }
    }

    private fun trigger(name: String, source: Source, operation: String): String {
        val before = if (operation == "INSERT") "NULL" else payload(source.fields, "OLD", if (operation == "UPDATE") "NEW" else null)
        val after = if (operation == "DELETE") "NULL" else payload(source.fields, "NEW", if (operation == "UPDATE") "OLD" else null)
        val action = when (operation) { "INSERT" -> "created"; "UPDATE" -> "updated"; else -> "deleted" }
        val row = if (operation == "DELETE") "OLD" else "NEW"
        val changed = source.fields.joinToString(" OR ") { "OLD.`$it` IS NOT NEW.`$it`" }
        val condition = if (operation == "UPDATE") " WHEN $changed" else ""
        val transactionId = "COALESCE((SELECT group_id FROM hub_git_edit_context WHERE id=1),lower(hex(randomblob(16))))"
        val sequence = "(SELECT COALESCE(MAX(sequence),0)+1 FROM mutation_events WHERE transaction_id=$transactionId)"
        val actor = "COALESCE((SELECT actor FROM hub_git_edit_context WHERE id=1),'user')"
        val sourceName = "COALESCE((SELECT source FROM hub_git_edit_context WHERE id=1),'personalhub')"
        val label = source.label?.let { "json_object('name',$row.`$it`)" } ?: "'{}'"
        return """CREATE TRIGGER `$name` AFTER $operation ON `${source.table}`$condition BEGIN
            INSERT INTO mutation_events(event_id,occurred_at,transaction_id,sequence,module,event_type,
                entity_type,entity_id,actor_type,actor_source,before_json,after_json,context_json,schema_version)
            VALUES(lower(hex(randomblob(16))),CAST((julianday('now')-2440587.5)*86400000 AS INTEGER),
                $transactionId,$sequence,'${source.module}','${source.module}.${source.entity}.$action',
                '${source.entity}',CAST($row.`${source.key}` AS TEXT),$actor,$sourceName,
                $before,$after,$label,1);
        END""".trimIndent()
    }

    /** UPDATE payloads contain only changed semantic fields, never timestamps or bookkeeping. */
    private fun payload(fields: List<String>, row: String, comparison: String?): String {
        val parts = fields.map { field ->
            val value = "'\"$field\":' || json_quote($row.`$field`) || ','"
            if (comparison == null) value
            else "CASE WHEN $row.`$field` IS NOT $comparison.`$field` THEN $value ELSE '' END"
        }
        return "json('{' || rtrim(${parts.joinToString(" || ") { "($it)" }}, ',') || '}')"
    }

    private fun tableExists(db: SupportSQLiteDatabase, name: String): Boolean =
        db.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)).use { it.moveToFirst() }

    private fun columns(db: SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }
}
