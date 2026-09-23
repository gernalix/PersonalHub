package com.gernalix.personalhub.core.hubcontext

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.contracts.database.HubTagNamespaces
import com.gernalix.personalhub.contracts.database.HubTagKinds
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedTagEngineTest {
    private lateinit var context: Context
    private lateinit var database: PersonalHubDatabase
    private lateinit var engine: SharedTagEngine

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = PersonalHubDatabase.openTemporary(context, "shared-tag-engine-test.db")
        engine = SharedTagEngine(database)
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase("shared-tag-engine-test.db")
    }

    @Test fun namespaceIsolationGlobalCompatibilityAndBooleanFilters() = runBlocking {
        val people = requireNotNull(engine.create(HubTagNamespaces.PEOPLE, "Holiday").tag)
        val places = requireNotNull(engine.create(HubTagNamespaces.PLACES, "Holiday").tag)
        val global = requireNotNull(engine.create(HubTagNamespaces.GLOBAL, "Shared").tag)
        val person = HubEntityRef("people", "person", "p1")
        val place = HubEntityRef("places", "place", "l1")

        engine.assign(person, listOf(people.id, global.id))
        engine.assign(place, listOf(places.id, global.id))
        assertEquals(setOf(people.id, global.id), engine.tags(person).map { it.id }.toSet())
        assertEquals(setOf(places.id, global.id), engine.tags(place).map { it.id }.toSet())
        assertFails { engine.assign(person, listOf(places.id)) }
        assertTrue(engine.matches(person, HubTagFilter(include = setOf(people.id, global.id))))
        assertTrue(engine.matches(person, HubTagFilter(include = setOf(places.id, people.id), mode = HubTagMatchMode.OR)))
        assertFalse(engine.matches(person, HubTagFilter(exclude = setOf(global.id))))
        assertFalse(engine.matches(person, HubTagFilter(noTags = true)))
        assertTrue(engine.matches(HubEntityRef("people", "person", "untagged"), HubTagFilter(noTags = true)))
    }

    @Test fun timerNamespacesRemainIndependentForRenameArchiveDeleteAndMerge() = runBlocking {
        val now = engine.createStable("timer.now:7", HubTagNamespaces.TIMER_NOW, "Focus")
        val events = engine.createStable("timer.events:7", HubTagNamespaces.TIMER_EVENTS, "Focus")
        val since = engine.createStable("timer.since_when:7", HubTagNamespaces.TIMER_SINCE_WHEN, "Focus")
        engine.assign(HubEntityRef("timer", "session", "1"), listOf(now.id))
        engine.assign(HubEntityRef("timer", "quick_event_entry", "2"), listOf(events.id))
        engine.assign(HubEntityRef("timer", "life_period", "3"), listOf(since.id))

        engine.rename(now.id, "Deep focus")
        engine.archive(events.id, true)
        assertEquals("Deep focus", database.hubTagDao().tag(now.id)?.name)
        assertEquals("Focus", database.hubTagDao().tag(events.id)?.name)
        assertEquals("Focus", database.hubTagDao().tag(since.id)?.name)
        assertTrue(database.hubTagDao().tag(events.id)?.archived == true)
        assertFalse(database.hubTagDao().tag(since.id)?.archived == true)
        assertFails { engine.merge(now.id, events.id) }
        assertFalse(engine.deleteUnused(now.id))
        engine.remove(HubEntityRef("timer", "life_period", "3"), listOf(since.id))
        assertTrue(engine.deleteUnused(since.id))
        assertNull(database.hubTagDao().tag(since.id))
        assertNotNull(database.hubTagDao().tag(events.id))
    }

    @Test fun normalizationAliasesDuplicatePreventionAndSameNamespaceMerge() = runBlocking {
        val canonical = requireNotNull(engine.create(HubTagNamespaces.SOLDI, "Copenhagen").tag)
        assertEquals(canonical.id, engine.create(HubTagNamespaces.SOLDI, "  COPENHAGEN ").exactDuplicate?.id)
        engine.addAlias(canonical.id, "CPH")
        assertEquals(canonical.id, engine.search(HubTagNamespaces.SOLDI, "cph").single().id)
        val duplicate = requireNotNull(engine.create(HubTagNamespaces.SOLDI, "København", acceptNearDuplicate = true).tag)
        val transaction = HubEntityRef("soldi", "transaction", "tx-1")
        engine.assign(transaction, listOf(duplicate.id))
        engine.merge(duplicate.id, canonical.id)
        assertEquals(listOf(canonical.id), engine.tags(transaction).map { it.id })
        assertNull(database.hubTagDao().tag(duplicate.id))
    }

    @Test fun soldiCategoryIsTypedAndLimitedToOnePrimaryAssignment() = runBlocking {
        val food = requireNotNull(engine.create(HubTagNamespaces.SOLDI_CATEGORY, "Food", kind = HubTagKinds.CATEGORY).tag)
        val travel = requireNotNull(engine.create(HubTagNamespaces.SOLDI_CATEGORY, "Travel", kind = HubTagKinds.CATEGORY).tag)
        val transaction = HubEntityRef("soldi", "transaction", "tx-category")
        engine.assign(transaction, listOf(food.id))
        assertFails { engine.assign(transaction, listOf(travel.id)) }
        engine.replace(transaction, HubTagNamespaces.SOLDI_CATEGORY, listOf(travel.id))
        assertEquals(listOf(travel.id), engine.tags(transaction).map { it.id })
        assertEquals(HubTagKinds.CATEGORY, database.hubTagDao().tag(travel.id)?.kind)
    }

    private suspend fun assertFails(block: suspend () -> Unit) {
        var failed = false
        try { block() } catch (_: Exception) { failed = true }
        assertTrue("Expected failure", failed)
    }
}
