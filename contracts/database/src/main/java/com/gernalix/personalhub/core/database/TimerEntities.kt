package com.gernalix.personalhub.core.database

import androidx.room.*

@Entity(tableName = "audit_events", indices = [Index(value = ["ts_ms", "id"], name = "idx_audit_events_ts", orders = [Index.Order.DESC, Index.Order.DESC]), Index(value = ["action", "ts_ms"], name = "idx_audit_events_action_ts", orders = [Index.Order.ASC, Index.Order.DESC]), Index(value = ["is_system", "undone_at_ms", "id"], name = "idx_audit_events_visible_id", orders = [Index.Order.ASC, Index.Order.ASC, Index.Order.DESC]), Index(value = ["entity_type", "entity_id", "undone_at_ms", "id"], name = "idx_audit_events_entity_undo_id", orders = [Index.Order.ASC, Index.Order.ASC, Index.Order.ASC, Index.Order.DESC])])
data class TimerAuditEvents(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val `id`: Long,
    @ColumnInfo(name = "ts_ms")
    val `ts_ms`: Long,
    @ColumnInfo(name = "is_system", defaultValue = "0")
    val `is_system`: Long,
    @ColumnInfo(name = "action")
    val `action`: String,
    @ColumnInfo(name = "entity_type")
    val `entity_type`: String?,
    @ColumnInfo(name = "entity_id")
    val `entity_id`: Long?,
    @ColumnInfo(name = "summary")
    val `summary`: String,
    @ColumnInfo(name = "payload_json")
    val `payload_json`: String?,
    @ColumnInfo(name = "undone_at_ms")
    val `undone_at_ms`: Long?,
)

@Entity(tableName = "integrity_stats", primaryKeys = ["id"])
data class TimerIntegrityStats(
    @ColumnInfo(name = "id")
    val `id`: Long,
    @ColumnInfo(name = "json")
    val `json`: String,
    @ColumnInfo(name = "computed_at_ms")
    val `computed_at_ms`: Long,
)

@Entity(tableName = "quick_event_entries", primaryKeys = ["id"], indices = [Index(value = ["timestamp_ms"], name = "idx_quick_event_entries_timestamp", orders = [Index.Order.DESC]), Index(value = ["deleted_at_ms", "timestamp_ms", "id"], name = "idx_quick_event_entries_visible_timestamp", orders = [Index.Order.ASC, Index.Order.DESC, Index.Order.DESC]), Index(value = ["template_id"], name = "idx_quick_event_entries_template", orders = [Index.Order.ASC]), Index(value = ["macro_id"], name = "idx_quick_event_entries_macro", orders = [Index.Order.ASC])])
data class TimerQuickEventEntries(
    @ColumnInfo(name = "id")
    val `id`: Long,
    @ColumnInfo(name = "template_id")
    val `template_id`: Long?,
    @ColumnInfo(name = "macro_id")
    val `macro_id`: Long?,
    @ColumnInfo(name = "title")
    val `title`: String,
    @ColumnInfo(name = "timestamp_ms")
    val `timestamp_ms`: Long,
    @ColumnInfo(name = "created_at_ms")
    val `created_at_ms`: Long,
    @ColumnInfo(name = "updated_at_ms")
    val `updated_at_ms`: Long,
    @ColumnInfo(name = "deleted_at_ms")
    val `deleted_at_ms`: Long?,
)

@Entity(tableName = "quick_event_entry_field_values", primaryKeys = ["id"], indices = [Index(value = ["entry_id", "display_order"], name = "idx_quick_event_entry_field_values_entry", orders = [Index.Order.ASC, Index.Order.ASC])])
data class TimerQuickEventEntryFieldValues(
    @ColumnInfo(name = "id")
    val `id`: Long,
    @ColumnInfo(name = "entry_id")
    val `entry_id`: Long,
    @ColumnInfo(name = "field_id")
    val `field_id`: Long,
    @ColumnInfo(name = "label")
    val `label`: String,
    @ColumnInfo(name = "type", defaultValue = "'TEXT'")
    val `type`: String,
    @ColumnInfo(name = "value", defaultValue = "''")
    val `value`: String,
    @ColumnInfo(name = "display_order", defaultValue = "0")
    val `display_order`: Long,
)

@Entity(tableName = "quick_event_entry_tags", primaryKeys = ["entry_id", "tag_id"], indices = [Index(value = ["tag_id"], name = "idx_quick_event_entry_tags_tag", orders = [Index.Order.ASC]), Index(value = ["entry_id"], name = "idx_quick_event_entry_tags_entry", orders = [Index.Order.ASC]), Index(value = ["entry_id", "tag_id"], name = "ux_quick_event_entry_tags_edge", orders = [Index.Order.ASC, Index.Order.ASC], unique = true)])
data class TimerQuickEventEntryTags(
    @ColumnInfo(name = "entry_id")
    val `entry_id`: Long,
    @ColumnInfo(name = "tag_id")
    val `tag_id`: Long,
)

@Entity(tableName = "quick_event_macro_actions", primaryKeys = ["macro_id", "template_id"], indices = [Index(value = ["macro_id", "display_order"], name = "idx_quick_event_macro_actions_macro", orders = [Index.Order.ASC, Index.Order.ASC]), Index(value = ["template_id"], name = "idx_quick_event_macro_actions_template", orders = [Index.Order.ASC])])
data class TimerQuickEventMacroActions(
    @ColumnInfo(name = "macro_id")
    val `macro_id`: Long,
    @ColumnInfo(name = "template_id")
    val `template_id`: Long,
    @ColumnInfo(name = "display_order", defaultValue = "0")
    val `display_order`: Long,
)

@Entity(tableName = "quick_event_macro_tags", primaryKeys = ["macro_id", "tag_id"], indices = [Index(value = ["tag_id"], name = "idx_quick_event_macro_tags_tag", orders = [Index.Order.ASC]), Index(value = ["macro_id"], name = "idx_quick_event_macro_tags_macro", orders = [Index.Order.ASC]), Index(value = ["macro_id", "tag_id"], name = "ux_quick_event_macro_tags_edge", orders = [Index.Order.ASC, Index.Order.ASC], unique = true)])
data class TimerQuickEventMacroTags(
    @ColumnInfo(name = "macro_id")
    val `macro_id`: Long,
    @ColumnInfo(name = "tag_id")
    val `tag_id`: Long,
)

@Entity(tableName = "quick_event_macros", primaryKeys = ["id"], indices = [Index(value = ["sort_order", "title"], name = "idx_quick_event_macros_sort", orders = [Index.Order.ASC, Index.Order.ASC]), Index(value = ["deleted_at_ms", "is_archived", "sort_order", "title", "id"], name = "idx_quick_event_macros_visible_sort", orders = [Index.Order.ASC, Index.Order.ASC, Index.Order.ASC, Index.Order.ASC, Index.Order.ASC])])
data class TimerQuickEventMacros(
    @ColumnInfo(name = "id")
    val `id`: Long,
    @ColumnInfo(name = "title")
    val `title`: String,
    @ColumnInfo(name = "sort_order", defaultValue = "0")
    val `sort_order`: Long,
    @ColumnInfo(name = "is_archived", defaultValue = "0")
    val `is_archived`: Long,
    @ColumnInfo(name = "created_at_ms")
    val `created_at_ms`: Long,
    @ColumnInfo(name = "updated_at_ms")
    val `updated_at_ms`: Long,
    @ColumnInfo(name = "deleted_at_ms")
    val `deleted_at_ms`: Long?,
)

@Entity(tableName = "quick_event_template_fields", primaryKeys = ["id"], indices = [Index(value = ["template_id", "display_order"], name = "idx_quick_event_template_fields_template", orders = [Index.Order.ASC, Index.Order.ASC])])
data class TimerQuickEventTemplateFields(
    @ColumnInfo(name = "id")
    val `id`: Long,
    @ColumnInfo(name = "template_id")
    val `template_id`: Long,
    @ColumnInfo(name = "label")
    val `label`: String,
    @ColumnInfo(name = "type", defaultValue = "'TEXT'")
    val `type`: String,
    @ColumnInfo(name = "required", defaultValue = "0")
    val `required`: Long,
    @ColumnInfo(name = "default_value", defaultValue = "''")
    val `default_value`: String,
    @ColumnInfo(name = "choice_options_json", defaultValue = "'[]'")
    val `choice_options_json`: String,
    @ColumnInfo(name = "display_order", defaultValue = "0")
    val `display_order`: Long,
    @ColumnInfo(name = "created_at_ms")
    val `created_at_ms`: Long,
    @ColumnInfo(name = "updated_at_ms")
    val `updated_at_ms`: Long,
    @ColumnInfo(name = "deleted_at_ms")
    val `deleted_at_ms`: Long?,
)

@Entity(tableName = "quick_event_template_tags", primaryKeys = ["template_id", "tag_id"], indices = [Index(value = ["tag_id"], name = "idx_quick_event_template_tags_tag", orders = [Index.Order.ASC]), Index(value = ["template_id"], name = "idx_quick_event_template_tags_template", orders = [Index.Order.ASC]), Index(value = ["template_id", "tag_id"], name = "ux_quick_event_template_tags_edge", orders = [Index.Order.ASC, Index.Order.ASC], unique = true)])
data class TimerQuickEventTemplateTags(
    @ColumnInfo(name = "template_id")
    val `template_id`: Long,
    @ColumnInfo(name = "tag_id")
    val `tag_id`: Long,
)

@Entity(tableName = "quick_event_templates", primaryKeys = ["id"], indices = [Index(value = ["sort_order", "title"], name = "idx_quick_event_templates_sort", orders = [Index.Order.ASC, Index.Order.ASC]), Index(value = ["deleted_at_ms", "is_archived", "sort_order", "title", "id"], name = "idx_quick_event_templates_visible_sort", orders = [Index.Order.ASC, Index.Order.ASC, Index.Order.ASC, Index.Order.ASC, Index.Order.ASC])])
data class TimerQuickEventTemplates(
    @ColumnInfo(name = "id")
    val `id`: Long,
    @ColumnInfo(name = "title")
    val `title`: String,
    @ColumnInfo(name = "sort_order", defaultValue = "0")
    val `sort_order`: Long,
    @ColumnInfo(name = "is_archived", defaultValue = "0")
    val `is_archived`: Long,
    @ColumnInfo(name = "created_at_ms")
    val `created_at_ms`: Long,
    @ColumnInfo(name = "updated_at_ms")
    val `updated_at_ms`: Long,
    @ColumnInfo(name = "deleted_at_ms")
    val `deleted_at_ms`: Long?,
)

@Entity(tableName = "session_tags", primaryKeys = ["session_id", "tag_id"], indices = [Index(value = ["tag_id"], name = "idx_session_tags_tag", orders = [Index.Order.ASC]), Index(value = ["session_id"], name = "idx_session_tags_session", orders = [Index.Order.ASC]), Index(value = ["session_id", "tag_id"], name = "ux_session_tags_edge", orders = [Index.Order.ASC, Index.Order.ASC], unique = true)])
data class TimerSessionTags(
    @ColumnInfo(name = "session_id")
    val `session_id`: Long,
    @ColumnInfo(name = "tag_id")
    val `tag_id`: Long,
)

@Entity(tableName = "sessions", primaryKeys = ["id"], indices = [Index(value = ["deleted_at_ms", "start_ms", "id"], name = "idx_sessions_timeline", orders = [Index.Order.ASC, Index.Order.DESC, Index.Order.DESC]), Index(value = ["deleted_at_ms", "end_ms", "start_ms"], name = "idx_sessions_running", orders = [Index.Order.ASC, Index.Order.ASC, Index.Order.DESC]), Index(value = ["updated_at_ms", "id"], name = "idx_sessions_updated", orders = [Index.Order.DESC, Index.Order.DESC]), Index(value = ["deleted_at_ms", "id"], name = "idx_sessions_visible_id", orders = [Index.Order.ASC, Index.Order.DESC])])
data class TimerSessions(
    @ColumnInfo(name = "id")
    val `id`: Long,
    @ColumnInfo(name = "title")
    val `title`: String,
    @ColumnInfo(name = "start_ms")
    val `start_ms`: Long,
    @ColumnInfo(name = "end_ms")
    val `end_ms`: Long?,
    @ColumnInfo(name = "expected_end_ms")
    val `expected_end_ms`: Long?,
    @ColumnInfo(name = "created_at_ms")
    val `created_at_ms`: Long,
    @ColumnInfo(name = "updated_at_ms")
    val `updated_at_ms`: Long,
    @ColumnInfo(name = "deleted_at_ms")
    val `deleted_at_ms`: Long?,
)

@Entity(tableName = "snapshot", primaryKeys = ["id"])
data class TimerSnapshot(
    @ColumnInfo(name = "id")
    val `id`: Long,
    @ColumnInfo(name = "json")
    val `json`: String,
    @ColumnInfo(name = "saved_at_ms")
    val `saved_at_ms`: Long,
)

@Entity(tableName = "snapshot_history", indices = [Index(value = ["saved_at_ms"], name = "idx_snapshot_history_saved_at_ms", orders = [Index.Order.DESC]), Index(value = ["kind", "saved_at_ms", "id"], name = "idx_snapshot_history_kind_saved", orders = [Index.Order.ASC, Index.Order.DESC, Index.Order.DESC]), Index(value = ["payload_id"], name = "idx_snapshot_history_payload", orders = [Index.Order.ASC])])
data class TimerSnapshotHistory(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val `id`: Long,
    @ColumnInfo(name = "json")
    val `json`: String,
    @ColumnInfo(name = "saved_at_ms")
    val `saved_at_ms`: Long,
    @ColumnInfo(name = "kind", defaultValue = "'legacy_snapshot'")
    val `kind`: String,
    @ColumnInfo(name = "payload_id")
    val `payload_id`: Long?,
    @ColumnInfo(name = "base_history_id")
    val `base_history_id`: Long?,
    @ColumnInfo(name = "delta_json")
    val `delta_json`: String?,
    @ColumnInfo(name = "payload_hash")
    val `payload_hash`: String?,
)

@Entity(tableName = "snapshot_payloads", indices = [Index(value = ["hash"], unique = true), Index(value = ["hash"], name = "idx_snapshot_payloads_hash", orders = [Index.Order.ASC])])
data class TimerSnapshotPayloads(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val `id`: Long,
    @ColumnInfo(name = "hash")
    val `hash`: String,
    @ColumnInfo(name = "json")
    val `json`: String,
    @ColumnInfo(name = "created_at_ms")
    val `created_at_ms`: Long,
    @ColumnInfo(name = "size_bytes")
    val `size_bytes`: Long,
)

@Entity(tableName = "ui_prefs_mirror", primaryKeys = ["id"])
data class TimerUiPrefsMirror(
    @ColumnInfo(name = "id")
    val `id`: Long,
    @ColumnInfo(name = "json")
    val `json`: String,
    @ColumnInfo(name = "saved_at_ms")
    val `saved_at_ms`: Long,
)

@Entity(tableName = "sync_meta", primaryKeys = ["key"])
data class TimerSyncMeta(
    @ColumnInfo(name = "key")
    val `key`: String,
    @ColumnInfo(name = "long_value")
    val `long_value`: Long,
)

@Entity(tableName = "sync_queue", primaryKeys = ["sync_id"])
data class TimerSyncQueue(
    @ColumnInfo(name = "sync_id")
    val `sync_id`: String,
    @ColumnInfo(name = "row_json")
    val `row_json`: String,
    @ColumnInfo(name = "revision")
    val `revision`: Long,
    @ColumnInfo(name = "attempt_count", defaultValue = "0")
    val `attempt_count`: Long,
    @ColumnInfo(name = "created_at_ms")
    val `created_at_ms`: Long,
    @ColumnInfo(name = "last_attempt_at_ms")
    val `last_attempt_at_ms`: Long?,
    @ColumnInfo(name = "last_failure_class")
    val `last_failure_class`: String?,
)

@Entity(tableName = "sync_shadow", primaryKeys = ["sync_id"])
data class TimerSyncShadow(
    @ColumnInfo(name = "sync_id")
    val `sync_id`: String,
    @ColumnInfo(name = "fingerprint")
    val `fingerprint`: String,
    @ColumnInfo(name = "row_json")
    val `row_json`: String,
)
