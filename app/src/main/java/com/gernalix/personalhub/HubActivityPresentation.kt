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

internal fun groupActivityRows(rows: List<HubActivityEntity>): List<List<HubActivityEntity>> =
    rows.groupBy { it.groupId?.takeIf(String::isNotBlank) ?: it.id }.values.toList()

internal fun safeUndoActivityId(group: List<HubActivityEntity>): String? {
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
        key.startsWith("normalized_") ||
        key.startsWith("sort_") ||
        key.contains("cursor") ||
        key.contains("hash") ||
        key.contains("revision") ||
        key.contains("schema") ||
        key.contains("payload") ||
        key.contains("source_row") ||
        key in setOf("public_id", "source", "source_app", "version", "app_version", "metadata")
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


internal fun gitHistoryModule(table: String): String = when {
    table.startsWith("finance_") -> "soldi"
    table.startsWith("wordpulse_") -> "wordpulse"
    table == "contacts" || table.startsWith("contact_") || table.startsWith("people_") -> "people"
    table.startsWith("timer_") || table.startsWith("time_") || table.startsWith("session") ||
        table == "audit_events" -> "timer"
    table.startsWith("place") || table.startsWith("route_") || table.startsWith("visit_") -> "places"
    table.startsWith("substance") || table.startsWith("intake_") || table.startsWith("stock_") ||
        table.startsWith("prescription") || table.startsWith("macro") -> "substances"
    table.startsWith("hub_tag") -> "tags"
    else -> "hub"
}

internal fun humanizeGitHistory(
    item: com.gernalix.personalhub.core.database.capsules.gitdata.GitHistoryItem,
    moduleLabel: String,
): HumanActivityText {
    val before = runCatching { JSONObject(item.displayBefore.orEmpty()) }.getOrNull()
    val after = runCatching { JSONObject(item.displayAfter.orEmpty()) }.getOrNull()
    val objectName = listOf("name", "nickname", "title", "label")
        .firstNotNullOfOrNull { key -> cleanHumanValue(after?.optString(key)) ?: cleanHumanValue(before?.optString(key)) }
    val type = when (item.table) {
        "places" -> "place"
        "finance_accounts" -> "account"
        "finance_transactions" -> "transaction"
        "intake_events" -> "intake"
        "stock_adjustments" -> "stock adjustment"
        "contact_events" -> "person event"
        "contacts" -> "person"
        "substances" -> "substance"
        "prescriptions" -> "prescription"
        "sessions" -> "session"
        "hub_tags" -> "tag"
        else -> "item"
    }
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
