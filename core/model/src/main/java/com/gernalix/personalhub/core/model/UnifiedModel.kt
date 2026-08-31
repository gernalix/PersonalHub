package com.gernalix.personalhub.core.model

enum class SourceApp(val key: String) {
    LUOGHI("luoghi"),
    MULTITIMETRACKER("multitimetracker"),
    SOSTANZE("sostanze"),
    SUPERCONTACTS("supercontacts"),
    WORDPULSE("wordpulse"),
}

enum class UnifiedFeature(val key: String) {
    PEOPLE("people"),
    TIMER("timer"),
    PLACES("places"),
    SUBSTANCES("substances"),
    WORDPULSE("wordpulse"),
}

enum class UnifiedEntityType(val key: String) {
    PERSON("person"),
    PLACE("place"),
    TAG("tag"),
    EVENT("event"),
    RELATIONSHIP("relationship"),
    TIMER_SESSION("timer_session"),
    QUICK_EVENT("quick_event"),
    SUBSTANCE("substance"),
    INTAKE_EVENT("intake_event"),
    WORD_SESSION("word_session"),
    WORD_ENTRY("word_entry"),
    CORRECTION_EVENT("correction_event"),
    FEATURE_LOCAL_RECORD("feature_local_record"),
}

enum class DedupeDecision(val key: String) {
    PRESERVE_DISTINCT("preserve_distinct"),
    PROVEN_IDENTITY("proven_identity"),
    AMBIGUOUS_CONFLICT("ambiguous_conflict"),
}

data class MigrationMapping(
    val sourceApp: SourceApp,
    val sourceTable: String,
    val sourceId: String,
    val entityType: UnifiedEntityType,
    val newId: String,
    val dedupeDecision: DedupeDecision,
    val notes: String = "",
)

data class SourceTablePlan(
    val sourceApp: SourceApp,
    val sourceTable: String,
    val entityType: UnifiedEntityType,
    val sourceIdColumn: String = "rowid",
    val sourceIdColumns: List<String> = listOf(sourceIdColumn),
)

object MigrationInventory {
    val plans: List<SourceTablePlan> = listOf(
        SourceTablePlan(SourceApp.LUOGHI, "places", UnifiedEntityType.PLACE, sourceIdColumn = "uuid"),
        SourceTablePlan(SourceApp.LUOGHI, "place_aliases", UnifiedEntityType.PLACE, sourceIdColumn = "id"),
        SourceTablePlan(SourceApp.LUOGHI, "place_links", UnifiedEntityType.RELATIONSHIP, sourceIdColumn = "id"),
        SourceTablePlan(SourceApp.LUOGHI, "place_events", UnifiedEntityType.EVENT, sourceIdColumn = "id"),
        SourceTablePlan(SourceApp.LUOGHI, "global_stats_state", UnifiedEntityType.FEATURE_LOCAL_RECORD, sourceIdColumn = "id"),
        SourceTablePlan(SourceApp.LUOGHI, "route_distance_cache", UnifiedEntityType.FEATURE_LOCAL_RECORD, sourceIdColumn = "id"),
        SourceTablePlan(SourceApp.LUOGHI, "history_audit_log", UnifiedEntityType.EVENT, sourceIdColumn = "id"),
        SourceTablePlan(SourceApp.LUOGHI, "history_actions", UnifiedEntityType.EVENT, sourceIdColumn = "id"),
        SourceTablePlan(SourceApp.MULTITIMETRACKER, "snapshot", UnifiedEntityType.FEATURE_LOCAL_RECORD, sourceIdColumn = "id"),
        SourceTablePlan(SourceApp.MULTITIMETRACKER, "snapshot_history", UnifiedEntityType.FEATURE_LOCAL_RECORD, sourceIdColumn = "id"),
        SourceTablePlan(SourceApp.MULTITIMETRACKER, "snapshot_payloads", UnifiedEntityType.FEATURE_LOCAL_RECORD, sourceIdColumn = "id"),
        SourceTablePlan(SourceApp.MULTITIMETRACKER, "audit_events", UnifiedEntityType.EVENT, sourceIdColumn = "id"),
        SourceTablePlan(SourceApp.MULTITIMETRACKER, "sessions", UnifiedEntityType.TIMER_SESSION, sourceIdColumn = "id"),
        SourceTablePlan(
            SourceApp.MULTITIMETRACKER,
            "session_tags",
            UnifiedEntityType.TAG,
            sourceIdColumns = listOf("session_id", "tag_id"),
        ),
        SourceTablePlan(SourceApp.MULTITIMETRACKER, "quick_event_templates", UnifiedEntityType.QUICK_EVENT, sourceIdColumn = "id"),
        SourceTablePlan(
            SourceApp.MULTITIMETRACKER,
            "quick_event_template_tags",
            UnifiedEntityType.TAG,
            sourceIdColumns = listOf("template_id", "tag_id"),
        ),
        SourceTablePlan(SourceApp.MULTITIMETRACKER, "quick_event_entries", UnifiedEntityType.QUICK_EVENT, sourceIdColumn = "id"),
        SourceTablePlan(
            SourceApp.MULTITIMETRACKER,
            "quick_event_entry_tags",
            UnifiedEntityType.TAG,
            sourceIdColumns = listOf("entry_id", "tag_id"),
        ),
        SourceTablePlan(SourceApp.MULTITIMETRACKER, "quick_event_template_fields", UnifiedEntityType.QUICK_EVENT, sourceIdColumn = "id"),
        SourceTablePlan(SourceApp.MULTITIMETRACKER, "quick_event_entry_field_values", UnifiedEntityType.QUICK_EVENT, sourceIdColumn = "id"),
        SourceTablePlan(SourceApp.MULTITIMETRACKER, "quick_event_macros", UnifiedEntityType.QUICK_EVENT, sourceIdColumn = "id"),
        SourceTablePlan(
            SourceApp.MULTITIMETRACKER,
            "quick_event_macro_tags",
            UnifiedEntityType.TAG,
            sourceIdColumns = listOf("macro_id", "tag_id"),
        ),
        SourceTablePlan(
            SourceApp.MULTITIMETRACKER,
            "quick_event_macro_actions",
            UnifiedEntityType.QUICK_EVENT,
            sourceIdColumns = listOf("macro_id", "template_id"),
        ),
        SourceTablePlan(SourceApp.MULTITIMETRACKER, "mtt_remote_sync.sync_queue", UnifiedEntityType.FEATURE_LOCAL_RECORD, sourceIdColumn = "sync_id"),
        SourceTablePlan(SourceApp.MULTITIMETRACKER, "mtt_remote_sync.sync_shadow", UnifiedEntityType.FEATURE_LOCAL_RECORD, sourceIdColumn = "sync_id"),
        SourceTablePlan(SourceApp.MULTITIMETRACKER, "mtt_remote_sync.sync_meta", UnifiedEntityType.FEATURE_LOCAL_RECORD, sourceIdColumn = "key"),
        SourceTablePlan(SourceApp.SOSTANZE, "substances", UnifiedEntityType.SUBSTANCE, sourceIdColumn = "id"),
        SourceTablePlan(SourceApp.SOSTANZE, "intake_events", UnifiedEntityType.INTAKE_EVENT, sourceIdColumn = "id"),
        SourceTablePlan(SourceApp.SOSTANZE, "stock_adjustments", UnifiedEntityType.FEATURE_LOCAL_RECORD, sourceIdColumn = "id"),
        SourceTablePlan(SourceApp.SOSTANZE, "prescriptions", UnifiedEntityType.FEATURE_LOCAL_RECORD, sourceIdColumn = "id"),
        SourceTablePlan(SourceApp.SOSTANZE, "interaction_rules", UnifiedEntityType.RELATIONSHIP, sourceIdColumn = "id"),
        SourceTablePlan(SourceApp.SOSTANZE, "interaction_targets", UnifiedEntityType.RELATIONSHIP, sourceIdColumn = "id"),
        SourceTablePlan(SourceApp.SOSTANZE, "notification_state", UnifiedEntityType.FEATURE_LOCAL_RECORD, sourceIdColumn = "id"),
        SourceTablePlan(SourceApp.SOSTANZE, "settings", UnifiedEntityType.FEATURE_LOCAL_RECORD, sourceIdColumn = "id"),
        SourceTablePlan(SourceApp.SOSTANZE, "macros", UnifiedEntityType.FEATURE_LOCAL_RECORD, sourceIdColumn = "id"),
        SourceTablePlan(SourceApp.SOSTANZE, "macro_items", UnifiedEntityType.RELATIONSHIP, sourceIdColumn = "id"),
        SourceTablePlan(SourceApp.SUPERCONTACTS, "backup_metadata", UnifiedEntityType.FEATURE_LOCAL_RECORD, sourceIdColumn = "id"),
        SourceTablePlan(SourceApp.SUPERCONTACTS, "contacts", UnifiedEntityType.PERSON, sourceIdColumn = "id"),
        SourceTablePlan(SourceApp.SUPERCONTACTS, "contact_fields", UnifiedEntityType.PERSON, sourceIdColumn = "id"),
        SourceTablePlan(SourceApp.SUPERCONTACTS, "contact_events", UnifiedEntityType.EVENT, sourceIdColumn = "id"),
        SourceTablePlan(SourceApp.SUPERCONTACTS, "contact_initiatives", UnifiedEntityType.EVENT, sourceIdColumn = "id"),
        SourceTablePlan(SourceApp.SUPERCONTACTS, "contact_messaging_links", UnifiedEntityType.RELATIONSHIP, sourceIdColumn = "id"),
        SourceTablePlan(SourceApp.SUPERCONTACTS, "saved_searches", UnifiedEntityType.FEATURE_LOCAL_RECORD, sourceIdColumn = "id"),
        SourceTablePlan(
            SourceApp.SUPERCONTACTS,
            "saved_search_tags",
            UnifiedEntityType.TAG,
            sourceIdColumns = listOf("saved_search_id", "tag_id"),
        ),
        SourceTablePlan(SourceApp.SUPERCONTACTS, "tags", UnifiedEntityType.TAG, sourceIdColumn = "id"),
        SourceTablePlan(
            SourceApp.SUPERCONTACTS,
            "contact_tags",
            UnifiedEntityType.TAG,
            sourceIdColumns = listOf("contact_id", "tag_id"),
        ),
        SourceTablePlan(SourceApp.WORDPULSE, "app_state", UnifiedEntityType.FEATURE_LOCAL_RECORD, sourceIdColumn = "id"),
        SourceTablePlan(SourceApp.WORDPULSE, "correction_events", UnifiedEntityType.CORRECTION_EVENT, sourceIdColumn = "id"),
        SourceTablePlan(SourceApp.WORDPULSE, "word_entries", UnifiedEntityType.WORD_ENTRY, sourceIdColumn = "id"),
        SourceTablePlan(SourceApp.WORDPULSE, "sessions", UnifiedEntityType.WORD_SESSION, sourceIdColumn = "id"),
    )

    fun planFor(sourceApp: SourceApp, table: String): SourceTablePlan? =
        plans.firstOrNull { it.sourceApp == sourceApp && it.sourceTable == table }

    fun mappingForRow(sourceApp: SourceApp, table: String, oldId: String): MigrationMapping {
        val plan = planFor(sourceApp, table)
            ?: SourceTablePlan(sourceApp, table, UnifiedEntityType.FEATURE_LOCAL_RECORD)
        return MigrationMappingPlanner.preserveDistinct(
            sourceApp = sourceApp,
            sourceTable = table,
            sourceId = oldId,
            entityType = plan.entityType,
            notes = "Initial PersonalHub import keeps source identity until a stronger identity proof exists.",
        )
    }
}

object MigrationMappingPlanner {
    fun namespacedId(sourceApp: SourceApp, sourceTable: String, sourceId: String): String {
        require(sourceTable.isNotBlank()) { "sourceTable must not be blank" }
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
        return listOf(sourceApp.key, sourceTable.trim(), sourceId.trim()).joinToString(":")
    }

    fun preserveDistinct(
        sourceApp: SourceApp,
        sourceTable: String,
        sourceId: String,
        entityType: UnifiedEntityType,
        notes: String = "",
    ): MigrationMapping =
        MigrationMapping(
            sourceApp = sourceApp,
            sourceTable = sourceTable.trim(),
            sourceId = sourceId.trim(),
            entityType = entityType,
            newId = namespacedId(sourceApp, sourceTable, sourceId),
            dedupeDecision = DedupeDecision.PRESERVE_DISTINCT,
            notes = notes,
        )
}

data class SourceRecordKey(
    val sourceApp: SourceApp,
    val sourceTable: String,
    val sourceId: String,
)

data class SourceTableRows(
    val sourceApp: SourceApp,
    val sourceTable: String,
    val sourceIds: List<String>,
)

data class TableMigrationAudit(
    val sourceApp: SourceApp,
    val sourceTable: String,
    val sourceCount: Int,
    val mappingCount: Int,
    val missingSourceIds: Set<String>,
    val unexpectedSourceIds: Set<String>,
    val duplicateSourceIds: Set<String>,
) {
    val passed: Boolean =
        sourceCount == mappingCount &&
            missingSourceIds.isEmpty() &&
            unexpectedSourceIds.isEmpty() &&
            duplicateSourceIds.isEmpty()
}

data class MigrationAuditReport(
    val tableAudits: List<TableMigrationAudit>,
    val duplicateTargetIds: Set<String>,
) {
    val passed: Boolean = tableAudits.all { it.passed } && duplicateTargetIds.isEmpty()
    val sourceCount: Int = tableAudits.sumOf { it.sourceCount }
    val mappingCount: Int = tableAudits.sumOf { it.mappingCount }
}

object MigrationVerifier {
    fun mappingsFor(sourceRows: List<SourceTableRows>): List<MigrationMapping> =
        sourceRows.flatMap { table ->
            table.sourceIds.map { sourceId ->
                MigrationInventory.mappingForRow(
                    sourceApp = table.sourceApp,
                    table = table.sourceTable,
                    oldId = sourceId,
                )
            }
        }

    fun verify(
        sourceRows: List<SourceTableRows>,
        mappings: List<MigrationMapping>,
    ): MigrationAuditReport {
        val sourceByTable = sourceRows
            .groupBy { SourceRecordKey(it.sourceApp, it.sourceTable, "") }
            .mapValues { (_, tables) -> tables.flatMap { it.sourceIds } }
        val mappingsByTable = mappings.groupBy { SourceRecordKey(it.sourceApp, it.sourceTable, "") }
        val tableKeys = (sourceByTable.keys + mappingsByTable.keys).sortedWith(sourceTableKeyComparator)
        val tableAudits = tableKeys.map { key ->
            val expectedIds = sourceByTable[key].orEmpty()
            val actualIds = mappingsByTable[key]?.map { it.sourceId }.orEmpty()
            val expectedSet = expectedIds.toSet()
            val actualSet = actualIds.toSet()
            TableMigrationAudit(
                sourceApp = key.sourceApp,
                sourceTable = key.sourceTable,
                sourceCount = expectedIds.size,
                mappingCount = actualIds.size,
                missingSourceIds = expectedSet - actualSet,
                unexpectedSourceIds = actualSet - expectedSet,
                duplicateSourceIds = actualIds
                    .groupingBy { it }
                    .eachCount()
                    .filterValues { it > 1 }
                    .keys,
            )
        }

        val duplicateTargetIds = mappings
            .groupBy { it.newId }
            .filterValues { targetMappings ->
                targetMappings.size > 1 &&
                    targetMappings.any { it.dedupeDecision != DedupeDecision.PROVEN_IDENTITY }
            }
            .keys

        return MigrationAuditReport(
            tableAudits = tableAudits,
            duplicateTargetIds = duplicateTargetIds,
        )
    }

    private val sourceTableKeyComparator = compareBy<SourceRecordKey>({ it.sourceApp.key }, { it.sourceTable })
}
