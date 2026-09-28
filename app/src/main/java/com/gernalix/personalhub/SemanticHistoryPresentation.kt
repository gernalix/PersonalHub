package com.gernalix.personalhub

import com.gernalix.personalhub.core.database.capsules.mutationevents.MutationEvent
import org.json.JSONObject

/** One human row per recorded application intention. Unknown facts stay in Audit. */
internal data class SemanticHistoryRow(
    val transactionId: String,
    val occurredAt: Long,
    val module: String,
    val text: HumanActivityText,
    val events: List<MutationEvent>,
)

internal fun semanticHistoryRows(events: List<MutationEvent>): List<SemanticHistoryRow> =
    events.groupBy(MutationEvent::transactionId).mapNotNull { (transactionId, group) ->
        val visible = group.filter { it.actorType == "user" }
        val rendered = visible.mapNotNull { event -> renderSemanticEvent(event)?.let { event to it } }
        val primary = rendered.maxByOrNull { (event, _) -> semanticPriority(event) } ?: return@mapNotNull null
        val text = primary.second
        val detail = rendered.mapNotNull { it.second.detail }.distinct().take(4).joinToString(" · ").takeIf(String::isNotBlank)
        SemanticHistoryRow(
            transactionId = transactionId,
            occurredAt = group.maxOf(MutationEvent::occurredAt),
            module = primary.first.module,
            text = HumanActivityText(
                title = text.title,
                detail = detail,
                searchText = rendered.joinToString(" ") { it.second.searchText },
            ),
            events = group.sortedBy(MutationEvent::sequence),
        )
    }.sortedWith(compareByDescending<SemanticHistoryRow> { it.occurredAt }.thenByDescending { it.transactionId })

private fun semanticPriority(event: MutationEvent): Int = when (event.eventType) {
    "workflowy.link.assigned", "workflowy.link.unlinked" -> 110
    "substances.intake.created" -> 90
    "timer.session.created", "timer.session.updated", "timer.session.deleted" -> 80
    "people.field.created", "people.field.updated", "people.field.deleted" -> 70
    "places.place.created", "places.place.updated", "places.place.deleted" -> 60
    "tags.tag.created", "tags.tag.updated", "tags.tag.deleted" -> 50
    else -> 10
}

private fun renderSemanticEvent(event: MutationEvent): HumanActivityText? {
    val before = event.beforeJson?.let(::JSONObject)
    val after = event.afterJson?.let(::JSONObject)
    val context = JSONObject(event.contextJson)
    val rawName = context.optString("name").takeIf(String::isNotBlank)
        ?: after?.optString("name")?.takeIf(String::isNotBlank)
        ?: before?.optString("name")?.takeIf(String::isNotBlank)
        ?: after?.optString("title")?.takeIf(String::isNotBlank)
        ?: before?.optString("title")?.takeIf(String::isNotBlank)
    val name = cleanHumanValue(rawName?.removePrefix("Workflowy · ")) ?: return null
    val quoted = "“$name”"
    val title = when (event.eventType) {
        "workflowy.link.assigned" ->
            if (name == "Workflowy") "Linked Workflowy node" else "Linked $quoted to Workflowy"
        "workflowy.link.unlinked" ->
            if (name == "Workflowy") "Removed Workflowy link" else "Removed Workflowy link for $quoted"
        "timer.session.created" -> "Created session $quoted"
        "timer.session.deleted" -> "Deleted session $quoted"
        "timer.session.updated" -> {
            if (before?.has("title") == true && after?.has("title") == true) {
                val old = cleanHumanValue(before.optString("title")) ?: return null
                "Renamed session “$old” to $quoted"
            } else "Updated session $quoted"
        }
        "substances.intake.created" -> {
            val dose = after?.opt("dose")?.toString()?.let(::cleanHumanValue)
            val unit = after?.optString("dose_unit")?.let(::cleanHumanValue)
            if (dose == null) "Recorded intake of $quoted"
            else "Recorded $dose${unit?.let { " $it" }.orEmpty()} of $quoted"
        }
        "substances.substance.created" -> "Created substance $quoted"
        "substances.substance.deleted" -> "Deleted substance $quoted"
        "substances.substance.updated" -> {
            if (before?.has("stock_current") == true && after?.has("stock_current") == true) {
                "Updated quantity of $quoted from ${before.opt("stock_current")} to ${after.opt("stock_current")}"
            } else "Updated substance $quoted"
        }
        "places.place.created" -> "Created place $quoted"
        "places.place.updated" -> "Updated place $quoted"
        "places.place.deleted" -> "Deleted place $quoted"
        "tags.tag.created" -> "Created tag $quoted"
        "tags.tag.updated" -> "Updated tag $quoted"
        "tags.tag.deleted" -> "Deleted tag $quoted"
        "people.field.created" -> if (after?.optString("field_type") == "name") "Created person $quoted" else return null
        "people.field.updated" -> if (after?.has("value") == true && before?.has("value") == true) "Updated person $quoted" else return null
        "money.transaction.created" -> "Recorded transaction $quoted"
        "money.transaction.updated" -> "Updated transaction $quoted"
        "money.transaction.deleted" -> "Deleted transaction $quoted"
        else -> return null
    }
    val changes = if (before != null && after != null) before.keys().asSequence().mapNotNull { key ->
        val label = semanticFieldLabel(key) ?: return@mapNotNull null
        val old = cleanHumanValue(before.opt(key)?.toString())
        val new = cleanHumanValue(after.opt(key)?.toString())
        if (old == null && new == null) null else "$label: ${old ?: "—"} → ${new ?: "—"}"
    }.take(3).toList().joinToString(" · ").takeIf(String::isNotBlank) else null
    return HumanActivityText(title, changes, listOfNotNull(title, changes).joinToString(" "))
}

private fun semanticFieldLabel(key: String): String? = when (key) {
    "name", "title", "nickname" -> "Name"
    "address" -> "Address"
    "notes", "description" -> "Notes"
    "amount" -> "Amount"
    "currency" -> "Currency"
    "stock_current", "quantity" -> "Quantity"
    "dose", "dose_per_intake" -> "Dose"
    "dose_unit", "stock_unit" -> "Unit"
    "archived" -> "Archived"
    "pinned" -> "Pinned"
    else -> null
}
