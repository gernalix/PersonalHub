package com.example.multitimetracker

import com.example.multitimetracker.capsules.quickevents.public.QuickEventsSnapshot
import com.example.multitimetracker.model.LifePeriod
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.TimedTagNotificationType
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.contracts.database.HubTagEntity
import com.gernalix.personalhub.contracts.database.HubTagNamespaces
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.hubcontext.SharedTagEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/** Compatibility projection from the common tag authority to Timer's numeric domain references. */
internal class TimerSharedTagBridge(database: PersonalHubDatabase) {
    private val engine = SharedTagEngine(database)

    fun observe(namespace: String): Flow<List<Tag>> = engine.observe(namespace).map { values ->
        values.mapNotNull { it.toTimerTag() }
    }

    suspend fun add(namespace: String, name: String): Tag {
        val existing = engine.search(namespace, name).firstOrNull {
            SharedTagEngine.normalize(it.name) == SharedTagEngine.normalize(name)
        }
        if (existing != null) return requireNotNull(existing.toTimerTag())
        val next = engine.all().asSequence()
            .filter { it.namespace == namespace }
            .mapNotNull { it.numericId() }
            .maxOrNull()?.plus(1L) ?: 1L
        return requireNotNull(engine.createStable("$namespace:$next", namespace, name).toTimerTag())
    }

    suspend fun syncEvents(snapshot: QuickEventsSnapshot) {
        snapshot.templates.forEach { replace("quick_event_template", it.id, it.tagIds) }
        snapshot.entries.forEach { replace("quick_event_entry", it.id, it.tagIds) }
        snapshot.macros.forEach { replace("quick_event_macro", it.id, it.tagIds) }
    }

    suspend fun syncSinceWhen(periods: List<LifePeriod>) {
        periods.forEach { replace("counter", it.id, it.tagIds) }
    }

    suspend fun syncNow(tags: List<Tag>, sessions: List<SessionUi>) {
        val activeIds = tags.mapTo(mutableSetOf()) { "${HubTagNamespaces.TIMER_NOW}:${it.id}" }
        tags.forEach { tag ->
            engine.syncProjection(
                id = "${HubTagNamespaces.TIMER_NOW}:${tag.id}",
                namespace = HubTagNamespaces.TIMER_NOW,
                name = tag.name,
                archived = tag.isArchived || tag.isDeleted,
                metadataJson = JSONObject()
                    .put("timedDurationMinutes", tag.timedDurationMinutes)
                    .put("notificationType", tag.notificationType.name)
                    .put("showInTimeline", tag.showInTimeline)
                    .put("isDeleted", tag.isDeleted)
                    .put("deletedAtMs", tag.deletedAtMs)
                    .toString(),
            )
        }
        engine.all().filter { it.namespace == HubTagNamespaces.TIMER_NOW && it.id !in activeIds }
            .forEach { engine.archive(it.id, true) }
        sessions.distinctBy { it.id }.forEach { replace("session", it.id, it.tagIds) }
    }

    private suspend fun replace(kind: String, targetId: Long, legacyTagIds: Set<Long>) {
        val namespace = when (kind) {
            "life_period", "counter" -> HubTagNamespaces.SINCE_WHEN
            "session" -> HubTagNamespaces.TIMER_NOW
            else -> HubTagNamespaces.TIMER_EVENTS
        }
        val stableIds = legacyTagIds.map { "$namespace:$it" }
        val ref = if (kind == "counter") HubEntityRef("since_when", "counter", targetId.toString())
        else HubEntityRef("timer", kind, targetId.toString())
        engine.replace(ref, namespace, stableIds)
    }

    private fun HubTagEntity.numericId(): Long? = id.substringAfterLast(':').toLongOrNull()

    private fun HubTagEntity.toTimerTag(): Tag? {
        val numericId = numericId() ?: return null
        val metadata = metadataJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        val notification = metadata?.optString("notificationType")
            ?.let { value -> runCatching { TimedTagNotificationType.valueOf(value) }.getOrNull() }
            ?: TimedTagNotificationType.NONE
        return Tag(
            id = numericId,
            name = name,
            timedDurationMinutes = metadata?.optInt("timedDurationMinutes")?.takeIf { it > 0 },
            notificationType = notification,
            isArchived = archived,
            showInTimeline = metadata?.optBoolean("showInTimeline", true) ?: true,
            isDeleted = metadata?.optBoolean("isDeleted") == true,
            deletedAtMs = metadata?.optLong("deletedAtMs")?.takeIf { it > 0 },
            restoreSessionIds = emptySet(),
            activeChildrenCount = 0,
            totalMs = 0,
            lastStartedAtMs = null,
        )
    }
}
