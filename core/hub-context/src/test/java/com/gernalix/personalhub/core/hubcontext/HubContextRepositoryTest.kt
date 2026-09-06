package com.gernalix.personalhub.core.hubcontext

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gernalix.personalhub.contracts.database.*
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HubContextRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: PersonalHubDatabase
    private lateinit var adapter: FakeAdapter
    private lateinit var repository: HubContextRepository

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = PersonalHubDatabase.openTemporary(context, "hub-context-test.db")
        adapter = FakeAdapter()
        repository = HubContextRepository(database, HubAdapterRegistry(listOf(adapter)))
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase("hub-context-test.db")
    }

    @Test fun nAryIntersectionReverseAndLifecycleContracts() = runBlocking {
        val a = repository.bind(ref("a"))
        val b = repository.bind(ref("b"))
        val c = repository.bind(ref("c"))
        val d = repository.bind(ref("d"))
        val unused = repository.bind(ref("unused"))

        val first = repository.createContext(listOf(draft(a), draft(b), draft(c)))
        repository.createContext(listOf(draft(a), draft(b), draft(d)))
        assertEquals(2, repository.contextsContainingAll(listOf(a.id, b.id)).size)
        assertEquals(setOf(c.id, d.id), repository.relatedToAll(listOf(a.id, b.id)).map { it.id }.toSet())
        assertEquals(1, repository.contextsFor(c.id).size)
        assertEquals(2, repository.facetsRelatedToAll(listOf(a.id, b.id)).single().count)

        assertFails { repository.createContext(listOf(draft(a), draft(a))) }
        repository.createContext(listOf(HubContextMemberDraft(a.id, "subject"), HubContextMemberDraft(a.id, "witness")))
        repository.removeMember(first, draft(c))
        assertFails { repository.removeMember(first, draft(b)) }

        adapter.states["a"] = HubEntityLifecycle.ARCHIVED
        repository.refreshLifecycle(a.id)
        assertEquals(HubEntityLifecycle.ARCHIVED, database.hubContextDao().binding(a.id)!!.lifecycle)
        repository.canonicalDeleted(a.id)
        assertEquals(HubEntityLifecycle.DELETED, database.hubContextDao().binding(a.id)!!.lifecycle)
        repository.canonicalDeleted(unused.id)
        assertNull(database.hubContextDao().binding(unused.id))
    }

    @Test fun typeFieldsAreDataAndCardinalityIsValidated() = runBlocking {
        val now = Instant.now().toString()
        val type = HubContextType("meeting", "Meeting", now, now)
        repository.saveType(type, listOf(
            HubContextTypeField("meeting", "people", 0, "People", acceptedModuleId = "fake", acceptedEntityKind = "item", minCardinality = 1, maxCardinality = null),
            HubContextTypeField("meeting", "place", 1, "Place", acceptedCapability = "location", minCardinality = 0, maxCardinality = 1),
        ))
        val count = database.openHelper.readableDatabase.query("SELECT count(*) FROM hub_context_type_fields WHERE context_type_id='meeting'").use { it.moveToFirst(); it.getInt(0) }
        assertEquals(2, count)
    }

    @Test fun typedContextsCanBeEditedWithoutPairwiseFactsAndTemplatesCopyOnlyShape() = runBlocking {
        val now = Instant.now().toString()
        val type = HubContextType("outing", "Uscita", now, now)
        val fields = listOf(
            HubContextTypeField("outing", "people", 0, "Con chi", "people", "fake", "item", minCardinality = 0, maxCardinality = null),
            HubContextTypeField("outing", "place", 1, "Dove", "place", "fake", "item", minCardinality = 1, maxCardinality = 1),
        )
        repository.saveType(type, fields)
        val giovanni = repository.bind(ref("giovanni"))
        val maria = repository.bind(ref("maria"))
        val piazza = repository.bind(ref("piazza"))
        val parco = repository.bind(ref("parco"))
        val id = repository.createContext(listOf(
            HubContextMemberDraft(giovanni.id, "people"),
            HubContextMemberDraft(piazza.id, "place"),
        ), "outing")

        repository.updateContext(id, listOf(
            HubContextMemberDraft(giovanni.id, "people"),
            HubContextMemberDraft(maria.id, "people"),
            HubContextMemberDraft(parco.id, "place"),
        ), "outing")
        assertEquals(setOf("giovanni", "maria", "parco"), repository.context(id)!!.members.map { it.ref.canonicalId }.toSet())
        assertFails { repository.updateContext(id, listOf(HubContextMemberDraft(giovanni.id, "people"), HubContextMemberDraft(piazza.id, "place"), HubContextMemberDraft(parco.id, "place")), "outing") }

        val savedTypeId = repository.saveCombinationAsType(id, "Uscita riusabile")
        val saved = requireNotNull(repository.type(savedTypeId))
        assertEquals(2, saved.second.size)
        assertFalse(saved.second.any { field -> field.label in setOf("giovanni", "maria", "parco") })
        repository.saveType(type.copy(name = "Uscita aggiornata", updatedAt = Instant.now().toString()), fields.reversed().mapIndexed { index, field -> field.copy(position = index) })
        assertEquals(setOf("giovanni", "maria", "parco"), repository.context(id)!!.members.map { it.ref.canonicalId }.toSet())
    }

    @Test fun recursiveScopeCountsAreDistinctSymmetricAndExcludeFalseAssociations() = runBlocking {
        val people = FakeKindAdapter("people", "person")
        val places = FakeKindAdapter("places", "place")
        val sessions = FakeKindAdapter("timer", "session")
        val graph = HubContextRepository(database, HubAdapterRegistry(listOf(people, places, sessions)))
        val giovanni = graph.bind(HubEntityRef("people", "person", "giovanni"))
        val piazza = graph.bind(HubEntityRef("places", "place", "piazza-savona"))
        val oscar = graph.bind(HubEntityRef("places", "place", "oscar-cafe"))
        val feb = graph.bind(HubEntityRef("timer", "session", "12-02"))
        val may = graph.bind(HubEntityRef("timer", "session", "17-05"))
        val other = graph.bind(HubEntityRef("timer", "session", "session-x"))
        graph.createContext(listOf(draft(giovanni), draft(piazza), draft(feb)))
        graph.createContext(listOf(draft(giovanni), draft(piazza), draft(may)))
        graph.createContext(listOf(draft(giovanni), draft(oscar), draft(other)))

        val fromPerson = graph.explore(listOf(HubEntityRef("people", "person", "giovanni")))
        assertEquals(mapOf("piazza-savona" to 2, "oscar-cafe" to 1), fromPerson.facets.single { it.entityKind == "place" }.candidates.associate { it.summary.ref.canonicalId to it.compatibleContextCount })
        val narrowed = graph.explore(listOf(HubEntityRef("people", "person", "giovanni"), HubEntityRef("places", "place", "piazza-savona")))
        assertEquals(setOf("12-02", "17-05"), narrowed.facets.single { it.entityKind == "session" }.candidates.map { it.summary.ref.canonicalId }.toSet())
        assertFalse(narrowed.facets.flatMap { it.candidates }.any { it.summary.ref.canonicalId == "session-x" })
        val reverse = graph.explore(listOf(HubEntityRef("places", "place", "piazza-savona"), HubEntityRef("people", "person", "giovanni")))
        assertEquals(narrowed.facets, reverse.facets)
    }

    @Test fun indexedExplorerAvoidsGrossScalingRegression() = runBlocking {
        val people = FakeKindAdapter("people", "person")
        val places = FakeKindAdapter("places", "place")
        val graph = HubContextRepository(database, HubAdapterRegistry(listOf(people, places)))
        val anchor = graph.bind(HubEntityRef("people", "person", "anchor"))
        repeat(300) { index ->
            val place = graph.bind(HubEntityRef("places", "place", "place-$index"))
            graph.createContext(listOf(draft(anchor), draft(place)))
        }
        val started = System.nanoTime()
        val result = graph.explore(listOf(HubEntityRef("people", "person", "anchor")), limit = 200)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(200, result.facets.single().candidates.size)
        assertTrue("Indexed 300-context query took ${elapsedMs}ms", elapsedMs < 2_000)
    }

    @Test fun multipleContextViewsResolveBindingsInOneBatch() = runBlocking {
        val batches = mutableListOf<Int>()
        val graph = HubContextRepository(database, HubAdapterRegistry(listOf(adapter))) { batches += it }
        val anchor = graph.bind(ref("anchor"))
        val b = graph.bind(ref("b"))
        val c = graph.bind(ref("c"))
        graph.createContext(listOf(draft(anchor), draft(b)))
        graph.createContext(listOf(draft(anchor), draft(c)))
        batches.clear()

        val views = graph.viewsFor(ref("anchor"))

        assertEquals(2, views.size)
        assertEquals(listOf(3), batches)
    }

    @Test fun systemTypesAreLockedWhileUserTypesRemainEditableAndDeletable() = runBlocking {
        val now = Instant.now().toString()
        val system = HubContextType("system", "System", now, now, locked = true)
        val fields = listOf(HubContextTypeField("system", "item", 0, "Item", acceptedModuleId = "fake", acceptedEntityKind = "item"))
        repository.saveSystemType(system, fields)
        assertTrue(repository.type("system")!!.first.locked)
        assertFails { repository.saveType(system.copy(name = "Overwritten", locked = false), fields) }
        assertFails { repository.deleteType("system") }

        val user = HubContextType("user", "User", now, now)
        val userFields = fields.map { it.copy(contextTypeId = "user") }
        repository.saveType(user, userFields)
        repository.saveType(user.copy(name = "User updated"), userFields)
        assertEquals("User updated", repository.type("user")!!.first.name)
        repository.deleteType("user")
        assertNull(repository.type("user"))
    }

    private fun ref(id: String) = HubEntityRef("fake", "item", id)
    private fun draft(binding: HubEntityBinding) = HubContextMemberDraft(binding.id)
    private suspend fun assertFails(block: suspend () -> Unit) {
        try { block(); fail("Expected failure") } catch (_: IllegalArgumentException) { }
    }

    private class FakeAdapter : HubEntityAdapter {
        override val moduleId = "fake"
        override val entityKind = "item"
        override val capabilities = setOf("location")
        val states = mutableMapOf<String, String>()
        override suspend fun exists(canonicalId: String) = canonicalId.isNotBlank()
        override suspend fun lifecycle(canonicalId: String) = states[canonicalId] ?: HubEntityLifecycle.ACTIVE
        override suspend fun summaries(canonicalIds: Set<String>) = canonicalIds.associateWith { id -> HubEntitySummary(HubEntityRef(moduleId, entityKind, id), id) }
        override suspend fun search(query: String, limit: Int) = summaries(setOf(query)).values.take(limit)
        override suspend fun openTarget(canonicalId: String) = HubOpenTarget("personalhub://fake/$canonicalId")
    }

    private class FakeKindAdapter(override val moduleId: String, override val entityKind: String) : HubEntityAdapter {
        override val capabilities = setOf(entityKind)
        override suspend fun exists(canonicalId: String) = canonicalId.isNotBlank()
        override suspend fun lifecycle(canonicalId: String) = HubEntityLifecycle.ACTIVE
        override suspend fun summaries(canonicalIds: Set<String>) = canonicalIds.associateWith { id -> HubEntitySummary(HubEntityRef(moduleId, entityKind, id), id) }
        override suspend fun search(query: String, limit: Int) = emptyList<HubEntitySummary>()
        override suspend fun openTarget(canonicalId: String) = null
    }
}
