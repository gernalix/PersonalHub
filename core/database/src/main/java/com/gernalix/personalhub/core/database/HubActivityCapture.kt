package com.gernalix.personalhub.core.database

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Builds the global activity register from authoritative semantic audit streams where they already
 * exist (People, Timer, Places), and from selected user-facing canonical tables elsewhere.
 * Operational/bookkeeping tables are intentionally not captured.
 */
object HubActivityCapture {
    const val UNDO_CONTEXT_TABLE = "hub_activity_undo_context"

    private data class RowSpec(
        val table: String,
        val moduleId: String,
        val entityKind: String,
        val entityIdNew: String,
        val entityIdOld: String = entityIdNew.replace("NEW.", "OLD."),
        val labelNew: String,
        val labelOld: String = labelNew.replace("NEW.", "OLD."),
        val insertAction: String = "created",
        val updateActionSql: String = "'updated'",
        val deleteAction: String = "deleted",
        val origin: String = "user",
        val system: Boolean = false,
        val reversibleInsert: Boolean = false,
        val reversibleUpdate: Boolean = false,
        val reversibleDelete: Boolean = false,
        val captureDelete: Boolean = true,
    )

    private val rowSpecs = listOf(
        RowSpec(
            table = "places",
            moduleId = "places",
            entityKind = "place",
            entityIdNew = "NEW.`uuid`",
            labelNew = "NULLIF(NEW.`nickname`, '')",
            insertAction = "place_created",
            updateActionSql = "CASE WHEN OLD.`archived`=0 AND NEW.`archived`=1 THEN 'place_archived' WHEN OLD.`archived`=1 AND NEW.`archived`=0 THEN 'place_restored' ELSE 'place_updated' END",
            deleteAction = "place_deleted",
            reversibleInsert = true,
            reversibleUpdate = true,
        ),
        RowSpec("finance_accounts", "soldi", "account", "NEW.`id`", labelNew = "NULLIF(NEW.`name`, '')", insertAction = "account_created", updateActionSql = "'account_updated'", deleteAction = "account_deleted", reversibleInsert = true, reversibleUpdate = true),
        RowSpec("finance_transactions", "soldi", "transaction", "NEW.`uuid`", labelNew = "NEW.`amount` || ' ' || NEW.`currency`", insertAction = "transaction_created", updateActionSql = "'transaction_updated'", deleteAction = "transaction_deleted"),
        RowSpec("finance_products", "soldi", "product", "NEW.`uuid`", labelNew = "NULLIF(NEW.`name`, '')", insertAction = "product_created", updateActionSql = "'product_updated'", deleteAction = "product_deleted"),
        RowSpec("finance_titles", "soldi", "title", "CAST(NEW.`id` AS TEXT)", labelNew = "NULLIF(NEW.`name`, '')", insertAction = "title_created", updateActionSql = "'title_updated'", deleteAction = "title_deleted"),
        RowSpec("finance_chains", "soldi", "chain", "CAST(NEW.`id` AS TEXT)", labelNew = "NULLIF(NEW.`name`, '')", insertAction = "chain_created", updateActionSql = "'chain_updated'", deleteAction = "chain_deleted"),
        RowSpec("finance_tags", "soldi", "tag", "CAST(NEW.`id` AS TEXT)", labelNew = "NULLIF(NEW.`name`, '')", insertAction = "tag_created", updateActionSql = "'tag_updated'", deleteAction = "tag_deleted"),
        RowSpec(
            "substances",
            "substances",
            "substance",
            "CAST(NEW.`id` AS TEXT)",
            labelNew = "NULLIF(NEW.`name`, '')",
            insertAction = "substance_created",
            updateActionSql = "CASE WHEN OLD.`archived`=0 AND NEW.`archived`=1 THEN 'substance_archived' WHEN OLD.`archived`=1 AND NEW.`archived`=0 THEN 'substance_restored' ELSE 'substance_updated' END",
            deleteAction = "substance_deleted",
        ),
        RowSpec("intake_events", "substances", "intake", "CAST(NEW.`id` AS TEXT)", labelNew = "(SELECT NULLIF(name,'') FROM substances WHERE id=NEW.`substance_id`)", insertAction = "intake_recorded", updateActionSql = "'intake_updated'", deleteAction = "intake_deleted"),
        RowSpec("stock_adjustments", "substances", "stock_adjustment", "CAST(NEW.`id` AS TEXT)", labelNew = "(SELECT NULLIF(name,'') FROM substances WHERE id=NEW.`substance_id`)", insertAction = "stock_adjusted", updateActionSql = "'stock_adjustment_updated'", deleteAction = "stock_adjustment_deleted"),
        RowSpec("prescriptions", "substances", "prescription", "CAST(NEW.`id` AS TEXT)", labelNew = "(SELECT NULLIF(name,'') FROM substances WHERE id=NEW.`substance_id`)", insertAction = "prescription_created", updateActionSql = "'prescription_updated'", deleteAction = "prescription_deleted"),
        RowSpec("macros", "substances", "macro", "CAST(NEW.`id` AS TEXT)", labelNew = "NULLIF(NEW.`name`, '')", insertAction = "macro_created", updateActionSql = "'macro_updated'", deleteAction = "macro_deleted"),
        RowSpec("settings", "substances", "setting", "NEW.`key`", labelNew = "NEW.`key`", insertAction = "setting_changed", updateActionSql = "'setting_changed'", deleteAction = "setting_removed"),
        RowSpec(
            "sessions",
            "timer",
            "session",
            "CAST(NEW.`id` AS TEXT)",
            labelNew = "NULLIF(NEW.`title`, '')",
            insertAction = "session_started",
            updateActionSql = "CASE WHEN OLD.`end_ms` IS NULL AND NEW.`end_ms` IS NOT NULL THEN 'session_stopped' WHEN OLD.`end_ms` IS NOT NULL AND NEW.`end_ms` IS NULL THEN 'session_reopened' ELSE 'session_updated' END",
            deleteAction = "session_deleted",
            system = false,
        ),
        RowSpec("quick_event_entries", "timer", "quick_event", "CAST(NEW.`id` AS TEXT)", labelNew = "NULLIF(NEW.`title`, '')", insertAction = "quick_event_recorded", updateActionSql = "'quick_event_updated'", deleteAction = "quick_event_deleted"),
        RowSpec("quick_event_templates", "timer", "quick_event_template", "CAST(NEW.`id` AS TEXT)", labelNew = "NULLIF(NEW.`title`, '')", insertAction = "quick_event_template_created", updateActionSql = "'quick_event_template_updated'", deleteAction = "quick_event_template_deleted"),
        RowSpec("quick_event_macros", "timer", "quick_event_macro", "CAST(NEW.`id` AS TEXT)", labelNew = "NULLIF(NEW.`title`, '')", insertAction = "quick_event_macro_created", updateActionSql = "'quick_event_macro_updated'", deleteAction = "quick_event_macro_deleted"),
        RowSpec("wordpulse_sessions", "wordpulse", "session", "NEW.`id`", labelNew = "'Typing session'", insertAction = "typing_session_started", updateActionSql = "CASE WHEN OLD.`ended_at_utc_ms` IS NULL AND NEW.`ended_at_utc_ms` IS NOT NULL THEN 'typing_session_completed' ELSE 'typing_session_updated' END", deleteAction = "typing_session_deleted", origin = "system", system = true),
        RowSpec("hub_contexts", "hub", "episode", "NEW.`id`", labelNew = "NULLIF(NEW.`title`, '')", insertAction = "episode_created", updateActionSql = "'episode_updated'", deleteAction = "episode_deleted"),
        RowSpec("hub_context_types", "hub", "context_type", "NEW.`id`", labelNew = "NULLIF(NEW.`name`, '')", insertAction = "context_type_created", updateActionSql = "'context_type_updated'", deleteAction = "context_type_deleted"),
        RowSpec("hub_preferences", "settings", "setting", "NEW.`namespace`", labelNew = "NEW.`namespace`", insertAction = "setting_changed", updateActionSql = "'setting_changed'", deleteAction = "setting_removed", captureDelete = false),
    )

    fun createInternalTables(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `$UNDO_CONTEXT_TABLE` (`id` INTEGER NOT NULL PRIMARY KEY, `original_activity_id` TEXT NOT NULL)",
        )
    }

    fun install(db: SupportSQLiteDatabase, appVersion: Long) {
        createInternalTables(db)
        rowSpecs.forEach { spec -> if (tableExists(db, spec.table)) installRowSpec(db, spec, appVersion) }
        if (tableExists(db, "contact_events")) installPeopleBridge(db, appVersion)
        if (tableExists(db, "audit_events")) installTimerBridge(db, appVersion)
        if (tableExists(db, "history_audit_log")) installPlacesBridge(db, appVersion)
    }

    private fun installRowSpec(db: SupportSQLiteDatabase, spec: RowSpec, appVersion: Long) {
        val columns = columns(db, spec.table)
        if (columns.isEmpty()) return
        val columnList = columns.joinToString(",")
        val afterPayload = encodedPayload(columns, "NEW")
        val beforePayload = encodedPayload(columns, "OLD")
        val base = "hub_activity_${spec.table}"
        listOf("INSERT", "UPDATE", "DELETE").forEach { db.execSQL("DROP TRIGGER IF EXISTS `${base}_$it`") }

        db.execSQL(
            """
            CREATE TRIGGER `${base}_INSERT` AFTER INSERT ON `${spec.table}`
            BEGIN
                ${insertSql(
                    appVersion = appVersion,
                    moduleId = spec.moduleId,
                    actionSql = sqlString(spec.insertAction),
                    entityKind = spec.entityKind,
                    entityIdSql = spec.entityIdNew,
                    entityLabelSql = spec.labelNew,
                    detailKeySql = "NULL",
                    detailValueSql = "NULL",
                    origin = spec.origin,
                    systemSql = if (spec.system) "1" else "0",
                    sourceTable = spec.table,
                    sourceRowKeySql = spec.entityIdNew,
                    payloadKind = HubActivityPayloadKind.ROW_V1,
                    payloadColumns = columnList,
                    beforePayloadSql = "NULL",
                    afterPayloadSql = afterPayload,
                    reversibleSql = if (spec.reversibleInsert) "1" else "0",
                )}
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER `${base}_UPDATE` AFTER UPDATE ON `${spec.table}`
            BEGIN
                ${insertSql(
                    appVersion = appVersion,
                    moduleId = spec.moduleId,
                    actionSql = spec.updateActionSql,
                    entityKind = spec.entityKind,
                    entityIdSql = spec.entityIdNew,
                    entityLabelSql = spec.labelNew,
                    detailKeySql = "NULL",
                    detailValueSql = "NULL",
                    origin = spec.origin,
                    systemSql = if (spec.system) "1" else "0",
                    sourceTable = spec.table,
                    sourceRowKeySql = spec.entityIdNew,
                    payloadKind = HubActivityPayloadKind.ROW_V1,
                    payloadColumns = columnList,
                    beforePayloadSql = beforePayload,
                    afterPayloadSql = afterPayload,
                    reversibleSql = if (spec.reversibleUpdate) "1" else "0",
                )}
            END
            """.trimIndent(),
        )
        if (spec.captureDelete) {
            db.execSQL(
                """
                CREATE TRIGGER `${base}_DELETE` BEFORE DELETE ON `${spec.table}`
                BEGIN
                    ${insertSql(
                        appVersion = appVersion,
                        moduleId = spec.moduleId,
                        actionSql = sqlString(spec.deleteAction),
                        entityKind = spec.entityKind,
                        entityIdSql = spec.entityIdOld,
                        entityLabelSql = spec.labelOld,
                        detailKeySql = "NULL",
                        detailValueSql = "NULL",
                        origin = spec.origin,
                        systemSql = if (spec.system) "1" else "0",
                        sourceTable = spec.table,
                        sourceRowKeySql = spec.entityIdOld,
                        payloadKind = HubActivityPayloadKind.ROW_V1,
                        payloadColumns = columnList,
                        beforePayloadSql = beforePayload,
                        afterPayloadSql = "NULL",
                        reversibleSql = if (spec.reversibleDelete) "1" else "0",
                    )}
                END
                """.trimIndent(),
            )
        }
    }

    private fun installPeopleBridge(db: SupportSQLiteDatabase, appVersion: Long) {
        val nameSql = "(SELECT NULLIF(value,'') FROM contact_fields WHERE contact_id=NEW.contact_id AND field_type='name' ORDER BY position LIMIT 1)"
        db.execSQL("DROP TRIGGER IF EXISTS `hub_activity_bridge_people`")
        db.execSQL(
            """
            CREATE TRIGGER `hub_activity_bridge_people` AFTER INSERT ON `contact_events`
            BEGIN
                ${insertSql(
                    appVersion,
                    "people",
                    "lower(NEW.action_type)",
                    "person",
                    "COALESCE((SELECT public_id FROM contacts WHERE id=NEW.contact_id), CAST(NEW.contact_id AS TEXT))",
                    nameSql,
                    "NEW.field_type",
                    "COALESCE(NEW.new_value, NEW.old_value, NEW.event_type)",
                    "user",
                    "0",
                    "contact_events",
                    "CAST(NEW.id AS TEXT)",
                    HubActivityPayloadKind.PEOPLE_EVENT_V1,
                    null,
                    "NEW.old_value",
                    "NEW.new_value",
                    "CASE WHEN lower(NEW.action_type) IN ('created','deleted') AND lower(NEW.entity_type)='contact' THEN 1 WHEN lower(NEW.action_type)='updated' AND NEW.field_type IS NOT NULL THEN 1 ELSE 0 END",
                )}
            END
            """.trimIndent(),
        )
    }

    private fun installTimerBridge(db: SupportSQLiteDatabase, appVersion: Long) {
        db.execSQL("DROP TRIGGER IF EXISTS `hub_activity_bridge_timer`")
        db.execSQL(
            """
            CREATE TRIGGER `hub_activity_bridge_timer` AFTER INSERT ON `audit_events`
            BEGIN
                ${insertSql(
                    appVersion,
                    "timer",
                    "lower(NEW.action)",
                    null,
                    "CASE WHEN NEW.entity_id IS NULL THEN NULL ELSE CAST(NEW.entity_id AS TEXT) END",
                    "NULLIF(NEW.summary,'')",
                    "NEW.entity_type",
                    "NEW.payload_json",
                    "CASE WHEN NEW.is_system=1 THEN 'system' ELSE 'user' END",
                    "NEW.is_system",
                    "audit_events",
                    "CAST(NEW.id AS TEXT)",
                    HubActivityPayloadKind.TIMER_AUDIT_V1,
                    null,
                    "NULL",
                    "NEW.payload_json",
                    "0",
                )}
            END
            """.trimIndent(),
        )
    }

    private fun installPlacesBridge(db: SupportSQLiteDatabase, appVersion: Long) {
        db.execSQL("DROP TRIGGER IF EXISTS `hub_activity_bridge_places`")
        db.execSQL(
            """
            CREATE TRIGGER `hub_activity_bridge_places` AFTER INSERT ON `history_audit_log`
            BEGIN
                ${insertSql(
                    appVersion,
                    "places",
                    "lower(NEW.action)",
                    "place_event",
                    "NEW.entity_id",
                    "NULL",
                    "NEW.entity_type",
                    "NEW.reason",
                    "user",
                    "0",
                    "history_audit_log",
                    "CAST(NEW.id AS TEXT)",
                    HubActivityPayloadKind.PLACES_AUDIT_V1,
                    null,
                    "NEW.before_json",
                    "NEW.after_json",
                    "0",
                )}
            END
            """.trimIndent(),
        )
    }

    private fun insertSql(
        appVersion: Long,
        moduleId: String,
        actionSql: String,
        entityKind: String?,
        entityIdSql: String,
        entityLabelSql: String,
        detailKeySql: String,
        detailValueSql: String,
        origin: String,
        systemSql: String,
        sourceTable: String,
        sourceRowKeySql: String,
        payloadKind: String?,
        payloadColumns: String?,
        beforePayloadSql: String,
        afterPayloadSql: String,
        reversibleSql: String,
    ): String =
        """
        INSERT INTO hub_activity_log(
            id, occurred_at, module_id, action, entity_kind, entity_id, entity_label,
            detail_key, detail_value, origin, is_system, source_table, source_row_key,
            payload_kind, payload_columns, before_payload, after_payload, payload_version,
            app_version, group_id, reversible, status, reverted_at, reverts_activity_id
        ) VALUES(
            ${randomIdSql()}, ${nowMsSql()}, ${sqlString(moduleId)}, $actionSql,
            ${entityKind?.let(::sqlString) ?: "NULL"}, $entityIdSql, $entityLabelSql,
            $detailKeySql, $detailValueSql, ${sqlString(origin)}, $systemSql,
            ${sqlString(sourceTable)}, $sourceRowKeySql,
            ${payloadKind?.let(::sqlString) ?: "NULL"}, ${payloadColumns?.let(::sqlString) ?: "NULL"},
            $beforePayloadSql, $afterPayloadSql, 1, $appVersion, NULL,
            CASE WHEN (SELECT original_activity_id FROM `$UNDO_CONTEXT_TABLE` WHERE id=1) IS NULL THEN $reversibleSql ELSE 0 END,
            'ACTIVE', NULL,
            (SELECT original_activity_id FROM `$UNDO_CONTEXT_TABLE` WHERE id=1)
        );
        """.trimIndent()

    private fun encodedPayload(columns: List<String>, prefix: String): String =
        columns.joinToString(" || ':' || ") { "hex(quote($prefix.${quotedIdentifier(it)}))" }

    private fun columns(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info(${quotedIdentifier(table)})").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean =
        db.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1", arrayOf(table)).use { it.moveToFirst() }

    private fun randomIdSql() = "lower(hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-' || hex(randomblob(2)) || '-' || hex(randomblob(2)) || '-' || hex(randomblob(6)))"
    private fun nowMsSql() = "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"
    private fun quotedIdentifier(value: String) = "`" + value.replace("`", "``") + "`"
    private fun sqlString(value: String) = "'" + value.replace("'", "''") + "'"
}
