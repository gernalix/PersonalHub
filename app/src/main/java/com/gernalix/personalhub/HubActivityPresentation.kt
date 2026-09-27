package com.gernalix.personalhub

import com.gernalix.personalhub.core.database.HubActivityEntity
import com.gernalix.personalhub.core.database.HubActivityPayloadKind
import com.gernalix.personalhub.core.database.HubActivityStatus
import com.gernalix.personalhub.core.database.capsules.sync.SyncJournal
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONObject

private val TECHNICAL_UUID_VALUE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")

internal data class HumanActivityChange(
    val label: String,
    val beforeText: String?,
    val afterText: String?,
) {
    fun render(): String = "$label: ${beforeText ?: "—"} → ${afterText ?: "—"}"
}

internal data class HumanActivityText(
    val title: String,
    val detail: String?,
    val searchText: String,
)

internal fun normalizeHistorySearchText(value: String, locale: Locale = Locale.getDefault()): String =
    Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(locale)
        .trim()

internal fun historyDateLabel(
    epochMs: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String = DateTimeFormatter.ofPattern("EEE d/M/yy", locale)
    .format(Instant.ofEpochMilli(epochMs).atZone(zoneId))

internal fun historyTimeLabel(epochMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(epochMs).atZone(zoneId))

internal fun historyDayLabel(
    epochMs: Long,
    todayMs: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val date = Instant.ofEpochMilli(epochMs).atZone(zoneId).toLocalDate()
    val today = Instant.ofEpochMilli(todayMs).atZone(zoneId).toLocalDate()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).format(date)
    }
}

internal fun groupGitHistoryRows(rows: List<com.gernalix.personalhub.core.database.capsules.gitdata.GitHistoryItem>) =
    rows.groupBy { it.groupId?.takeIf(String::isNotBlank) ?: it.id }.values.toList()

private val GIT_DERIVED_TABLES = setOf("hub_context_members", "hub_entity_bindings", "hub_activity_log")

internal fun displayableGitHistoryGroup(
    group: List<com.gernalix.personalhub.core.database.capsules.gitdata.GitHistoryItem>,
): Boolean = group.any { row ->
    row.table !in GIT_DERIVED_TABLES &&
        (!row.operation.equals("UPDATE", true) ||
            row.changedColumns.split(',').any { humanFieldLabel(it) != null })
}

internal fun humanizeGitHistoryGroup(
    group: List<com.gernalix.personalhub.core.database.capsules.gitdata.GitHistoryItem>,
    moduleLabel: (String) -> String,
): Pair<com.gernalix.personalhub.core.database.capsules.gitdata.GitHistoryItem, HumanActivityText> {
    require(group.isNotEmpty())
    val semantic = group.filter { row ->
        row.table !in GIT_DERIVED_TABLES &&
            (!row.operation.equals("UPDATE", true) ||
                row.changedColumns.split(',').any { humanFieldLabel(it) != null })
    }.ifEmpty { group }
    val primary = semantic.firstOrNull { it.table == "sessions" || it.table == "contacts" || it.table == "hub_tags" }
        ?: semantic.first()
    val workflowy = group.any { row ->
        row.table in setOf("hub_resources", "hub_contexts") &&
            listOf(row.displayBefore, row.displayAfter).any { it?.contains("workflowy", ignoreCase = true) == true }
    }
    val parts = semantic.map { humanizeGitHistory(it, moduleLabel(gitHistoryModule(it))) }
    val title = if (workflowy) {
        if (group.any { it.operation.equals("INSERT", true) }) "Linked Workflowy node"
        else "Removed Workflowy link"
    } else parts.first().title
    val detail = parts.mapNotNull(HumanActivityText::detail).flatMap { it.lines() }.distinct()
        .take(8).joinToString("\n").takeIf(String::isNotBlank)
    return primary to HumanActivityText(title, detail, listOfNotNull(title, detail).joinToString(" "))
}

internal fun groupActivityRows(rows: List<HubActivityEntity>): List<List<HubActivityEntity>> =
    rows.groupBy { it.groupId?.takeIf(String::isNotBlank) ?: it.id }.values.toList()

internal fun safeUndoActivityId(group: List<HubActivityEntity>): String? {
    if (group.size != 1) return null // The fallback engine reverses one row, never a partial logical edit.
    val candidates = group.filter {
        it.reversible &&
            it.status == HubActivityStatus.ACTIVE &&
            it.revertsActivityId == null
    }
    return candidates.singleOrNull()?.id
}

internal fun humanizeActivity(
    activity: HubActivityEntity,
    subjectLabel: String?,
    moduleLabel: String,
): HumanActivityText {
    if (activity.sourceTable == "hub_contexts" && subjectLabel == "Workflowy") {
        val title = if (activity.action.contains("delete")) "Removed Workflowy link" else "Linked Workflowy node"
        return HumanActivityText(title, null, "$title $moduleLabel")
    }
    val entityType = humanEntityType(activity.entityKind)
    val subject = subjectLabel?.trim()?.takeIf(String::isNotEmpty)
    val changes = activityChanges(activity)
    val action = activity.action.lowercase(Locale.ROOT)
    val entityDescription = when {
        entityType != null && subject != null -> "${entityType.lowercase()} “$subject”"
        subject != null -> "“$subject”"
        entityType != null -> entityType.lowercase()
        else -> moduleLabel
    }
    val rename = changes.firstOrNull {
        it.beforeText != null &&
            it.afterText != null &&
            it.label.lowercase(Locale.ROOT) in setOf("name", "title", "nickname")
    }
    val baseTitle = if (
        rename != null &&
        (action.contains("rename") || action.contains("update") || action.contains("edit") || action.contains("change"))
    ) {
        val type = entityType?.lowercase()?.plus(" ") ?: ""
        "Renamed $type“${rename.beforeText}” to “${rename.afterText}”"
    } else {
        "${humanActionVerb(action)} $entityDescription"
    }
    val primaryChange = changes.firstOrNull { it !== rename }
        ?: changes.firstOrNull()?.takeUnless { rename != null }
    val title = primaryChange?.let { "$baseTitle · ${it.render()}" } ?: baseTitle

    val fallbackDetail = humanDetail(activity)
    val detailParts = buildList {
        addAll(changes.take(8).map(HumanActivityChange::render))
        fallbackDetail?.takeIf { candidate -> none { it == candidate } }?.let(::add)
    }
    val detail = detailParts.takeIf(List<String>::isNotEmpty)?.joinToString("\n")
    val searchText = buildList {
        add(title)
        add(moduleLabel)
        entityType?.let(::add)
        subject?.let(::add)
        changes.forEach { change ->
            add(change.label)
            change.beforeText?.let(::add)
            change.afterText?.let(::add)
        }
        fallbackDetail?.let(::add)
    }.joinToString(" ")

    return HumanActivityText(title = title, detail = detail, searchText = searchText)
}

private fun humanActionVerb(action: String): String = when {
    "restore" in action || "reopen" in action -> "Restored"
    "archive" in action -> "Archived"
    "delete" in action || action == "deleted" -> "Deleted"
    "create" in action || action == "created" || "add" in action -> "Created"
    "record" in action -> "Recorded"
    "start" in action -> "Started"
    "stop" in action || "complete" in action -> "Completed"
    "unlink" in action -> "Unlinked"
    "link" in action -> "Linked"
    "rename" in action -> "Renamed"
    "update" in action || "modify" in action || "edit" in action || "change" in action -> "Updated"
    else -> "Changed"
}

private fun humanEntityType(kind: String?): String? = when (kind?.lowercase(Locale.ROOT)) {
    null, "" -> null
    "person", "contact" -> "Person"
    "place", "place_event" -> "Place"
    "account" -> "Account"
    "transaction" -> "Transaction"
    "substance" -> "Substance"
    "intake" -> "Intake"
    "stock_adjustment" -> "Stock adjustment"
    "prescription" -> "Prescription"
    "macro" -> "Macro"
    "setting" -> "Setting"
    "session" -> "Session"
    "episode" -> "Episode"
    "tag" -> "Tag"
    else -> humanFieldLabel(kind)
}

private fun activityChanges(activity: HubActivityEntity): List<HumanActivityChange> = when (activity.payloadKind) {
    HubActivityPayloadKind.ROW_V1 -> rowChanges(activity)
    HubActivityPayloadKind.PEOPLE_EVENT_V1 -> directChange(activity)
    HubActivityPayloadKind.TIMER_AUDIT_V1,
    HubActivityPayloadKind.PLACES_AUDIT_V1,
    -> jsonChanges(activity)
    else -> directChange(activity)
}

private fun directChange(activity: HubActivityEntity): List<HumanActivityChange> {
    val label = humanFieldLabel(activity.detailKey) ?: return emptyList()
    val before = cleanHumanValue(activity.beforePayload)
    val after = cleanHumanValue(activity.afterPayload)
    if (before == after || (before == null && after == null)) return emptyList()
    return listOf(HumanActivityChange(label, before, after))
}

private fun rowChanges(activity: HubActivityEntity): List<HumanActivityChange> {
    val columns = activity.payloadColumns?.split(',')?.filter(String::isNotBlank).orEmpty()
    if (columns.isEmpty()) return emptyList()
    val before = decodeRowPayload(activity.beforePayload)
    val after = decodeRowPayload(activity.afterPayload)
    return columns.mapIndexedNotNull { index, column ->
        val label = humanFieldLabel(column) ?: return@mapIndexedNotNull null
        val oldValue = humanValue(before?.getOrNull(index))
        val newValue = humanValue(after?.getOrNull(index))
        if (oldValue == newValue || (oldValue == null && newValue == null)) null
        else HumanActivityChange(label, oldValue, newValue)
    }
}

private fun jsonChanges(activity: HubActivityEntity): List<HumanActivityChange> {
    val before = humanJsonValues(activity.beforePayload)
    val after = humanJsonValues(activity.afterPayload)
    if (before.isEmpty() && after.isEmpty()) {
        return directChange(activity)
    }
    return (before.keys + after.keys).distinct().mapNotNull { key ->
        val label = humanFieldLabel(key) ?: return@mapNotNull null
        val oldValue = before[key]
        val newValue = after[key]
        if (oldValue == newValue || (oldValue == null && newValue == null)) null
        else HumanActivityChange(label, oldValue, newValue)
    }
}

private fun decodeRowPayload(payload: String?): List<Any?>? =
    payload?.let { runCatching { SyncJournal.keyValues(it).toList() }.getOrNull() }

private fun humanJsonValues(payload: String?): Map<String, String> {
    if (payload.isNullOrBlank()) return emptyMap()
    val json = runCatching { JSONObject(payload) }.getOrNull() ?: return emptyMap()
    return buildMap {
        json.keys().forEach { key ->
            if (humanFieldLabel(key) == null) return@forEach
            val raw = json.opt(key)
            if (raw is JSONObject || raw == JSONObject.NULL) return@forEach
            cleanHumanValue(raw?.toString())?.let { put(key, it) }
        }
    }
}

private fun humanDetail(activity: HubActivityEntity): String? {
    val label = humanFieldLabel(activity.detailKey) ?: return null
    val raw = activity.detailValue?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val json = humanJsonValues(raw)
    if (json.isNotEmpty()) {
        return json.entries
            .take(8)
            .joinToString(" · ") { (key, value) -> "${humanFieldLabel(key) ?: ""}: $value" }
            .takeIf(String::isNotBlank)
    }
    val value = cleanHumanValue(raw) ?: return null
    return "$label: $value"
}

private fun humanValue(value: Any?): String? = when (value) {
    null -> null
    is ByteArray -> null
    is Boolean -> if (value) "Yes" else "No"
    is Number -> value.toString()
    else -> cleanHumanValue(value.toString())
}

internal fun cleanHumanValue(value: String?): String? {
    val trimmed = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (trimmed.length > 240) return null
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) return null
    if (TECHNICAL_UUID_VALUE.matches(trimmed)) return null
    return trimmed
}

internal fun humanFieldLabel(raw: String?): String? {
    val key = raw
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
        ?.replace(' ', '_')
        ?.lowercase(Locale.ROOT)
        ?: return null
    if (
        key == "id" ||
        key == "uuid" ||
        key.endsWith("_id") ||
        key.endsWith("_uuid") ||
        key.endsWith("_json") ||
        key.endsWith("_at") ||
        key.endsWith("_ms") ||
        key.endsWith("_utc") ||
        key.endsWith("_epoch") ||
        key == "timestamp" ||
        key.endsWith("_timestamp") ||
        key.startsWith("normalized_") ||
        key.startsWith("sort_") ||
        key.contains("cursor") ||
        key.contains("hash") ||
        key.contains("revision") ||
        key.contains("schema") ||
        key.contains("payload") ||
        key.contains("source_row") ||
        key == "resource_kind" ||
        key in setOf("public_id", "source", "source_app", "version", "app_version", "metadata",
            "provenance", "position", "usage_count", "entity_kind", "entity_type", "stock_current",
            "timestamp_utc", "timestamp_ms", "row_key", "table_name")
    ) {
        return null
    }
    return when (key) {
        "name" -> "Name"
        "nickname" -> "Nickname"
        "title" -> "Title"
        "amount" -> "Amount"
        "currency" -> "Currency"
        "address" -> "Address"
        "notes" -> "Notes"
        "description" -> "Description"
        "type" -> "Type"
        "quantity" -> "Quantity"
        "unit" -> "Unit"
        "status" -> "Status"
        "reason" -> "Reason"
        "value" -> "Value"
        "archived" -> "Archived"
        "pinned" -> "Pinned"
        "is_global" -> "Global"
        "icon" -> "Icon"
        "color" -> "Color"
        "stock_unit" -> "Stock unit"
        "dose_per_intake" -> "Dose"
        "dose_unit" -> "Dose unit"
        "daily_frequency" -> "Daily frequency"
        "start_epoch_day" -> "Start date"
        "end_epoch_day" -> "End date"
        "forever" -> "Ongoing"
        "prn" -> "As needed"
        "dose_times_csv" -> "Dose times"
        "days_mask" -> "Days"
        "summary" -> "Summary"
        "field_type" -> "Field"
        "action_type" -> "Action"
        "event_type" -> "Event"
        else -> key.split('_')
            .filter(String::isNotBlank)
            .joinToString(" ") { token -> token.replaceFirstChar(Char::uppercase) }
            .takeIf(String::isNotBlank)
    }
}


private val GIT_PEOPLE_TABLES = setOf(
    "contacts", "contact_fields", "contact_events", "contact_initiatives", "contact_messaging_links",
    "saved_searches", "saved_search_tags", "tags", "contact_tags", "people_photos",
)

private val GIT_PLACES_TABLES = setOf(
    "places", "place_aliases", "place_links", "place_events", "check_in_attempts",
    "check_in_attempt_candidates", "place_geofence_configs", "place_geofence_transition_log",
    "place_tags", "place_tag_cross_ref",
    "history_audit_log", "history_actions", "global_stats_state", "route_distance_cache",
)

private val GIT_SUBSTANCES_TABLES = setOf(
    "substances", "intake_events", "stock_adjustments", "prescriptions", "interaction_rules",
    "interaction_targets", "settings", "macros", "macro_items", "notification_state",
)

private val GIT_WORDPULSE_TABLES = setOf(
    "app_state", "correction_events", "word_entries", "wordpulse_sessions", "pvt_results",
)

private val GIT_TIMER_TABLES = setOf(
    "sessions", "session_tags", "quick_event_entries", "quick_event_entry_field_values",
    "quick_event_entry_tags", "quick_event_macro_actions", "quick_event_macro_tags",
    "quick_event_macros", "quick_event_template_fields", "quick_event_template_tags",
    "quick_event_templates", "integrity_stats", "snapshot", "snapshot_history",
    "snapshot_payloads", "ui_prefs_mirror",
)

private val GIT_TAG_TABLES = setOf(
    "hub_tags", "hub_tag_aliases", "hub_tag_assignments", "hub_tag_parents", "hub_saved_tag_filters",
)

private val GIT_ALERT_TABLES = setOf("alert_rules", "alert_rule_targets", "alert_firings")

internal fun gitHistoryModule(table: String): String = when {
    table.startsWith("finance_") -> "soldi"
    table in GIT_PEOPLE_TABLES -> "people"
    table in GIT_PLACES_TABLES -> "places"
    table in GIT_SUBSTANCES_TABLES -> "substances"
    table in GIT_WORDPULSE_TABLES -> "wordpulse"
    table in GIT_TIMER_TABLES -> "timer"
    table in GIT_TAG_TABLES -> "tags"
    else -> "hub"
}

internal fun gitHistoryModule(
    item: com.gernalix.personalhub.core.database.capsules.gitdata.GitHistoryItem,
): String {
    if (item.table in GIT_ALERT_TABLES) {
        val payload = item.displayAfter ?: item.displayBefore
        val domain = runCatching { JSONObject(payload.orEmpty()).optString("domain") }.getOrNull()
        if (!domain.isNullOrBlank()) return domain
    }
    return gitHistoryModule(item.table)
}

internal val GIT_HISTORY_ENTITY_TYPES = mapOf(
    "finance_accounts" to "account",
    "finance_products" to "product",
    "finance_titles" to "title",
    "finance_chains" to "chain",
    "finance_stores" to "store",
    "finance_transactions" to "transaction",
    "finance_tags" to "finance tag",
    "finance_transaction_tags" to "transaction tag",
    "finance_transfers" to "transfer",
    "finance_macros" to "finance macro",
    "finance_recurrences" to "recurrence",
    "finance_recurrence_tags" to "recurrence tag",
    "finance_recurrence_overrides" to "recurrence override",
    "finance_attachments" to "attachment",
    "finance_photo_index" to "photo",
    "finance_owned_items" to "owned item",
    "contacts" to "person",
    "contact_fields" to "person field",
    "contact_events" to "person event",
    "contact_initiatives" to "initiative",
    "contact_messaging_links" to "messaging link",
    "saved_searches" to "saved search",
    "saved_search_tags" to "saved-search tag",
    "tags" to "person tag",
    "contact_tags" to "person tag",
    "places" to "place",
    "place_aliases" to "place alias",
    "place_links" to "place link",
    "place_events" to "visit",
    "check_in_attempts" to "check-in attempt",
    "check_in_attempt_candidates" to "check-in candidate",
    "place_geofence_configs" to "geofence",
    "place_geofence_transition_log" to "geofence transition",
    "place_tags" to "place tag",
    "place_tag_cross_ref" to "place-tag link",
    "alert_rules" to "alert",
    "substances" to "substance",
    "intake_events" to "intake",
    "stock_adjustments" to "stock adjustment",
    "prescriptions" to "prescription",
    "interaction_rules" to "interaction rule",
    "interaction_targets" to "interaction target",
    "settings" to "substance setting",
    "macros" to "substance macro",
    "macro_items" to "macro item",
    "app_state" to "WordPulse state",
    "correction_events" to "correction",
    "word_entries" to "word entry",
    "wordpulse_sessions" to "typing session",
    "pvt_results" to "reaction test",
    "quick_event_entries" to "quick event",
    "quick_event_entry_field_values" to "quick-event field",
    "quick_event_entry_tags" to "quick-event tag",
    "quick_event_macro_actions" to "quick-event macro action",
    "quick_event_macro_tags" to "quick-event macro tag",
    "quick_event_macros" to "quick-event macro",
    "quick_event_template_fields" to "quick-event template field",
    "quick_event_template_tags" to "quick-event template tag",
    "quick_event_templates" to "quick-event template",
    "session_tags" to "session tag",
    "sessions" to "session",
    "people_photos" to "person photo",
    "hub_preferences" to "preference",
    "hub_entity_bindings" to "entity link",
    "hub_context_types" to "context type",
    "hub_context_type_fields" to "context field",
    "hub_contexts" to "context",
    "hub_context_members" to "context member",
    "hub_tags" to "tag",
    "hub_tag_aliases" to "tag alias",
    "hub_tag_assignments" to "tag assignment",
    "hub_tag_parents" to "tag hierarchy link",
    "hub_saved_tag_filters" to "saved tag filter",
    "hub_resources" to "resource",
    "alert_rules" to "alert",
    "alert_rule_targets" to "alert target",
    "alert_firings" to "alert firing",
    "since_when_counters" to "counter",
    "history_audit_log" to "place audit event",
    "history_actions" to "history action",
    "hub_activity_log" to "activity event",
    "snapshot" to "timer snapshot",
    "snapshot_history" to "timer snapshot history",
    "snapshot_payloads" to "timer snapshot payload",
    "integrity_stats" to "timer integrity state",
    "ui_prefs_mirror" to "timer preference",
    "backup_metadata" to "backup metadata",
    "global_stats_state" to "place statistics state",
    "route_distance_cache" to "route distance",
    "notification_state" to "notification state",
)

internal fun gitHistoryEntityType(table: String): String =
    GIT_HISTORY_ENTITY_TYPES[table] ?: humanizeAuditTableName(table)

private fun humanizeAuditTableName(table: String): String {
    val stripped = table
        .removePrefix("finance_")
        .removePrefix("wordpulse_")
        .removePrefix("contact_")
        .removePrefix("place_")
        .removePrefix("quick_event_")
        .removePrefix("hub_")
    val words = stripped.split('_').filter(String::isNotBlank).toMutableList()
    if (words.isEmpty()) return "item"
    val last = words.last()
    words[words.lastIndex] = when {
        last.endsWith("ies") && last.length > 3 -> last.dropLast(3) + "y"
        last.endsWith("ses") && last.length > 3 -> last.dropLast(2)
        last.endsWith("s") && !last.endsWith("ss") && last.length > 1 -> last.dropLast(1)
        else -> last
    }
    return words.joinToString(" ")
}

internal fun humanizeGitHistory(
    item: com.gernalix.personalhub.core.database.capsules.gitdata.GitHistoryItem,
    moduleLabel: String,
): HumanActivityText {
    val before = runCatching { JSONObject(item.displayBefore.orEmpty()) }.getOrNull()
    val after = runCatching { JSONObject(item.displayAfter.orEmpty()) }.getOrNull()
    if (item.table == "alert_firings") {
        val data = after ?: before ?: JSONObject()
        val domain = data.optString("domain").takeIf(String::isNotBlank) ?: moduleLabel.lowercase(Locale.ROOT)
        val domainLabel = when (domain) {
            "timer" -> "Timer"
            "places" -> "Places"
            "substances" -> "Substances"
            else -> domain.replaceFirstChar(Char::uppercase)
        }
        val trigger = when (data.optString("trigger")) {
            "TIMER_START" -> "session started"
            "TIMER_STOP" -> "session stopped"
            "PLACE_CHECK_IN" -> "check-in"
            "PLACE_CHECK_OUT" -> "check-out"
            "PLACE_BOTH" -> "check-in/out"
            else -> "event"
        }
        val entity = cleanHumanValue(data.optString("entity_label"))
        val tags = cleanHumanValue(data.optString("tag_names"))
        val delivery = cleanHumanValue(data.optString("delivery"))
        val message = cleanHumanValue(data.optString("message"))
        val subject = entity?.let { " · " + it }.orEmpty()
        val title = "Alert fired · " + domainLabel + " · " + trigger + subject
        val detail = listOfNotNull(
            tags?.let { "Tags: " + it },
            delivery?.let { "Delivery: " + it.replace('_', ' ') },
            message?.let { "Message: " + it },
        ).joinToString("\n").takeIf(String::isNotBlank)
        return HumanActivityText(title, detail, listOfNotNull(title, detail).joinToString(" "))
    }
    val objectName = listOf("name", "nickname", "title", "label", "display_name", "alias", "text", "query", "summary", "message")
        .firstNotNullOfOrNull { key -> cleanHumanValue(after?.optString(key)) ?: cleanHumanValue(before?.optString(key)) }
    val type = gitHistoryEntityType(item.table)
    val changes = item.changedColumns.split(',').mapNotNull { key ->
        val label = humanFieldLabel(key) ?: return@mapNotNull null
        val oldValue = cleanHumanValue(before?.opt(key)?.takeUnless { it == JSONObject.NULL }?.toString())
        val newValue = cleanHumanValue(after?.opt(key)?.takeUnless { it == JSONObject.NULL }?.toString())
        if (oldValue == newValue) null else HumanActivityChange(label, oldValue, newValue)
    }
    val action = when (item.operation.uppercase(Locale.ROOT)) {
        "INSERT" -> "Created"
        "DELETE" -> "Deleted"
        "UPDATE" -> "Updated"
        else -> "Changed"
    }
    val subject = objectName?.let { "$type “$it”" } ?: type
    val title = "$action $subject" + (changes.firstOrNull()?.let { " · ${it.render()}" } ?: "")
    val detail = changes.take(8).joinToString("\n") { it.render() }.takeIf(String::isNotBlank)
    return HumanActivityText(title, detail, listOfNotNull(title, moduleLabel, detail).joinToString(" "))
}
