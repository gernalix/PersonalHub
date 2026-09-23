package com.gernalix.personalhub.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.text.Normalizer
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Lossless consolidation of the legacy module tag stores into the Hub binding layer. */
internal class SharedTagsMigration : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        createTables(db)
        migratePeople(db)
        migratePeopleSavedFilters(db)
        migratePlaces(db)
        migrateSoldi(db)
        rebuildFinanceCategoryColumns(db)
        migrateTimer(db)
        migratePlaceAlertTargets(db)
        refreshUsage(db)
        db.execSQL("UPDATE hub_generation SET generation=generation+1 WHERE id=1")
    }

    private fun createTables(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `hub_tags` (
                `id` TEXT NOT NULL, `namespace` TEXT NOT NULL, `kind` TEXT NOT NULL, `name` TEXT NOT NULL,
                `normalized_name` TEXT NOT NULL, `description` TEXT, `icon` TEXT, `color` TEXT,
                `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL,
                `archived` INTEGER NOT NULL, `pinned` INTEGER NOT NULL, `is_global` INTEGER NOT NULL,
                `last_used_at` INTEGER, `usage_count` INTEGER NOT NULL, `metadata_json` TEXT,
                PRIMARY KEY(`id`)
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_hub_tags_namespace_normalized_name` ON `hub_tags` (`namespace`,`normalized_name`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_hub_tags_namespace` ON `hub_tags` (`namespace`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_hub_tags_archived` ON `hub_tags` (`archived`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_hub_tags_pinned` ON `hub_tags` (`pinned`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_hub_tags_last_used_at` ON `hub_tags` (`last_used_at`)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `hub_tag_aliases` (
                `tag_id` TEXT NOT NULL, `namespace` TEXT NOT NULL, `alias` TEXT NOT NULL,
                `normalized_alias` TEXT NOT NULL, PRIMARY KEY(`tag_id`,`normalized_alias`),
                FOREIGN KEY(`tag_id`) REFERENCES `hub_tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_hub_tag_aliases_tag_id` ON `hub_tag_aliases` (`tag_id`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_hub_tag_aliases_namespace_normalized_alias` ON `hub_tag_aliases` (`namespace`,`normalized_alias`)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `hub_tag_assignments` (
                `target_binding_id` TEXT NOT NULL, `tag_id` TEXT NOT NULL, `assigned_at` INTEGER NOT NULL,
                `provenance` TEXT NOT NULL, PRIMARY KEY(`target_binding_id`,`tag_id`),
                FOREIGN KEY(`target_binding_id`) REFERENCES `hub_entity_bindings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`tag_id`) REFERENCES `hub_tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_hub_tag_assignments_target_binding_id` ON `hub_tag_assignments` (`target_binding_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_hub_tag_assignments_tag_id` ON `hub_tag_assignments` (`tag_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_hub_tag_assignments_assigned_at` ON `hub_tag_assignments` (`assigned_at`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_hub_tag_assignments_provenance` ON `hub_tag_assignments` (`provenance`)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `hub_tag_parents` (
                `child_tag_id` TEXT NOT NULL, `parent_tag_id` TEXT NOT NULL,
                PRIMARY KEY(`child_tag_id`,`parent_tag_id`),
                FOREIGN KEY(`child_tag_id`) REFERENCES `hub_tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`parent_tag_id`) REFERENCES `hub_tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_hub_tag_parents_child_tag_id` ON `hub_tag_parents` (`child_tag_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_hub_tag_parents_parent_tag_id` ON `hub_tag_parents` (`parent_tag_id`)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `hub_saved_tag_filters` (
                `id` TEXT NOT NULL, `namespace` TEXT NOT NULL, `name` TEXT NOT NULL,
                `query_json` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_hub_saved_tag_filters_namespace_name` ON `hub_saved_tag_filters` (`namespace`,`name`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_hub_saved_tag_filters_updated_at` ON `hub_saved_tag_filters` (`updated_at`)")
    }

    private fun migratePeople(db: SupportSQLiteDatabase) {
        db.query("SELECT id,name,normalized_name,created_at FROM tags ORDER BY id").use { cursor ->
            while (cursor.moveToNext()) insertTag(db, "people:${cursor.getLong(0)}", "people", cursor.getString(1), cursor.getString(2), cursor.getLong(3), cursor.getLong(3), false, null)
        }
        db.query("SELECT c.public_id,c.id,x.tag_id,x.added_at FROM contact_tags x JOIN contacts c ON c.id=x.contact_id ORDER BY c.id,x.tag_id").use { cursor ->
            while (cursor.moveToNext()) {
                val canonical = cursor.getString(0)?.takeIf(String::isNotBlank) ?: "legacy-contact:${cursor.getLong(1)}"
                val binding = ensureBinding(db, "people", "person", canonical, cursor.getLong(3))
                assign(db, binding, "people:${cursor.getLong(2)}", cursor.getLong(3))
            }
        }
    }

    private fun migratePeopleSavedFilters(db: SupportSQLiteDatabase) {
        db.query("SELECT id,public_id,title,query,created_at,updated_at FROM saved_searches ORDER BY id").use { cursor ->
            while (cursor.moveToNext()) {
                val legacyId = cursor.getLong(0)
                val includes = JSONArray()
                db.query("SELECT tag_id FROM saved_search_tags WHERE saved_search_id=? ORDER BY tag_id", arrayOf(legacyId)).use { tags ->
                    while (tags.moveToNext()) includes.put("people:${tags.getLong(0)}")
                }
                val payload = JSONObject().put("text", cursor.getString(3)).put("include", includes).put("exclude", JSONArray()).put("mode", "AND").put("no_tags", false)
                db.execSQL(
                    "INSERT OR IGNORE INTO hub_saved_tag_filters(id,namespace,name,query_json,created_at,updated_at) VALUES (?,?,?,?,?,?)",
                    arrayOf("people:${cursor.getString(1)}", "people", cursor.getString(2), payload.toString(), cursor.getLong(4), cursor.getLong(5)),
                )
            }
        }
    }

    private fun migratePlaces(db: SupportSQLiteDatabase) {
        db.query("SELECT id,name,normalized_name,created_at,updated_at FROM place_tags ORDER BY id").use { cursor ->
            while (cursor.moveToNext()) insertTag(db, "places:${cursor.getLong(0)}", "places", cursor.getString(1), cursor.getString(2), cursor.getLong(3), cursor.getLong(4), false, null)
        }
        db.query("SELECT place_uuid,tag_id FROM place_tag_cross_ref ORDER BY place_uuid,tag_id").use { cursor ->
            while (cursor.moveToNext()) {
                val binding = ensureBinding(db, "places", "place", cursor.getString(0), 0)
                assign(db, binding, "places:${cursor.getLong(1)}", 0)
            }
        }
    }

    private fun migratePlaceAlertTargets(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE `alert_place_tag_targets_new` (
                `rule_id` TEXT NOT NULL, `place_tag_id` TEXT NOT NULL,
                PRIMARY KEY(`rule_id`,`place_tag_id`),
                FOREIGN KEY(`rule_id`) REFERENCES `alert_rules`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`place_tag_id`) REFERENCES `hub_tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("INSERT INTO alert_place_tag_targets_new(rule_id,place_tag_id) SELECT rule_id,'places:' || place_tag_id FROM alert_place_tag_targets")
        db.execSQL("DROP TABLE alert_place_tag_targets")
        db.execSQL("ALTER TABLE alert_place_tag_targets_new RENAME TO alert_place_tag_targets")
        db.execSQL("CREATE INDEX `index_alert_place_tag_targets_rule_id` ON `alert_place_tag_targets` (`rule_id`)")
        db.execSQL("CREATE INDEX `index_alert_place_tag_targets_place_tag_id` ON `alert_place_tag_targets` (`place_tag_id`)")
    }

    private fun migrateSoldi(db: SupportSQLiteDatabase) {
        db.query("SELECT id,name FROM finance_tags ORDER BY id").use { cursor ->
            while (cursor.moveToNext()) insertTag(db, "soldi:${cursor.getLong(0)}", "soldi", cursor.getString(1), normalize(cursor.getString(1)), 0, 0, false, null)
        }
        db.query("SELECT t.uuid,x.tagId,t.updatedAt FROM finance_transaction_tags x JOIN finance_transactions t ON t.id=x.transactionId ORDER BY t.id,x.tagId").use { cursor ->
            while (cursor.moveToNext()) assign(db, ensureBinding(db, "soldi", "transaction", cursor.getString(0), cursor.getLong(2)), "soldi:${cursor.getLong(1)}", cursor.getLong(2))
        }
        db.query("SELECT r.id,x.tagId,r.updatedAt FROM finance_recurrence_tags x JOIN finance_recurrences r ON r.id=x.recurrenceId ORDER BY r.id,x.tagId").use { cursor ->
            while (cursor.moveToNext()) assign(db, ensureBinding(db, "soldi", "recurrence", cursor.getString(0), cursor.getLong(2)), "soldi:${cursor.getLong(1)}", cursor.getLong(2))
        }
        val categories = linkedMapOf<String, String>()
        listOf("finance_transactions", "finance_recurrences").forEach { table ->
            db.query("SELECT DISTINCT trim(category) FROM $table WHERE trim(category)!='' ORDER BY trim(category) COLLATE NOCASE").use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0)
                    val normalized = normalize(name)
                    categories.putIfAbsent(normalized, name)
                }
            }
        }
        categories.forEach { (normalized, name) ->
            insertTag(db, categoryId(normalized), "soldi.category", name, normalized, 0, 0, false, null, "CATEGORY")
        }
        db.query("SELECT uuid,category,updatedAt FROM finance_transactions WHERE trim(category)!='' ORDER BY id").use { cursor ->
            while (cursor.moveToNext()) assign(db, ensureBinding(db, "soldi", "transaction", cursor.getString(0), cursor.getLong(2)), categoryId(normalize(cursor.getString(1))), cursor.getLong(2))
        }
        db.query("SELECT id,category,updatedAt FROM finance_recurrences WHERE trim(category)!='' ORDER BY id").use { cursor ->
            while (cursor.moveToNext()) assign(db, ensureBinding(db, "soldi", "recurrence", cursor.getString(0), cursor.getLong(2)), categoryId(normalize(cursor.getString(1))), cursor.getLong(2))
        }
        migrateFinanceContexts(db)
    }

    private fun migrateFinanceContexts(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT OR IGNORE INTO hub_context_types(id,name,created_at,updated_at,locked) VALUES('finance_transaction_context','Finance transaction context',0,0,1)")
        listOf(
            arrayOf("transaction", "Transaction", "anchor", "soldi", "transaction", 0, 1, 1),
            arrayOf("person", "Person", "participant", "people", "person", 1, 0, 1),
            arrayOf("place", "Place", "merchant_place", "places", "place", 2, 0, 1),
        ).forEach { row ->
            db.execSQL(
                "INSERT OR IGNORE INTO hub_context_type_fields(context_type_id,field_id,position,label,role,accepted_module_id,accepted_entity_kind,accepted_capability,min_cardinality,max_cardinality) VALUES('finance_transaction_context',?,?,?,?,?,?,NULL,?,?)",
                arrayOf(row[0], row[5], row[1], row[2], row[3], row[4], row[6], row[7]),
            )
        }
        db.query("SELECT uuid,personId,placeId,updatedAt FROM finance_transactions WHERE personId IS NOT NULL OR placeId IS NOT NULL ORDER BY id").use { cursor ->
            while (cursor.moveToNext()) {
                val uuid = cursor.getString(0)
                val updatedAt = cursor.getLong(3)
                val anchor = ensureBinding(db, "soldi", "transaction", uuid, updatedAt)
                val members = mutableListOf(anchor to "anchor")
                if (!cursor.isNull(1)) {
                    val publicId = db.query("SELECT public_id FROM contacts WHERE id=?", arrayOf(cursor.getLong(1))).use { contact ->
                        if (contact.moveToFirst()) contact.getString(0) else null
                    }
                    publicId?.takeIf(String::isNotBlank)?.let { members += ensureBinding(db, "people", "person", it, updatedAt) to "participant" }
                }
                cursor.getString(2)?.takeIf(String::isNotBlank)?.let { members += ensureBinding(db, "places", "place", it, updatedAt) to "merchant_place" }
                if (members.size > 1) {
                    val contextId = "finance-context:$uuid"
                    db.execSQL("INSERT OR IGNORE INTO hub_contexts(id,context_type_id,title,created_at,updated_at) VALUES (?,'finance_transaction_context',NULL,?,?)", arrayOf(contextId, updatedAt, updatedAt))
                    members.forEachIndexed { index, (binding, role) ->
                        db.execSQL("INSERT OR IGNORE INTO hub_context_members(context_id,entity_id,role,position) VALUES (?,?,?,?)", arrayOf(contextId, binding, role, index))
                    }
                }
            }
        }
        db.execSQL("UPDATE finance_transactions SET personId=NULL,placeId=NULL WHERE personId IS NOT NULL OR placeId IS NOT NULL")

        db.execSQL("INSERT OR IGNORE INTO hub_context_types(id,name,created_at,updated_at,locked) VALUES('finance_recurrence_context','Finance recurrence context',0,0,1)")
        listOf(
            arrayOf("recurrence", "Recurrence", "anchor", "soldi", "recurrence", 0, 1, 1),
            arrayOf("person", "Person", "participant", "people", "person", 1, 0, 1),
            arrayOf("place", "Place", "merchant_place", "places", "place", 2, 0, 1),
        ).forEach { row ->
            db.execSQL(
                "INSERT OR IGNORE INTO hub_context_type_fields(context_type_id,field_id,position,label,role,accepted_module_id,accepted_entity_kind,accepted_capability,min_cardinality,max_cardinality) VALUES('finance_recurrence_context',?,?,?,?,?,?,NULL,?,?)",
                arrayOf(row[0], row[5], row[1], row[2], row[3], row[4], row[6], row[7]),
            )
        }
        db.query("SELECT id,personId,placeId,updatedAt FROM finance_recurrences WHERE personId IS NOT NULL OR placeId IS NOT NULL ORDER BY id").use { cursor ->
            while (cursor.moveToNext()) {
                val recurrenceId = cursor.getString(0)
                val updatedAt = cursor.getLong(3)
                val anchor = ensureBinding(db, "soldi", "recurrence", recurrenceId, updatedAt)
                val members = mutableListOf(anchor to "anchor")
                if (!cursor.isNull(1)) {
                    val publicId = db.query("SELECT public_id FROM contacts WHERE id=?", arrayOf(cursor.getLong(1))).use { contact ->
                        if (contact.moveToFirst()) contact.getString(0) else null
                    }
                    publicId?.takeIf(String::isNotBlank)?.let { members += ensureBinding(db, "people", "person", it, updatedAt) to "participant" }
                }
                cursor.getString(2)?.takeIf(String::isNotBlank)?.let { members += ensureBinding(db, "places", "place", it, updatedAt) to "merchant_place" }
                if (members.size > 1) {
                    val contextId = "finance-recurrence-context:$recurrenceId"
                    db.execSQL("INSERT OR IGNORE INTO hub_contexts(id,context_type_id,title,created_at,updated_at) VALUES (?,'finance_recurrence_context',NULL,?,?)", arrayOf(contextId, updatedAt, updatedAt))
                    members.forEachIndexed { index, (binding, role) ->
                        db.execSQL("INSERT OR IGNORE INTO hub_context_members(context_id,entity_id,role,position) VALUES (?,?,?,?)", arrayOf(contextId, binding, role, index))
                    }
                }
            }
        }
        db.execSQL("UPDATE finance_recurrences SET personId=NULL,placeId=NULL WHERE personId IS NOT NULL OR placeId IS NOT NULL")
    }

    private fun rebuildFinanceCategoryColumns(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE `finance_transactions_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `accountId` TEXT NOT NULL, `uuid` TEXT NOT NULL,
                `titleId` INTEGER, `productId` INTEGER, `amount` TEXT NOT NULL, `currency` TEXT NOT NULL,
                `chainId` INTEGER, `placeId` TEXT, `fromReceipt` INTEGER NOT NULL, `notes` TEXT NOT NULL,
                `occurredAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                `personId` INTEGER DEFAULT NULL, `macroId` TEXT DEFAULT NULL, `recurrenceId` TEXT DEFAULT NULL,
                `occurrenceKey` TEXT DEFAULT NULL, `reminderAt` INTEGER DEFAULT NULL,
                FOREIGN KEY(`accountId`) REFERENCES `finance_accounts`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`titleId`) REFERENCES `finance_titles`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`productId`) REFERENCES `finance_products`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`chainId`) REFERENCES `finance_chains`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`placeId`) REFERENCES `places`(`uuid`) ON UPDATE NO ACTION ON DELETE RESTRICT
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO finance_transactions_new(id,accountId,uuid,titleId,productId,amount,currency,chainId,placeId,fromReceipt,notes,occurredAt,createdAt,updatedAt,personId,macroId,recurrenceId,occurrenceKey,reminderAt)
            SELECT id,accountId,uuid,titleId,productId,amount,currency,chainId,placeId,fromReceipt,notes,occurredAt,createdAt,updatedAt,personId,macroId,recurrenceId,occurrenceKey,reminderAt FROM finance_transactions
        """.trimIndent())
        db.execSQL("DROP TABLE finance_transactions")
        db.execSQL("ALTER TABLE finance_transactions_new RENAME TO finance_transactions")
        listOf(
            "accountId", "titleId", "productId", "chainId", "placeId", "occurredAt", "personId", "macroId", "recurrenceId", "reminderAt",
        ).forEach { column -> db.execSQL("CREATE INDEX `index_finance_transactions_$column` ON `finance_transactions` (`$column`)") }
        db.execSQL("CREATE UNIQUE INDEX `index_finance_transactions_uuid` ON `finance_transactions` (`uuid`)")
        db.execSQL("CREATE INDEX `index_finance_transactions_recurrenceId_occurrenceKey` ON `finance_transactions` (`recurrenceId`,`occurrenceKey`)")

        db.execSQL("""
            CREATE TABLE `finance_recurrences_new` (
                `id` TEXT NOT NULL, `title` TEXT NOT NULL, `amount` TEXT NOT NULL, `currency` TEXT NOT NULL,
                `accountId` TEXT NOT NULL, `personId` INTEGER, `chain` TEXT NOT NULL, `placeId` TEXT,
                `notes` TEXT NOT NULL, `dayOfMonth` INTEGER, `lastBusinessDay` INTEGER NOT NULL,
                `startDate` TEXT NOT NULL, `endDate` TEXT, `reminderDaysBefore` INTEGER, `enabled` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `kind` TEXT NOT NULL,
                `targetAccountId` TEXT, `targetAmount` TEXT, `quotedRate` TEXT, `feeAmount` TEXT, `feeCurrency` TEXT,
                PRIMARY KEY(`id`), FOREIGN KEY(`accountId`) REFERENCES `finance_accounts`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO finance_recurrences_new(id,title,amount,currency,accountId,personId,chain,placeId,notes,dayOfMonth,lastBusinessDay,startDate,endDate,reminderDaysBefore,enabled,createdAt,updatedAt,kind,targetAccountId,targetAmount,quotedRate,feeAmount,feeCurrency)
            SELECT id,title,amount,currency,accountId,personId,chain,placeId,notes,dayOfMonth,lastBusinessDay,startDate,endDate,reminderDaysBefore,enabled,createdAt,updatedAt,kind,targetAccountId,targetAmount,quotedRate,feeAmount,feeCurrency FROM finance_recurrences
        """.trimIndent())
        db.execSQL("DROP TABLE finance_recurrences")
        db.execSQL("ALTER TABLE finance_recurrences_new RENAME TO finance_recurrences")
        listOf("accountId", "targetAccountId", "enabled", "startDate").forEach { column ->
            db.execSQL("CREATE INDEX `index_finance_recurrences_$column` ON `finance_recurrences` (`$column`)")
        }
    }

    private fun migrateTimer(db: SupportSQLiteDatabase) {
        val root = db.query("SELECT json FROM snapshot ORDER BY saved_at_ms DESC LIMIT 1").use { cursor ->
            if (cursor.moveToFirst()) runCatching { JSONObject(cursor.getString(0)) }.getOrNull() else null
        }
        val tags = linkedMapOf<Long, JSONObject>()
        root?.optJSONArray("tags")?.forEachObject { value -> value.optLong("id", -1).takeIf { it >= 0 }?.let { tags[it] = value } }
        val now = linkedSetOf<Long>()
        val events = linkedSetOf<Long>()
        val since = linkedSetOf<Long>()
        root?.let { collectTimerReferences(it, now, events, since) }
        collectRelationalTimerReferences(db, now, events)
        tags.keys.filterTo(now) { it !in now && it !in events && it !in since }
        (now + events + since).forEach { legacyId ->
            val source = tags[legacyId]
            val name = source?.optString("name")?.takeIf(String::isNotBlank) ?: "tag_$legacyId"
            if (legacyId in now) insertTimerTag(db, "timer.now", legacyId, name, source)
            if (legacyId in events) insertTimerTag(db, "timer.events", legacyId, name, source)
            if (legacyId in since) insertTimerTag(db, "timer.since_when", legacyId, name, source)
        }
        migrateTimerEdges(db, "session_tags", "session_id", "timer", "session", "timer.now", now)
        migrateTimerEdges(db, "quick_event_entry_tags", "entry_id", "timer", "quick_event_entry", "timer.events", events)
        migrateTimerEdges(db, "quick_event_template_tags", "template_id", "timer", "quick_event_template", "timer.events", events)
        migrateTimerEdges(db, "quick_event_macro_tags", "macro_id", "timer", "quick_event_macro", "timer.events", events)
        root?.optJSONArray("lifePeriods")?.forEachObject { period ->
            val periodId = period.optLong("id", -1).takeIf { it >= 0 } ?: return@forEachObject
            period.optJSONArray("tagIds")?.forEachLong { tagId ->
                if (tagId in since) assign(db, ensureBinding(db, "timer", "life_period", periodId.toString(), 0), "timer.since_when:$tagId", 0)
            }
        }
        migrateTimerParents(db, root, now)
    }

    private fun collectTimerReferences(root: JSONObject, now: MutableSet<Long>, events: MutableSet<Long>, since: MutableSet<Long>) {
        listOf("tasks", "chronologySessions", "runningSessions", "timeFenceRules").forEach { key ->
            root.optJSONArray(key)?.forEachObject { it.optJSONArray("tagIds")?.forEachLong(now::add) }
        }
        root.optJSONArray("tagSessions")?.forEachObject { it.optLong("tagId", -1).takeIf { value -> value >= 0 }?.let(now::add) }
        root.optJSONArray("activeTagStart")?.forEachObject { it.optLong("tagId", -1).takeIf { value -> value >= 0 }?.let(now::add) }
        root.optJSONArray("tagParents")?.forEachObject {
            it.optLong("childId", -1).takeIf { value -> value >= 0 }?.let(now::add)
            it.optLong("parentId", -1).takeIf { value -> value >= 0 }?.let(now::add)
        }
        root.optJSONArray("chains")?.forEachObject { chain ->
            chain.optJSONArray("steps")?.forEachObject { step -> step.optJSONArray("tagIds")?.forEachLong(now::add) }
        }
        listOf("quickEventTemplates", "quickEventEntries", "quickEventMacros").forEach { key ->
            root.optJSONArray(key)?.forEachObject { it.optJSONArray("tagIds")?.forEachLong(events::add) }
        }
        root.optJSONArray("lifePeriods")?.forEachObject { it.optJSONArray("tagIds")?.forEachLong(since::add) }
    }

    private fun collectRelationalTimerReferences(db: SupportSQLiteDatabase, now: MutableSet<Long>, events: MutableSet<Long>) {
        db.query("SELECT DISTINCT tag_id FROM session_tags").use { cursor -> while (cursor.moveToNext()) now += cursor.getLong(0) }
        listOf("quick_event_entry_tags", "quick_event_template_tags", "quick_event_macro_tags").forEach { table ->
            db.query("SELECT DISTINCT tag_id FROM $table").use { cursor -> while (cursor.moveToNext()) events += cursor.getLong(0) }
        }
    }

    private fun insertTimerTag(db: SupportSQLiteDatabase, namespace: String, legacyId: Long, name: String, source: JSONObject?) {
        insertTag(db, "$namespace:$legacyId", namespace, name, normalize(name), 0, 0, source?.optBoolean("isArchived") == true, source?.toString())
    }

    private fun migrateTimerEdges(db: SupportSQLiteDatabase, table: String, targetColumn: String, module: String, kind: String, namespace: String, allowed: Set<Long>) {
        db.query("SELECT $targetColumn,tag_id FROM $table ORDER BY $targetColumn,tag_id").use { cursor ->
            while (cursor.moveToNext()) {
                val tagId = cursor.getLong(1)
                if (tagId in allowed) assign(db, ensureBinding(db, module, kind, cursor.getLong(0).toString(), 0), "$namespace:$tagId", 0)
            }
        }
    }

    private fun migrateTimerParents(db: SupportSQLiteDatabase, root: JSONObject?, allowed: Set<Long>) {
        root?.optJSONArray("tagParents")?.forEachObject { edge ->
            val child = edge.optLong("childId", -1)
            val parent = edge.optLong("parentId", -1)
            if (child in allowed && parent in allowed && child != parent) {
                db.execSQL("INSERT OR IGNORE INTO hub_tag_parents(child_tag_id,parent_tag_id) VALUES (?,?)", arrayOf("timer.now:$child", "timer.now:$parent"))
            }
        }
    }

    private fun insertTag(db: SupportSQLiteDatabase, id: String, namespace: String, name: String, normalized: String, createdAt: Long, updatedAt: Long, archived: Boolean, metadata: String?, kind: String = "FREE") {
        var candidate = normalized.ifBlank { normalize(name) }.ifBlank { "tag" }
        var suffix = 0
        while (true) {
            try {
                db.execSQL(
                    "INSERT INTO hub_tags(id,namespace,kind,name,normalized_name,description,icon,color,created_at,updated_at,archived,pinned,is_global,last_used_at,usage_count,metadata_json) VALUES (?,?,?,?,?,NULL,NULL,NULL,?,?,?,?,0,NULL,0,?)",
                    arrayOf(id, namespace, kind, name, candidate, createdAt, updatedAt, if (archived) 1 else 0, 0, metadata),
                )
                return
            } catch (_: android.database.sqlite.SQLiteConstraintException) {
                val existing = db.query("SELECT id FROM hub_tags WHERE namespace=? AND normalized_name=?", arrayOf(namespace, candidate)).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                if (existing == id) return
                suffix += 1
                candidate = "$normalized#legacy:$suffix"
            }
        }
    }

    private fun ensureBinding(db: SupportSQLiteDatabase, module: String, kind: String, canonical: String, updatedAt: Long): String {
        db.query("SELECT id FROM hub_entity_bindings WHERE module_id=? AND entity_kind=? AND canonical_id=? LIMIT 1", arrayOf(module, kind, canonical)).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        val id = "tag-migration:$module:$kind:$canonical"
        db.execSQL("INSERT OR IGNORE INTO hub_entity_bindings(id,module_id,entity_kind,canonical_id,lifecycle,updated_at) VALUES (?,?,?,?,?,?)", arrayOf(id, module, kind, canonical, "ACTIVE", updatedAt))
        return id
    }

    private fun assign(db: SupportSQLiteDatabase, bindingId: String, tagId: String, assignedAt: Long) {
        db.execSQL("INSERT OR IGNORE INTO hub_tag_assignments(target_binding_id,tag_id,assigned_at,provenance) VALUES (?,?,?,?)", arrayOf(bindingId, tagId, assignedAt, "manual"))
    }

    private fun refreshUsage(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE hub_tags SET usage_count=(SELECT count(*) FROM hub_tag_assignments WHERE tag_id=hub_tags.id),last_used_at=(SELECT max(assigned_at) FROM hub_tag_assignments WHERE tag_id=hub_tags.id)")
    }

    private fun normalize(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
        .lowercase().replace(Regex("\\s+"), " ")

    private fun categoryId(normalized: String): String = "soldi.category:" + UUID.nameUUIDFromBytes(
        "soldi.category:$normalized".toByteArray(StandardCharsets.UTF_8),
    )

    private inline fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
        for (index in 0 until length()) optJSONObject(index)?.let(block)
    }

    private inline fun JSONArray.forEachLong(block: (Long) -> Unit) {
        for (index in 0 until length()) optLong(index, -1).takeIf { it >= 0 }?.let(block)
    }
}
