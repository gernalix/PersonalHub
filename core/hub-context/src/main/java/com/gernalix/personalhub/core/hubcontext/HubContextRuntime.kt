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
    const val FINANCE_TRANSACTION_CONTEXT_TYPE = "finance_transaction_context"
    const val FINANCE_RECURRENCE_CONTEXT_TYPE = "finance_recurrence_context"

    @Volatile private var appContext: Context? = null
    @Volatile private var registry: HubAdapterRegistry? = null
    @Volatile private var repository: HubContextRepository? = null
    @Volatile private var tagEngine: SharedTagEngine? = null

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
            registry = HubAdapterRegistry(adapters + HubTagAdapter(context.applicationContext))
            repository = null
            tagEngine = null
        }
    }

    fun adapter(moduleId: String, entityKind: String): HubEntityAdapter =
        requireRegistry().adapter(HubEntityRef(moduleId, entityKind, ""))

    fun adapters(): List<HubEntityAdapter> = requireRegistry().all().toList()

    fun temporalProviders(): List<HubTemporalProvider> = adapters().filterIsInstance<HubTemporalProvider>()

    private fun searchEngine() = HubSearchEngine { requireRegistry().all() }

    suspend fun search(query: HubSearchQuery): HubSearchPage =
        searchEngine().search(query)

    fun tags(): SharedTagEngine {
        tagEngine?.let { return it }
        return synchronized(this) {
            tagEngine ?: SharedTagEngine(
                PersonalHubDatabase.get(requireNotNull(appContext) { "Hub Context runtime is not initialized" }),
            ).also { tagEngine = it }
        }
    }

    suspend fun searchFacets(query: String, namespace: String, limit: Int = 50): List<HubFacetSuggestion> {
        val tagOnly = query.startsWith("#")
        val entityResults = if (tagOnly) emptyList() else search(HubSearchQuery(text = query, modules = adapters().mapTo(mutableSetOf()) { it.moduleId } - "tags", limit = limit)).results.map {
            HubFacetSuggestion(it.summary, it.summary.ref.entityKind.replace('_', ' '), it.summary.attributes["icon"] ?: "•", false)
        }
        val tagResults = tags().search(namespace, query, limit).map {
            HubFacetSuggestion(
                HubEntitySummary(
                    HubEntityRef("tags", "tag", it.id),
                    it.name,
                    it.namespace,
                    attributes = mapOf("namespace" to it.namespace, "icon" to (it.icon ?: "🏷"), "facet_type" to "tag"),
                ),
                "tag", it.icon ?: "🏷", true,
            )
        }
        return (entityResults + tagResults).take(limit)
    }

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
    suspend fun titledContexts() = requireRepository().titledViews()
    suspend fun contexts(ref: HubEntityRef) = requireRepository().viewsFor(ref)
    suspend fun contextTypes() = requireRepository().types()
    suspend fun contextType(typeId: String) = requireRepository().type(typeId)
    suspend fun saveContextType(type: HubContextType, fields: List<HubContextTypeField>) = requireRepository().saveType(type, fields)
    suspend fun deleteContextType(typeId: String) = requireRepository().deleteType(typeId)
    suspend fun saveCombinationAsType(contextId: String, name: String) = requireRepository().saveCombinationAsType(contextId, name)
    suspend fun explore(scope: List<HubEntityRef>, limit: Int = 100, offset: Int = 0) = requireRepository().explore(scope, limit, offset)

    suspend fun ensureTimerActivityType() {
        val now = System.currentTimeMillis()
        requireRepository().saveSystemType(
            HubContextType(TIMER_ACTIVITY_TYPE, "Timer activity", now, now, locked = true),
            listOf(
                HubContextTypeField(TIMER_ACTIVITY_TYPE, "session", 0, "Session", "anchor", "timer", "session", minCardinality = 1, maxCardinality = 1),
                HubContextTypeField(TIMER_ACTIVITY_TYPE, "people", 1, "People", "participant", "people", "person", minCardinality = 0, maxCardinality = null),
                HubContextTypeField(TIMER_ACTIVITY_TYPE, "place", 2, "Place", "participant", "places", "place", minCardinality = 0, maxCardinality = 1),
                HubContextTypeField(TIMER_ACTIVITY_TYPE, "substances", 3, "Substances", "substance_context", "substances", "substance", minCardinality = 0, maxCardinality = null),
            ),
        )
    }

    suspend fun saveTimerLinks(
        sessionId: Long,
        peopleIds: Set<String>,
        placeId: String?,
        contextualRefs: Collection<HubEntityRef> = emptyList(),
    ) {
        ensureTimerActivityType()
        val companions = buildList {
            peopleIds.forEach { add(HubEntityRef("people", "person", it) to "participant") }
            placeId?.let { add(HubEntityRef("places", "place", it) to "participant") }
            contextualRefs.filter { it.moduleId in setOf("people", "places", "substances") }.forEach { ref ->
                add(ref to if (ref.moduleId == "substances") "substance_context" else "participant")
            }
        }.distinct()
        requireRepository().replaceContextForAnchorWithRoles(
            HubEntityRef("timer", "session", sessionId.toString()), TIMER_ACTIVITY_TYPE, companions,
        )
    }

    suspend fun saveFinanceTransactionLinksIfInitialized(
        transactionUuid: String,
        personPublicId: String?,
        placeId: String?,
        contextualRefs: Collection<HubEntityRef> = emptyList(),
    ) {
        val repo = repositoryIfRuntimeInitialized() ?: return
        val now = System.currentTimeMillis()
        repo.saveSystemType(
            HubContextType(FINANCE_TRANSACTION_CONTEXT_TYPE, "Finance transaction context", now, now, locked = true),
            listOf(
                HubContextTypeField(FINANCE_TRANSACTION_CONTEXT_TYPE, "transaction", 0, "Transaction", "anchor", "soldi", "transaction", minCardinality = 1, maxCardinality = 1),
                HubContextTypeField(FINANCE_TRANSACTION_CONTEXT_TYPE, "person", 1, "Person", "participant", "people", "person", minCardinality = 0, maxCardinality = 1),
                HubContextTypeField(FINANCE_TRANSACTION_CONTEXT_TYPE, "place", 2, "Place", "merchant_place", "places", "place", minCardinality = 0, maxCardinality = 1),
            ),
        )
        val companions = buildList {
            personPublicId?.let { add(HubEntityRef("people", "person", it) to "participant") }
            placeId?.let { add(HubEntityRef("places", "place", it) to "merchant_place") }
            contextualRefs.filterNot { it.moduleId == "tags" }.forEach { ref ->
                add(ref to when (ref.moduleId) {
                    "people" -> "participant"
                    "places" -> "merchant_place"
                    "substances" -> "substance_context"
                    else -> "context"
                })
            }
        }
        repo.replaceContextForAnchorWithRoles(
            HubEntityRef("soldi", "transaction", transactionUuid),
            FINANCE_TRANSACTION_CONTEXT_TYPE,
            companions.distinct(),
        )
    }

    suspend fun saveFinanceRecurrenceLinksIfInitialized(
        recurrenceId: String,
        personPublicId: String?,
        placeId: String?,
        contextualRefs: Collection<HubEntityRef> = emptyList(),
    ) {
        val repo = repositoryIfRuntimeInitialized() ?: return
        val now = System.currentTimeMillis()
        repo.saveSystemType(
            HubContextType(FINANCE_RECURRENCE_CONTEXT_TYPE, "Finance recurrence context", now, now, locked = true),
            listOf(
                HubContextTypeField(FINANCE_RECURRENCE_CONTEXT_TYPE, "recurrence", 0, "Recurrence", "anchor", "soldi", "recurrence", minCardinality = 1, maxCardinality = 1),
                HubContextTypeField(FINANCE_RECURRENCE_CONTEXT_TYPE, "person", 1, "Person", "participant", "people", "person", minCardinality = 0, maxCardinality = 1),
                HubContextTypeField(FINANCE_RECURRENCE_CONTEXT_TYPE, "place", 2, "Place", "merchant_place", "places", "place", minCardinality = 0, maxCardinality = 1),
            ),
        )
        val companions = buildList {
            personPublicId?.let { add(HubEntityRef("people", "person", it) to "participant") }
            placeId?.let { add(HubEntityRef("places", "place", it) to "merchant_place") }
            contextualRefs.filterNot { it.moduleId == "tags" }.forEach { ref ->
                add(ref to when (ref.moduleId) {
                    "people" -> "participant"
                    "places" -> "merchant_place"
                    "substances" -> "substance_context"
                    else -> "context"
                })
            }
        }.distinct()
        repo.replaceContextForAnchorWithRoles(HubEntityRef("soldi", "recurrence", recurrenceId), FINANCE_RECURRENCE_CONTEXT_TYPE, companions)
    }

    suspend fun linked(ref: HubEntityRef): List<HubEntitySummary> {
        val contextLinked = requireRepository().viewsFor(ref)
            .flatMap { it.members }
            .filter { it.ref != ref }
        val tagLinkedRefs = if (ref.moduleId == "tags" && ref.entityKind == "tag") {
            tags().backlinks(ref.canonicalId)
        } else {
            tags().tags(ref).map { HubEntityRef("tags", "tag", it.id) }
        }
        val resolved = summaries(tagLinkedRefs).values
        return (contextLinked + resolved).distinctBy { it.ref }
    }

    suspend fun timerSelection(sessionId: Long): Pair<Set<String>, String?> {
        val linked = linked(HubEntityRef("timer", "session", sessionId.toString()))
        return linked.filter { it.ref.moduleId == "people" && it.ref.entityKind == "person" }.map { it.ref.canonicalId }.toSet() to
            linked.firstOrNull { it.ref.moduleId == "places" && it.ref.entityKind == "place" }?.ref?.canonicalId
    }

    suspend fun timerFacets(sessionId: Long): List<HubEntitySummary> =
        linked(HubEntityRef("timer", "session", sessionId.toString()))
            .filter { it.ref.moduleId in setOf("people", "places", "substances") }

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
        val repo = repositoryIfRuntimeInitialized() ?: return
        repo.binding(ref)?.let { repo.canonicalDeleted(it.id) }
    }

    suspend fun canonicalLifecycleChangedIfInitialized(ref: HubEntityRef) {
        val repo = repositoryIfRuntimeInitialized() ?: return
        repo.binding(ref)?.let { repo.refreshLifecycle(it.id) }
    }

    private fun requireRegistry(): HubAdapterRegistry =
        requireNotNull(registry) { "Hub Context runtime is not initialized" }

    private fun repositoryIfRuntimeInitialized(): HubContextRepository? {
        if (appContext == null || registry == null) return null
        return requireRepository()
    }

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
