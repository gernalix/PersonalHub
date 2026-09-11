package com.gernalix.personalhub.core.database

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Builds the global activity register from authoritative semantic audit streams where they already
 * exist (People, Timer, Places), and from a deliberately small set of user-facing canonical tables
 * where no equivalent semantic audit exists. Operational/bookkeeping tables are never captured.
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
        val updateWhenSql: String? = null,
        val deleteAction: String = "deleted",
        val origin: String = "user",
        val system: Boolean = false,
        val reversibleInsert: Boolean = false,
        val reversibleUpdate: Boolean = false,
        val reversibleDelete: Boolean = false,
        val captureDelete: Boolean = true,
        val capturePayload: Boolean = true,
    )

    /*
     * Keep this list intentionally sparse. Timer, People and Places-history changes are sourced
     * from their semantic audit streams below. Derived changes (for example a substance stock
     * recalculation after an intake) are filtered so one user action does not produce duplicates.
     */
    private val rowSpecs = listOf(
        RowSpec(
            table = "places",
            moduleId = "places",
            entityKind = "place",
            entityIdNew = "NEW.`uuid`",
            labelNew = "NULLIF(NEW.`nickname`, '')",
            insertAction = "place_created",
            updateActionSql = "CASE WHEN OLD.`archived`=0 AND NEW.`archived`=1 THEN 'place_archived' WHEN OLD.`archived`=1 AND NEW.`archived`=0 THEN 'place_restored' ELSE 'place_updated' END",
            updateWhenSql = "OLD.`nickname` IS NOT NEW.`nickname` OR OLD.`address` IS NOT NEW.`address` OR OLD.`lat` IS NOT NEW.`lat` OR OLD.`lon` IS NOT NEW.`lon` OR OLD.`radius_m` IS NOT NEW.`radius_m` OR OLD.`notes` IS NOT NEW.`notes` OR OLD.`source_app` IS NOT NEW.`source_app` OR OLD.`archived` IS NOT NEW.`archived`",
            deleteAction = "place_deleted",
            reversibleInsert = true,
            reversibleUpdate = true,
        ),
        RowSpec(
            table = "finance_accounts",
            moduleId = "soldi",
            entityKind = "account",
            entityIdNew = "NEW.`id`",
            labelNew = "NULLIF(NEW.`name`, '')",
            insertAction = "account_created",
            updateActionSql = "'account_updated'",
            deleteAction = "account_deleted",
            reversibleInsert = true,
            reversibleUpdate = true,
        ),
        RowSpec(
            table = "finance_transactions",
            moduleId = "soldi",
            entityKind = "transaction",
            entityIdNew = "NEW.`uuid`",
            labelNew = "NEW.`amount` || ' ' || NEW.`currency`",
            insertAction = "transaction_created",
            updateActionSql = "'transaction_updated'",
            deleteAction = "transaction_deleted",
        ),
        RowSpec(
            table = "substances",
            moduleId = "substances",
            entityKind = "substance",
            entityIdNew = "CAST(NEW.`id` AS TEXT)",
            labelNew = "NULLIF(NEW.`name`, '')",
            insertAction = "substance_created",
            updateActionSql = "CASE WHEN OLD.`archived`=0 AND NEW.`archived`=1 THEN 'substance_archived' WHEN OLD.`archived`=1 AND NEW.`archived`=0 THEN 'substance_restored' ELSE 'substance_updated' END",
            updateWhenSql = "OLD.`name` IS NOT NEW.`name` OR OLD.`canonical_name` IS NOT NEW.`canonical_name` OR OLD.`type` IS NOT NEW.`type` OR OLD.`stock_unit` IS NOT NEW.`stock_unit` OR OLD.`dose_per_intake` IS NOT NEW.`dose_per_intake` OR OLD.`dose_unit` IS NOT NEW.`dose_unit` OR OLD.`daily_frequency` IS NOT NEW.`daily_frequency` OR OLD.`start_epoch_day` IS NOT NEW.`start_epoch_day` OR OLD.`end_epoch_day` IS NOT NEW.`end_epoch_day` OR OLD.`forever` IS NOT NEW.`forever` OR OLD.`archived` IS NOT NEW.`archived` OR OLD.`prn` IS NOT NEW.`prn` OR OLD.`dose_times_csv` IS NOT NEW.`dose_times_csv` OR OLD.`days_mask` IS NOT NEW.`days_mask`",
            deleteAction = "substance_deleted",
        ),
        RowSpec(
            table = "intake_events",
            moduleId = "substances",
            entityKind = "intake",
            entityIdNew = "CAST(NEW.`id` AS TEXT)",
            labelNew = "(SELECT NULLIF(name,'') FROM substances WHERE id=NEW.`substance_id`)",
            insertAction = "intake_recorded",
            updateActionSql = "'intake_updated'",
            deleteAction = "intake_deleted",
        ),
        RowSpec(
            table = "stock_adjustments",
            moduleId = "substances",
            entityKind = "stock_adjustment",
            entityIdNew = "CAST(NEW.`id` AS TEXT)",
            labelNew = "(SELECT NULLIF(name,'') FROM substances WHERE id=NEW.`substance_id`)",
            insertAction = "stock_adjusted",
            updateActionSql = "'stock_adjustment_updated'",
            deleteAction = "stock_adjustment_deleted",
        ),
        RowSpec(
            table = "prescriptions",
            moduleId = "substances",
            entityKind = "prescription",
            entityIdNew = "CAST(NEW.`id` AS TEXT)",
            labelNew = "(SELECT NULLIF(name,'') FROM substances WHERE id=NEW.`substance_id`)",
            insertAction = "prescription_created",
            updateActionSql = "'prescription_updated'",
            deleteAction = "prescription_deleted",
        ),
        RowSpec(
            table = "macros",
            moduleId = "substances",
            entityKind = "macro",
            entityIdNew = "CAST(NEW.`id` AS TEXT)",
            labelNew = "NULLIF(NEW.`name`, '')",
            insertAction = "macro_created",
            updateActionSql = "'macro_updated'",
            deleteAction = "macro_deleted",
        ),
        RowSpec(
            table = "settings",
            moduleId = "substances",
            entityKind = "setting",
            entityIdNew = "NEW.`key`",
            labelNew = "NEW.`key`",
            insertAction = "setting_changed",
            updateActionSql = "'setting_changed'",
            deleteAction = "setting_removed",
            capturePayload = false,
        ),
        RowSpec(
            table = "wordpulse_sessions",
            moduleId = "wordpulse",
            entityKind = "session",
            entityIdNew = "NEW.`id`",
            labelNew = "'Typing session'",
            insertAction = "typing_session_started",
            updateActionSql = "CASE WHEN OLD.`ended_at_utc_ms` IS NULL AND NEW.`ended_at_utc_ms` IS NOT NULL THEN 'typing_session_completed' ELSE 'typing_session_updated' END",
            updateWhenSql = "OLD.`ended_at_utc_ms` IS NOT NEW.`ended_at_utc_ms`",
            deleteAction = "typing_session_deleted",
            origin = "system",
            system = true,
        ),
        RowSpec(
            table = "hub_contexts",
            moduleId = "hub",
            entityKind = "episode",
            entityIdNew = "NEW.`id`",
            labelNew = "NULLIF(NEW.`title`, '')",
            insertAction = "episode_created",
            updateActionSql = "'episode_updated'",
            updateWhenSql = "OLD.`context_type_id` IS NOT NEW.`context_type_id` OR OLD.`title` IS NOT NEW.`title`",
            deleteAction = "episode_deleted",
        ),
        RowSpec(
            table = "hub_preferences",
            moduleId = "settings",
            entityKind = "setting",
            entityIdNew = "NEW.`namespace`",
            labelNew = "NEW.`namespace`",
            insertAction = "setting_changed",
            updateActionSql = "'setting_changed'",
            deleteAction = "setting_removed",
            captureDelete = false,
            capturePayload = false,
        ),
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
        val afterPayload = if (spec.capturePayload) encodedPayload(columns, "NEW") else "NULL"
        val beforePayload = if (spec.capturePayload) encodedPayload(columns, "OLD") else "NULL"
        val payloadKind = if (spec.capturePayload) HubActivityPayloadKind.ROW_V1 else null
        val payloadColumns = if (spec.capturePayload) columnList else null
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
                    payloadKind = payloadKind,
                    payloadColumns = payloadColumns,
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
            ${spec.updateWhenSql?.let { "WHEN $it" }.orEmpty()}
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
                    payloadKind = payloadKind,
                    payloadColumns = payloadColumns,
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
                        payloadKind = payloadKind,
                        payloadColumns = payloadColumns,
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
        val initialField = "lower(NEW.entity_type)='field' AND lower(NEW.action_type)='added' AND EXISTS (SELECT 1 FROM contact_events seed WHERE seed.contact_id=NEW.contact_id AND lower(seed.entity_type)='contact' AND lower(seed.action_type)='created' AND seed.occurred_at=NEW.occurred_at)"
        val nameSql = "(SELECT NULLIF(value,'') FROM contact_fields WHERE contact_id=NEW.contact_id AND field_type='name' ORDER BY position LIMIT 1)"
        db.execSQL("DROP TRIGGER IF EXISTS `hub_activity_bridge_people`")
        db.execSQL("DROP TRIGGER IF EXISTS `hub_activity_people_creation_label`")
        db.execSQL(
            """
            CREATE TRIGGER `hub_activity_people_creation_label` AFTER INSERT ON `contact_events`
            WHEN $initialField AND lower(COALESCE(NEW.field_type,''))='name'
            BEGIN
                UPDATE hub_activity_log
                SET entity_label=NULLIF(NEW.new_value,'')
                WHERE module_id='people'
                  AND source_table='contact_events'
                  AND source_row_key=CAST((
                      SELECT seed.id FROM contact_events seed
                      WHERE seed.contact_id=NEW.contact_id
                        AND lower(seed.entity_type)='contact'
                        AND lower(seed.action_type)='created'
                        AND seed.occurred_at=NEW.occurred_at
                      ORDER BY seed.id DESC LIMIT 1
                  ) AS TEXT)
                  AND entity_label IS NULL;
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER `hub_activity_bridge_people` AFTER INSERT ON `contact_events`
            WHEN NOT ($initialField)
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
                    "CASE WHEN lower(NEW.action_type) IN ('created','deleted') AND lower(NEW.entity_type)='contact' THEN 1 ELSE 0 END",
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
