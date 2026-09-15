package com.gernalix.personalhub.core.hubcontext

import android.content.Context
import com.gernalix.personalhub.contracts.database.*
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    @Volatile private var appContext: Context? = null
    @Volatile private var registry: HubAdapterRegistry? = null
    @Volatile private var repository: HubContextRepository? = null

    /**
     * Installs the lightweight Hub routing configuration.
     *
     * Repository/Room materialization is intentionally deferred until a Hub operation actually
     * needs persistence. The launcher Home does not need Hub persistence, so opening the shared
     * database here only adds work to the process-start critical path.
     */
    fun initialize(context: Context, adapters: Collection<HubEntityAdapter>) {
        synchronized(this) {
            appContext = context.applicationContext
            registry = HubAdapterRegistry(adapters)
            repository = null
        }
    }

    fun adapter(moduleId: String, entityKind: String): HubEntityAdapter =
        requireRegistry().adapter(HubEntityRef(moduleId, entityKind, ""))

    fun adapters(): List<HubEntityAdapter> = requireRegistry().all().toList()

    fun temporalProviders(): List<HubTemporalProvider> = adapters().filterIsInstance<HubTemporalProvider>()

    suspend fun summaries(refs: Collection<HubEntityRef>): Map<HubEntityRef, HubEntitySummary> {
        val distinctRefs = refs.distinct()
        if (distinctRefs.isEmpty()) return emptyMap()
        return coroutineScope {
            distinctRefs.groupBy { it.moduleId to it.entityKind }.map { (kind, groupedRefs) ->
                async(Dispatchers.IO) {
                    val resolved = adapter(kind.first, kind.second).summaries(groupedRefs.map { it.canonicalId }.toSet())
                    groupedRefs.mapNotNull { ref -> resolved[ref.canonicalId]?.let { ref to it } }
                }
            }.awaitAll().flatten().toMap()
        }
    }

    suspend fun temporal(query: HubTemporalQuery, modules: Set<String> = emptySet()): List<HubTemporalRecord> {
        val slices = coroutineScope {
            temporalProviders()
                .filter { modules.isEmpty() || it.moduleId in modules }
                .map { provider -> async(Dispatchers.IO) { provider.queryTemporal(query).records } }
                .awaitAll()
        }
        return mergeTemporalSlices(slices, query.fromMs, query.toMs, modules)
    }

    suspend fun createContext(refs: List<Pair<HubEntityRef, String>>, typeId: String? = null, title: String? = null): String {
        val repo = requireRepository()
        val drafts = refs.distinct().map { (ref, role) -> HubContextMemberDraft(repo.bind(ref).id, role) }
        return repo.createContext(drafts, typeId, title)
    }

    suspend fun updateContext(contextId: String, refs: List<Pair<HubEntityRef, String>>, typeId: String? = null, title: String? = null) {
        val repo = requireRepository()
        val drafts = refs.distinct().map { (ref, role) -> HubContextMemberDraft(repo.bind(ref).id, role) }
        repo.updateContext(contextId, drafts, typeId, title)
    }

    suspend fun context(contextId: String) = requireRepository().context(contextId)
    suspend fun contexts(ref: HubEntityRef) = requireRepository().viewsFor(ref)
    suspend fun contextTypes() = requireRepository().types()
    suspend fun contextType(typeId: String) = requireRepository().type(typeId)
    suspend fun saveContextType(type: HubContextType, fields: List<HubContextTypeField>) = requireRepository().saveType(type, fields)
    suspend fun deleteContextType(typeId: String) = requireRepository().deleteType(typeId)
    suspend fun saveCombinationAsType(contextId: String, name: String) = requireRepository().saveCombinationAsType(contextId, name)
    suspend fun explore(scope: List<HubEntityRef>, limit: Int = 100, offset: Int = 0) = requireRepository().explore(scope, limit, offset)

    suspend fun ensureTimerActivityType() {
        val now = Instant.now().toString()
        requireRepository().saveSystemType(
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
        requireRepository().replaceContextForAnchor(HubEntityRef("timer", "session", sessionId.toString()), TIMER_ACTIVITY_TYPE, companions)
    }

    suspend fun linked(ref: HubEntityRef): List<HubEntitySummary> =
        requireRepository().viewsFor(ref).flatMap { it.members }.filter { it.ref != ref }.distinctBy { it.ref }

    suspend fun timerSelection(sessionId: Long): Pair<Set<String>, String?> {
        val linked = linked(HubEntityRef("timer", "session", sessionId.toString()))
        return linked.filter { it.ref.moduleId == "people" && it.ref.entityKind == "person" }.map { it.ref.canonicalId }.toSet() to
            linked.firstOrNull { it.ref.moduleId == "places" && it.ref.entityKind == "place" }?.ref?.canonicalId
    }

    suspend fun temporalFactsForPlace(placeId: String): List<HubTemporalFact> {
        val views = requireRepository().viewsFor(HubEntityRef("places", "place", placeId))
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

    suspend fun temporalFacts(): List<HubTemporalFact> = requireRepository().viewsByType(TIMER_ACTIVITY_TYPE).mapNotNull { view ->
        val timer = view.members.singleOrNull { it.ref.moduleId == "timer" && it.ref.entityKind == "session" } ?: return@mapNotNull null
        val place = view.members.singleOrNull { it.ref.moduleId == "places" && it.ref.entityKind == "place" } ?: return@mapNotNull null
        val start = timer.attributes["start_ms"]?.toLongOrNull() ?: return@mapNotNull null
        HubTemporalFact(view.context.id, timer.ref.canonicalId, place.ref.canonicalId, start, timer.attributes["end_ms"]?.toLongOrNull(), view.members.filter { it.ref.moduleId == "people" })
    }

    fun contextChanges(): Flow<List<String>> = requireRepository().changes()

    suspend fun canonicalDeleted(ref: HubEntityRef) {
        val repo = requireRepository()
        repo.binding(ref)?.let { repo.canonicalDeleted(it.id) }
    }

    suspend fun canonicalDeletedIfInitialized(ref: HubEntityRef) {
        val repo = repository ?: return
        repo.binding(ref)?.let { repo.canonicalDeleted(it.id) }
    }

    suspend fun canonicalLifecycleChangedIfInitialized(ref: HubEntityRef) {
        val repo = repository ?: return
        repo.binding(ref)?.let { repo.refreshLifecycle(it.id) }
    }

    private fun requireRegistry(): HubAdapterRegistry =
        requireNotNull(registry) { "Hub Context runtime is not initialized" }

    private fun requireRepository(): HubContextRepository {
        repository?.let { return it }
        return synchronized(this) {
            repository ?: run {
                val context = requireNotNull(appContext) { "Hub Context runtime is not initialized" }
                val resolvedRegistry = requireRegistry()
                val appVersion = runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
                }.getOrDefault(0L)
                HubContextRepository(
                    PersonalHubDatabase.get(context),
                    resolvedRegistry,
                    appVersion = appVersion,
                ).also { repository = it }
            }
        }
    }
}
