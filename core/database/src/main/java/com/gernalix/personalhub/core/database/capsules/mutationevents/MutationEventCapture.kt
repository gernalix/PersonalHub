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
        val predicate: String? = null,
    )

    private val sources = listOf(
        Source("sessions", "timer", "session", "id", "title", listOf("title", "start_ms", "end_ms", "expected_end_ms", "deleted_at_ms")),
        Source("hub_tags", "tags", "tag", "id", "name", listOf("name", "description", "icon", "color", "archived", "pinned")),
        Source("places", "places", "place", "uuid", "nickname", listOf("nickname", "address", "notes", "archived")),
        Source("substances", "substances", "substance", "id", "name", listOf("name", "stock_current", "stock_unit", "dose_per_intake", "dose_unit", "archived")),
        Source("intake_events", "substances", "intake", "id", null, listOf("substance_id", "dose", "dose_unit", "quantity", "timestamp_utc")),
        Source("finance_transactions", "money", "transaction", "id", null, listOf("amount", "currency", "notes", "occurredAt", "accountId", "titleId", "placeId")),
        Source("contact_fields", "people", "field", "id", null, listOf("contact_id", "field_type", "value", "description", "is_primary")),
        Source("hub_resources", "hub", "resource", "id", "title", listOf("title", "value"), "value LIKE '%workflowy.com/%'"),
    )

    fun install(db: SupportSQLiteDatabase) {
        MutationEventStore.install(db)
        val existingTables = db.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        sources.filter { it.table in existingTables }.forEach { source ->
            val columns = columns(db, source.table)
            check(source.key in columns && source.fields.all { it in columns }) {
                "Mutation source schema changed: ${source.table}"
            }
        }
        expectedSql(existingTables).forEach { (name, sql) ->
            db.execSQL("DROP TRIGGER IF EXISTS `$name`")
            db.execSQL(sql)
        }
    }

    /** Also used by DatabaseVault: imported snapshots must contain only our exact trigger SQL. */
    fun expectedSql(existingTables: Set<String>): Map<String, String> = buildMap {
        sources.filter { it.table in existingTables }.forEach { source ->
            listOf("INSERT", "UPDATE", "DELETE").forEach { operation ->
                val name = "mutation_${source.table}_$operation"
                put(name, trigger(name, source, operation))
            }
        }
        if (listOf("hub_contexts", "hub_context_members", "hub_entity_bindings", "hub_resources").all { it in existingTables }) {
            putAll(workflowyAssociationSql())
        }
        if (listOf("session_tags", "sessions", "tags").all { it in existingTables }) putAll(sessionTagSql())
        if ("contact_events" in existingTables && "contact_fields" in existingTables) putAll(peopleActionSql())
    }

    private fun sessionTagSql(): Map<String, String> = buildMap {
        val group = "COALESCE((SELECT group_id FROM hub_git_edit_context WHERE id=1),lower(hex(randomblob(16))))"
        val sequence = "(SELECT COALESCE(MAX(sequence),0)+1 FROM mutation_events WHERE transaction_id=$group)"
        val actor = "COALESCE((SELECT actor FROM hub_git_edit_context WHERE id=1),'user')"
        val source = "COALESCE((SELECT source FROM hub_git_edit_context WHERE id=1),'personalhub')"
        listOf("INSERT" to "tag_added", "DELETE" to "tag_removed").forEach { (operation, action) ->
            val row = if (operation == "INSERT") "NEW" else "OLD"
            val tag = "('{\"tag\":{\"id\":' || ${jsonScalar("$row.tag_id")} || ',\"name\":' || ${jsonScalar("(SELECT name FROM tags WHERE id=$row.tag_id)")} || '}}')"
            val name = jsonObject("name", "(SELECT title FROM sessions WHERE id=$row.session_id)")
            val trigger = "mutation_session_tags_$operation"
            put(trigger, """CREATE TRIGGER `$trigger` BEFORE $operation ON session_tags BEGIN
                INSERT INTO mutation_events(event_id,occurred_at,transaction_id,sequence,module,event_type,
                    entity_type,entity_id,actor_type,actor_source,before_json,after_json,context_json,schema_version,entity_kind,canonical_id)
                VALUES(lower(hex(randomblob(16))),CAST((julianday('now')-2440587.5)*86400000 AS INTEGER),
                    $group,$sequence,'timer','timer.session.$action','session',CAST($row.session_id AS TEXT),$actor,$source,
                    ${if (operation == "INSERT") "NULL" else tag},${if (operation == "DELETE") "NULL" else tag},$name,1,'timer/session',(SELECT canonical_id FROM sessions WHERE id=$row.session_id));
            END""".trimIndent())
        }
    }

    private fun peopleActionSql(): Map<String, String> {
        val group = "COALESCE((SELECT group_id FROM hub_git_edit_context WHERE id=1),lower(hex(randomblob(16))))"
        val label = "(SELECT value FROM contact_fields WHERE contact_id=NEW.contact_id AND field_type='name' LIMIT 1)"
        val named = jsonObject("name", label)
        return mapOf("mutation_people_action" to """CREATE TRIGGER mutation_people_action AFTER INSERT ON contact_events
            WHEN lower(NEW.entity_type)='contact' AND lower(NEW.action_type) IN ('created','deleted','archived')
            BEGIN
              INSERT INTO mutation_events(event_id,occurred_at,transaction_id,sequence,module,event_type,
                entity_type,entity_id,actor_type,actor_source,before_json,after_json,context_json,schema_version,entity_kind,canonical_id)
              VALUES(lower(hex(randomblob(16))),NEW.occurred_at,$group,
                (SELECT COALESCE(MAX(sequence),0)+1 FROM mutation_events WHERE transaction_id=$group),
                'people','people.person.' || lower(NEW.action_type),'person',CAST(NEW.contact_id AS TEXT),
                COALESCE((SELECT actor FROM hub_git_edit_context WHERE id=1),'user'),
                COALESCE((SELECT source FROM hub_git_edit_context WHERE id=1),'personalhub'),
                CASE WHEN lower(NEW.action_type)='created' THEN NULL WHEN lower(NEW.action_type)='archived' THEN '{"archived":false}' ELSE $named END,
                CASE WHEN lower(NEW.action_type)='deleted' THEN NULL WHEN lower(NEW.action_type)='archived' THEN '{"archived":true}' ELSE $named END,
                $named,1,'people/person',(SELECT public_id FROM contacts WHERE id=NEW.contact_id));
            END""".trimIndent())
    }

    private fun workflowyAssociationSql(): Map<String, String> {
        val group = "COALESCE((SELECT group_id FROM hub_git_edit_context WHERE id=1),lower(hex(randomblob(16))))"
        val sequence = "(SELECT COALESCE(MAX(sequence),0)+1 FROM mutation_events WHERE transaction_id=$group)"
        val actor = "COALESCE((SELECT actor FROM hub_git_edit_context WHERE id=1),'user')"
        val source = "COALESCE((SELECT source FROM hub_git_edit_context WHERE id=1),'personalhub')"
        fun anchor(contextId: String, column: String) = """(SELECT b.$column FROM hub_context_members m
            JOIN hub_entity_bindings b ON b.id=m.entity_id
            WHERE m.context_id=$contextId AND m.position=0 LIMIT 1)""".replace("\n", " ")
        fun resource(contextId: String, column: String) = """(SELECT r.$column FROM hub_context_members m
            JOIN hub_entity_bindings b ON b.id=m.entity_id JOIN hub_resources r ON r.id=b.canonical_id
            WHERE m.context_id=$contextId AND m.position=1 AND b.module_id='hub' AND b.entity_kind='resource'
            AND r.value LIKE '%workflowy.com/%' LIMIT 1)""".replace("\n", " ")
        val newResource = resource("NEW.context_id", "title")
        val oldResource = resource("OLD.id", "title")
        val newName = jsonObject("name", newResource)
        val oldName = jsonObject("name", oldResource)
        val assigned = """CREATE TRIGGER mutation_workflowy_assigned AFTER INSERT ON hub_context_members
            WHEN NEW.position=1 AND (SELECT title FROM hub_contexts WHERE id=NEW.context_id)='Workflowy'
                AND ${resource("NEW.context_id", "id")} IS NOT NULL
            BEGIN
              INSERT INTO mutation_events(event_id,occurred_at,transaction_id,sequence,module,event_type,
                entity_type,entity_id,actor_type,actor_source,before_json,after_json,context_json,schema_version,entity_kind,canonical_id)
              VALUES(lower(hex(randomblob(16))),CAST((julianday('now')-2440587.5)*86400000 AS INTEGER),
                $group,$sequence,${anchor("NEW.context_id", "module_id")},'workflowy.link.assigned',
                'link',${anchor("NEW.context_id", "canonical_id")},$actor,$source,
                NULL,$newName,$newName,1,${anchor("NEW.context_id", "module_id")} || '/' || ${anchor("NEW.context_id", "entity_kind")},${anchor("NEW.context_id", "canonical_id")});
            END""".trimIndent()
        val unlinked = """CREATE TRIGGER mutation_workflowy_unlinked BEFORE DELETE ON hub_contexts
            WHEN OLD.title='Workflowy' AND ${resource("OLD.id", "id")} IS NOT NULL
            BEGIN
              INSERT INTO mutation_events(event_id,occurred_at,transaction_id,sequence,module,event_type,
                entity_type,entity_id,actor_type,actor_source,before_json,after_json,context_json,schema_version,entity_kind,canonical_id)
              VALUES(lower(hex(randomblob(16))),CAST((julianday('now')-2440587.5)*86400000 AS INTEGER),
                $group,$sequence,${anchor("OLD.id", "module_id")},'workflowy.link.unlinked',
                'link',${anchor("OLD.id", "canonical_id")},$actor,$source,
                $oldName,NULL,$oldName,1,${anchor("OLD.id", "module_id")} || '/' || ${anchor("OLD.id", "entity_kind")},${anchor("OLD.id", "canonical_id")});
            END""".trimIndent()
        return mapOf("mutation_workflowy_assigned" to assigned, "mutation_workflowy_unlinked" to unlinked)
    }

    private fun trigger(name: String, source: Source, operation: String): String {
        val before = if (operation == "INSERT") "NULL" else payload(source.fields, "OLD", if (operation == "UPDATE") "NEW" else null)
        val after = if (operation == "DELETE") "NULL" else payload(source.fields, "NEW", if (operation == "UPDATE") "OLD" else null)
        val action = when (operation) { "INSERT" -> "created"; "UPDATE" -> "updated"; else -> "deleted" }
        val row = if (operation == "DELETE") "OLD" else "NEW"
        val changed = source.fields.joinToString(" OR ") { "OLD.`$it` IS NOT NEW.`$it`" }
        val predicate = source.predicate?.let { filter ->
            when (operation) {
                "INSERT" -> "NEW.$filter"
                "DELETE" -> "OLD.$filter"
                else -> "(NEW.$filter OR OLD.$filter)"
            }
        }
        val condition = when {
            operation == "UPDATE" && predicate != null -> " WHEN ($changed) AND $predicate"
            operation == "UPDATE" -> " WHEN $changed"
            predicate != null -> " WHEN $predicate"
            else -> ""
        }
        val transactionId = "COALESCE((SELECT group_id FROM hub_git_edit_context WHERE id=1),lower(hex(randomblob(16))))"
        val sequence = "(SELECT COALESCE(MAX(sequence),0)+1 FROM mutation_events WHERE transaction_id=$transactionId)"
        val actor = "COALESCE((SELECT actor FROM hub_git_edit_context WHERE id=1),'user')"
        val sourceName = "COALESCE((SELECT source FROM hub_git_edit_context WHERE id=1),'personalhub')"
        val label = when (source.table) {
            "intake_events" -> jsonObject("name", "(SELECT name FROM substances WHERE id=$row.substance_id)")
            "finance_transactions" -> jsonObject("name", "(SELECT name FROM finance_titles WHERE id=$row.titleId)")
            "contact_fields" -> jsonObject("name", "CASE WHEN $row.field_type='name' THEN $row.value ELSE (SELECT value FROM contact_fields WHERE contact_id=$row.contact_id AND field_type='name' LIMIT 1) END")
            else -> source.label?.let { jsonObject("name", "$row.`$it`") } ?: "'{}'"
        }
        val identity = when (source.table) {
            "contact_fields" -> "(SELECT public_id FROM contacts WHERE id=$row.contact_id)"
            "sessions", "substances", "intake_events" -> "COALESCE(NULLIF($row.canonical_id,''),(SELECT canonical_id FROM hub_entities WHERE local_table='${source.table}' AND local_key=CAST($row.`${source.key}` AS TEXT)))"
            "finance_transactions" -> "$row.uuid"
            else -> "CAST($row.`${source.key}` AS TEXT)"
        }
        val canonicalKind = when (source.table) {
            "contact_fields" -> "people/person"
            "finance_transactions" -> "soldi/transaction"
            else -> "${source.module}/${source.entity}"
        }
        return """CREATE TRIGGER `$name` AFTER $operation ON `${source.table}`$condition BEGIN
            INSERT INTO mutation_events(event_id,occurred_at,transaction_id,sequence,module,event_type,
                entity_type,entity_id,actor_type,actor_source,before_json,after_json,context_json,schema_version,entity_kind,canonical_id)
            VALUES(lower(hex(randomblob(16))),CAST((julianday('now')-2440587.5)*86400000 AS INTEGER),
                $transactionId,$sequence,'${source.module}','${source.module}.${source.entity}.$action',
                '${source.entity}',CAST($row.`${source.key}` AS TEXT),$actor,$sourceName,
                $before,$after,$label,1,'$canonicalKind',$identity);
        END""".trimIndent()
    }

    /** UPDATE payloads contain only changed semantic fields, never timestamps or bookkeeping. */
    private fun payload(fields: List<String>, row: String, comparison: String?): String {
        val parts = fields.map { field ->
            val value = "'\"$field\":' || ${jsonScalar("$row.`$field`")} || ','"
            if (comparison == null) value
            else "CASE WHEN $row.`$field` IS NOT $comparison.`$field` THEN $value ELSE '' END"
        }
        return "('{' || rtrim(${parts.joinToString(" || ") { "($it)" }}, ',') || '}')"
    }

    /** Android API 29 and Robolectric SQLite do not consistently expose JSON1 functions. */
    private fun jsonScalar(expression: String): String {
        val escaped = listOf(
            "char(92)" to "char(92)||char(92)",
            "char(34)" to "char(92)||char(34)",
            "char(10)" to "char(92)||'n'",
            "char(13)" to "char(92)||'r'",
            "char(9)" to "char(92)||'t'",
            "char(8)" to "char(92)||'b'",
            "char(12)" to "char(92)||'f'",
        ).fold("CAST(($expression) AS TEXT)") { value, (needle, replacement) ->
            "replace($value,$needle,$replacement)"
        }
        return "(CASE WHEN ($expression) IS NULL THEN 'null' " +
            "WHEN typeof(($expression)) IN ('integer','real') THEN CAST(($expression) AS TEXT) " +
            "WHEN typeof(($expression))='blob' THEN char(34)||hex(($expression))||char(34) " +
            "ELSE char(34)||$escaped||char(34) END)"
    }

    private fun jsonObject(key: String, expression: String): String =
        "('{\"$key\":' || ${jsonScalar(expression)} || '}')"

    private fun columns(db: SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }
}
