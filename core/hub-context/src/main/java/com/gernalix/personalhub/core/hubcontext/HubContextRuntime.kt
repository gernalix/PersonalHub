package com.gernalix.personalhub.core.hubcontext

import android.content.Context
import com.gernalix.personalhub.contracts.database.*
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import java.time.Instant
import kotlinx.coroutines.flow.Flow

data class HubTemporalFact(
    val contextId: String,
    val sessionId: String,
    val placeId: String,
    val startMs: Long,
    val endMs: Long?,
    val people: List<HubEntitySummary>,
)

object HubContextRuntime {
    const val TIMER_ACTIVITY_TYPE = "timer_activity"
    @Volatile private var registry: HubAdapterRegistry? = null
    @Volatile private var repository: HubContextRepository? = null

    fun initialize(context: Context, adapters: Collection<HubEntityAdapter>) {
        val resolvedRegistry = HubAdapterRegistry(adapters)
        registry = resolvedRegistry
        repository = HubContextRepository(PersonalHubDatabase.get(context.applicationContext), resolvedRegistry)
    }

    fun adapter(moduleId: String, entityKind: String): HubEntityAdapter =
        requireNotNull(registry) { "Hub Context runtime is not initialized" }
            .adapter(HubEntityRef(moduleId, entityKind, ""))

    fun adapters(): List<HubEntityAdapter> = requireNotNull(registry) { "Hub Context runtime is not initialized" }.all().toList()

    suspend fun createContext(refs: List<Pair<HubEntityRef, String>>, typeId: String? = null, title: String? = null): String {
        val repo = requireNotNull(repository)
        val drafts = refs.distinct().map { (ref, role) -> HubContextMemberDraft(repo.bind(ref).id, role) }
        return repo.createContext(drafts, typeId, title)
    }

    suspend fun updateContext(contextId: String, refs: List<Pair<HubEntityRef, String>>, typeId: String? = null, title: String? = null) {
        val repo = requireNotNull(repository)
        val drafts = refs.distinct().map { (ref, role) -> HubContextMemberDraft(repo.bind(ref).id, role) }
        repo.updateContext(contextId, drafts, typeId, title)
    }

    suspend fun context(contextId: String) = requireNotNull(repository).context(contextId)
    suspend fun contexts(ref: HubEntityRef) = requireNotNull(repository).viewsFor(ref)
    suspend fun contextTypes() = requireNotNull(repository).types()
    suspend fun contextType(typeId: String) = requireNotNull(repository).type(typeId)
    suspend fun saveContextType(type: HubContextType, fields: List<HubContextTypeField>) = requireNotNull(repository).saveType(type, fields)
    suspend fun deleteContextType(typeId: String) = requireNotNull(repository).deleteType(typeId)
    suspend fun saveCombinationAsType(contextId: String, name: String) = requireNotNull(repository).saveCombinationAsType(contextId, name)
    suspend fun explore(scope: List<HubEntityRef>, limit: Int = 100, offset: Int = 0) = requireNotNull(repository).explore(scope, limit, offset)

    suspend fun ensureTimerActivityType() {
        val now = Instant.now().toString()
        requireNotNull(repository).saveSystemType(
            HubContextType(TIMER_ACTIVITY_TYPE, "Timer activity", now, now, locked = true),
            listOf(
                HubContextTypeField(TIMER_ACTIVITY_TYPE, "session", 0, "Session", "anchor", "timer", "session", minCardinality = 1, maxCardinality = 1),
                HubContextTypeField(TIMER_ACTIVITY_TYPE, "people", 1, "People", "participant", "people", "person", minCardinality = 0, maxCardinality = null),
                HubContextTypeField(TIMER_ACTIVITY_TYPE, "place", 2, "Place", "participant", "places", "place", minCardinality = 0, maxCardinality = 1),
            ),
        )
    }

    suspend fun saveTimerLinks(sessionId: Long, peopleIds: Set<String>, placeId: String?) {
        ensureTimerActivityType()
        val companions = peopleIds.map { HubEntityRef("people", "person", it) } +
            listOfNotNull(placeId?.let { HubEntityRef("places", "place", it) })
        requireNotNull(repository).replaceContextForAnchor(HubEntityRef("timer", "session", sessionId.toString()), TIMER_ACTIVITY_TYPE, companions)
    }

    suspend fun linked(ref: HubEntityRef): List<HubEntitySummary> =
        requireNotNull(repository).viewsFor(ref).flatMap { it.members }.filter { it.ref != ref }.distinctBy { it.ref }

    suspend fun timerSelection(sessionId: Long): Pair<Set<String>, String?> {
        val linked = linked(HubEntityRef("timer", "session", sessionId.toString()))
        return linked.filter { it.ref.moduleId == "people" && it.ref.entityKind == "person" }.map { it.ref.canonicalId }.toSet() to
            linked.firstOrNull { it.ref.moduleId == "places" && it.ref.entityKind == "place" }?.ref?.canonicalId
    }

    suspend fun temporalFactsForPlace(placeId: String): List<HubTemporalFact> {
        val views = requireNotNull(repository).viewsFor(HubEntityRef("places", "place", placeId))
        return views.mapNotNull { view ->
            val timer = view.members.singleOrNull { it.ref.moduleId == "timer" && it.ref.entityKind == "session" } ?: return@mapNotNull null
            val start = timer.attributes["start_ms"]?.toLongOrNull() ?: return@mapNotNull null
            HubTemporalFact(
                view.context.id,
                timer.ref.canonicalId,
                placeId,
                start,
                timer.attributes["end_ms"]?.toLongOrNull(),
                view.members.filter { it.ref.moduleId == "people" && it.ref.entityKind == "person" },
            )
        }
    }

    suspend fun temporalFacts(): List<HubTemporalFact> = requireNotNull(repository).viewsByType(TIMER_ACTIVITY_TYPE).mapNotNull { view ->
        val timer = view.members.singleOrNull { it.ref.moduleId == "timer" && it.ref.entityKind == "session" } ?: return@mapNotNull null
        val place = view.members.singleOrNull { it.ref.moduleId == "places" && it.ref.entityKind == "place" } ?: return@mapNotNull null
        val start = timer.attributes["start_ms"]?.toLongOrNull() ?: return@mapNotNull null
        HubTemporalFact(view.context.id, timer.ref.canonicalId, place.ref.canonicalId, start, timer.attributes["end_ms"]?.toLongOrNull(), view.members.filter { it.ref.moduleId == "people" })
    }

    fun contextChanges(): Flow<List<String>> = requireNotNull(repository).changes()

    suspend fun canonicalDeleted(ref: HubEntityRef) {
        requireNotNull(repository).binding(ref)?.let { requireNotNull(repository).canonicalDeleted(it.id) }
    }

    suspend fun canonicalDeletedIfInitialized(ref: HubEntityRef) {
        repository?.binding(ref)?.let { repository?.canonicalDeleted(it.id) }
    }

    suspend fun canonicalLifecycleChangedIfInitialized(ref: HubEntityRef) {
        repository?.binding(ref)?.let { repository?.refreshLifecycle(it.id) }
    }
}
