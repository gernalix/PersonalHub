package com.gernalix.personalhub.core.hubcontext

import com.gernalix.personalhub.contracts.database.HubEntityAdapter
import com.gernalix.personalhub.contracts.database.HubEntityLifecycle
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.contracts.database.HubEntitySummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Canonical cross-module search/filter specification.
 *
 * The spec is intentionally persistence-friendly so module UIs can store it as a saved query
 * without inventing a second search model. Persisting named searches remains the caller's concern.
 */
data class HubSearchQuery(
    val text: String = "",
    val modules: Set<String> = emptySet(),
    val entityKinds: Set<String> = emptySet(),
    val capabilities: Set<String> = emptySet(),
    val includeArchived: Boolean = false,
    val perAdapterLimit: Int = 25,
    val limit: Int = 100,
) {
    init {
        require(perAdapterLimit in 1..100)
        require(limit in 1..500)
    }

    fun normalized(): HubSearchQuery = copy(
        text = text.trim(),
        modules = modules.map(String::trim).filter(String::isNotEmpty).toSortedSet(),
        entityKinds = entityKinds.map(String::trim).filter(String::isNotEmpty).toSortedSet(),
        capabilities = capabilities.map(String::trim).filter(String::isNotEmpty).toSortedSet(),
    )
}

data class HubSearchResult(
    val summary: HubEntitySummary,
    val rank: Int,
)

data class HubSearchPage(
    val query: HubSearchQuery,
    val results: List<HubSearchResult>,
)

class HubSearchEngine(
    private val adapters: () -> Collection<HubEntityAdapter>,
) {
    suspend fun search(rawQuery: HubSearchQuery): HubSearchPage {
        val query = rawQuery.normalized()
        val eligible = adapters().filter { adapter ->
            (query.modules.isEmpty() || adapter.moduleId in query.modules) &&
                (query.entityKinds.isEmpty() || adapter.entityKind in query.entityKinds) &&
                (query.capabilities.isEmpty() || query.capabilities.all(adapter.capabilities::contains))
        }

        val candidates = coroutineScope {
            eligible.map { adapter ->
                async(Dispatchers.IO) {
                    adapter.search(query.text, query.perAdapterLimit)
                }
            }.awaitAll().flatten()
        }

        val distinct = candidates
            .asSequence()
            .filter { query.includeArchived || it.lifecycle == HubEntityLifecycle.ACTIVE }
            .distinctBy(HubEntitySummary::ref)
            .map { summary -> HubSearchResult(summary, rank(query.text, summary)) }
            .sortedWith(
                compareByDescending<HubSearchResult> { it.rank }
                    .thenBy { it.summary.label.lowercase() }
                    .thenBy { stableKey(it.summary.ref) },
            )
            .take(query.limit)
            .toList()

        return HubSearchPage(query, distinct)
    }

    private fun rank(text: String, summary: HubEntitySummary): Int {
        if (text.isBlank()) return 0
        val needle = text.lowercase()
        val label = summary.label.lowercase()
        val description = summary.description.orEmpty().lowercase()
        return when {
            label == needle -> 500
            label.startsWith(needle) -> 400
            label.contains(needle) -> 300
            description.startsWith(needle) -> 200
            description.contains(needle) -> 100
            else -> 0
        }
    }

    private fun stableKey(ref: HubEntityRef): String =
        "${ref.moduleId}/${ref.entityKind}/${ref.canonicalId}"
}
