package com.gernalix.personalhub.core.hubcontext

import com.gernalix.personalhub.contracts.database.HubCreateRequest
import com.gernalix.personalhub.contracts.database.HubEntityAdapter
import com.gernalix.personalhub.contracts.database.HubEntityLifecycle
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.contracts.database.HubEntitySummary
import com.gernalix.personalhub.contracts.database.HubOpenTarget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class HubSearchEngineTest {
    @Test
    fun ranksAndFiltersAcrossAdapters() = runBlocking {
        val people = FakeAdapter(
            moduleId = "people",
            entityKind = "person",
            capabilities = setOf("person"),
            rows = listOf(
                HubEntitySummary(HubEntityRef("people", "person", "1"), "Alice"),
                HubEntitySummary(HubEntityRef("people", "person", "2"), "Malice"),
                HubEntitySummary(HubEntityRef("people", "person", "3"), "Archived Alice", lifecycle = HubEntityLifecycle.ARCHIVED),
            ),
        )
        val places = FakeAdapter(
            moduleId = "places",
            entityKind = "place",
            capabilities = setOf("place", "location"),
            rows = listOf(
                HubEntitySummary(HubEntityRef("places", "place", "a"), "Alice Cafe"),
            ),
        )
        val engine = HubSearchEngine { listOf(people, places) }

        val all = engine.search(HubSearchQuery(text = "alice")).results
        assertEquals(listOf("Alice", "Alice Cafe"), all.map { it.summary.label })

        val onlyPlaces = engine.search(
            HubSearchQuery(text = "alice", modules = setOf("places"))
        ).results
        assertEquals(listOf("Alice Cafe"), onlyPlaces.map { it.summary.label })

        val archived = engine.search(
            HubSearchQuery(text = "alice", modules = setOf("people"), includeArchived = true)
        ).results
        assertEquals(listOf("Alice", "Archived Alice"), archived.map { it.summary.label })
    }

    @Test
    fun capabilityFilterSelectsCompatibleAdapters() = runBlocking {
        val engine = HubSearchEngine {
            listOf(
                FakeAdapter("people", "person", setOf("person"), emptyList()),
                FakeAdapter(
                    "places",
                    "place",
                    setOf("place", "location"),
                    listOf(HubEntitySummary(HubEntityRef("places", "place", "x"), "Copenhagen")),
                ),
            )
        }
        val results = engine.search(HubSearchQuery(text = "cop", capabilities = setOf("location"))).results
        assertEquals(listOf("Copenhagen"), results.map { it.summary.label })
    }

    private class FakeAdapter(
        override val moduleId: String,
        override val entityKind: String,
        override val capabilities: Set<String>,
        private val rows: List<HubEntitySummary>,
    ) : HubEntityAdapter {
        override suspend fun exists(canonicalId: String) = rows.any { it.ref.canonicalId == canonicalId }
        override suspend fun lifecycle(canonicalId: String) =
            rows.firstOrNull { it.ref.canonicalId == canonicalId }?.lifecycle ?: HubEntityLifecycle.DELETED
        override suspend fun summaries(canonicalIds: Set<String>) =
            rows.filter { it.ref.canonicalId in canonicalIds }.associateBy { it.ref.canonicalId }
        override suspend fun search(query: String, limit: Int) =
            rows.filter {
                query.isBlank() ||
                    it.label.contains(query, ignoreCase = true) ||
                    it.description.orEmpty().contains(query, ignoreCase = true)
            }.take(limit)
        override suspend fun openTarget(canonicalId: String): HubOpenTarget? = null
        override suspend fun create(request: HubCreateRequest): HubEntitySummary? = null
    }
}
