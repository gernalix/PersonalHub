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
)

object MigrationInventory {
    val plans: List<SourceTablePlan> = listOf(
        SourceTablePlan(SourceApp.LUOGHI, "places", UnifiedEntityType.PLACE),
        SourceTablePlan(SourceApp.LUOGHI, "place_aliases", UnifiedEntityType.PLACE),
        SourceTablePlan(SourceApp.LUOGHI, "place_links", UnifiedEntityType.RELATIONSHIP),
        SourceTablePlan(SourceApp.LUOGHI, "place_events", UnifiedEntityType.EVENT),
        SourceTablePlan(SourceApp.LUOGHI, "global_stats_state", UnifiedEntityType.FEATURE_LOCAL_RECORD),
        SourceTablePlan(SourceApp.LUOGHI, "route_distance_cache", UnifiedEntityType.FEATURE_LOCAL_RECORD),
        SourceTablePlan(SourceApp.LUOGHI, "history_audit_log", UnifiedEntityType.EVENT),
        SourceTablePlan(SourceApp.LUOGHI, "history_actions", UnifiedEntityType.EVENT),
        SourceTablePlan(SourceApp.MULTITIMETRACKER, "snapshot", UnifiedEntityType.FEATURE_LOCAL_RECORD),
        SourceTablePlan(SourceApp.MULTITIMETRACKER, "snapshot_history", UnifiedEntityType.FEATURE_LOCAL_RECORD),
        SourceTablePlan(SourceApp.MULTITIMETRACKER, "snapshot_payloads", UnifiedEntityType.FEATURE_LOCAL_RECORD),
        SourceTablePlan(SourceApp.MULTITIMETRACKER, "audit_events", UnifiedEntityType.EVENT),
        SourceTablePlan(SourceApp.MULTITIMETRACKER, "sessions", UnifiedEntityType.TIMER_SESSION),
        SourceTablePlan(SourceApp.MULTITIMETRACKER, "session_tags", UnifiedEntityType.TAG),
        SourceTablePlan(SourceApp.MULTITIMETRACKER, "quick_event_templates", UnifiedEntityType.QUICK_EVENT),
        SourceTablePlan(SourceApp.MULTITIMETRACKER, "quick_event_template_tags", UnifiedEntityType.TAG),
        SourceTablePlan(SourceApp.MULTITIMETRACKER, "quick_event_entries", UnifiedEntityType.QUICK_EVENT),
        SourceTablePlan(SourceApp.MULTITIMETRACKER, "quick_event_entry_tags", UnifiedEntityType.TAG),
        SourceTablePlan(SourceApp.MULTITIMETRACKER, "quick_event_template_fields", UnifiedEntityType.QUICK_EVENT),
        SourceTablePlan(SourceApp.MULTITIMETRACKER, "quick_event_entry_field_values", UnifiedEntityType.QUICK_EVENT),
        SourceTablePlan(SourceApp.MULTITIMETRACKER, "quick_event_macros", UnifiedEntityType.QUICK_EVENT),
        SourceTablePlan(SourceApp.MULTITIMETRACKER, "quick_event_macro_tags", UnifiedEntityType.TAG),
        SourceTablePlan(SourceApp.MULTITIMETRACKER, "quick_event_macro_actions", UnifiedEntityType.QUICK_EVENT),
        SourceTablePlan(SourceApp.MULTITIMETRACKER, "mtt_remote_sync.sync_queue", UnifiedEntityType.FEATURE_LOCAL_RECORD),
        SourceTablePlan(SourceApp.MULTITIMETRACKER, "mtt_remote_sync.sync_shadow", UnifiedEntityType.FEATURE_LOCAL_RECORD),
        SourceTablePlan(SourceApp.MULTITIMETRACKER, "mtt_remote_sync.sync_meta", UnifiedEntityType.FEATURE_LOCAL_RECORD),
        SourceTablePlan(SourceApp.SOSTANZE, "substances", UnifiedEntityType.SUBSTANCE),
        SourceTablePlan(SourceApp.SOSTANZE, "intake_events", UnifiedEntityType.INTAKE_EVENT),
        SourceTablePlan(SourceApp.SOSTANZE, "stock_adjustments", UnifiedEntityType.FEATURE_LOCAL_RECORD),
        SourceTablePlan(SourceApp.SOSTANZE, "prescriptions", UnifiedEntityType.FEATURE_LOCAL_RECORD),
        SourceTablePlan(SourceApp.SOSTANZE, "interaction_rules", UnifiedEntityType.RELATIONSHIP),
        SourceTablePlan(SourceApp.SOSTANZE, "interaction_targets", UnifiedEntityType.RELATIONSHIP),
        SourceTablePlan(SourceApp.SOSTANZE, "notification_state", UnifiedEntityType.FEATURE_LOCAL_RECORD),
        SourceTablePlan(SourceApp.SOSTANZE, "settings", UnifiedEntityType.FEATURE_LOCAL_RECORD),
        SourceTablePlan(SourceApp.SOSTANZE, "macros", UnifiedEntityType.FEATURE_LOCAL_RECORD),
        SourceTablePlan(SourceApp.SOSTANZE, "macro_items", UnifiedEntityType.RELATIONSHIP),
        SourceTablePlan(SourceApp.SUPERCONTACTS, "backup_metadata", UnifiedEntityType.FEATURE_LOCAL_RECORD),
        SourceTablePlan(SourceApp.SUPERCONTACTS, "contacts", UnifiedEntityType.PERSON),
        SourceTablePlan(SourceApp.SUPERCONTACTS, "contact_fields", UnifiedEntityType.PERSON),
        SourceTablePlan(SourceApp.SUPERCONTACTS, "contact_events", UnifiedEntityType.EVENT),
        SourceTablePlan(SourceApp.SUPERCONTACTS, "contact_initiatives", UnifiedEntityType.EVENT),
        SourceTablePlan(SourceApp.SUPERCONTACTS, "contact_messaging_links", UnifiedEntityType.RELATIONSHIP),
        SourceTablePlan(SourceApp.SUPERCONTACTS, "saved_searches", UnifiedEntityType.FEATURE_LOCAL_RECORD),
        SourceTablePlan(SourceApp.SUPERCONTACTS, "saved_search_tags", UnifiedEntityType.TAG),
        SourceTablePlan(SourceApp.SUPERCONTACTS, "tags", UnifiedEntityType.TAG),
        SourceTablePlan(SourceApp.SUPERCONTACTS, "contact_tags", UnifiedEntityType.TAG),
        SourceTablePlan(SourceApp.WORDPULSE, "app_state", UnifiedEntityType.FEATURE_LOCAL_RECORD),
        SourceTablePlan(SourceApp.WORDPULSE, "correction_events", UnifiedEntityType.CORRECTION_EVENT),
        SourceTablePlan(SourceApp.WORDPULSE, "word_entries", UnifiedEntityType.WORD_ENTRY),
        SourceTablePlan(SourceApp.WORDPULSE, "sessions", UnifiedEntityType.WORD_SESSION),
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
