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
        val person = canonicalFixture(database, "people", "person", "p1")
        val place = canonicalFixture(database, "places", "place", "l1")

        engine.assign(person, listOf(people.id, global.id))
        engine.assign(place, listOf(places.id, global.id))
        assertEquals(setOf(people.id, global.id), engine.tags(person).map { it.id }.toSet())
        assertEquals(setOf(places.id, global.id), engine.tags(place).map { it.id }.toSet())
        assertFails { engine.assign(person, listOf(places.id)) }
        assertTrue(engine.matches(person, HubTagFilter(include = setOf(people.id, global.id))))
        assertTrue(engine.matches(person, HubTagFilter(include = setOf(places.id, people.id), mode = HubTagMatchMode.OR)))
        assertFalse(engine.matches(person, HubTagFilter(exclude = setOf(global.id))))
        assertFalse(engine.matches(person, HubTagFilter(noTags = true)))
        assertTrue(engine.matches(canonicalFixture(database, "people", "person", "untagged"), HubTagFilter(noTags = true)))
    }

    @Test fun countdownSharesCountersButKeepsTagsAndHistorySeparate() = runBlocking {
        val dao = database.sinceWhenCounterDao()
        val sinceId = dao.insert(com.gernalix.personalhub.contracts.database.SinceWhenCounterEntity(title = "Since", initialTimestamp = 1, createdAt = 1))
        val countdownId = dao.insert(com.gernalix.personalhub.contracts.database.SinceWhenCounterEntity(title = "Countdown", initialTimestamp = 2, createdAt = 1,
            mode = com.gernalix.personalhub.contracts.database.CounterModes.COUNTDOWN))
        val since = requireNotNull(dao.get(sinceId))
        val countdown = requireNotNull(dao.get(countdownId))
        val sinceRef = HubEntityRef("since_when", "counter", since.canonicalId)
        val countdownRef = HubEntityRef("since_when", "counter", countdown.canonicalId)
        val sinceTag = engine.getOrCreate(HubTagNamespaces.SINCE_WHEN, "Focus")
        val countdownTag = engine.getOrCreate(HubTagNamespaces.COUNTDOWN, "Focus")
        val timerTag = engine.getOrCreate(HubTagNamespaces.TIMER_NOW, "Focus")
        engine.assign(sinceRef, listOf(sinceTag.id))
        engine.assign(countdownRef, listOf(countdownTag.id))
        assertFails { engine.assign(countdownRef, listOf(sinceTag.id)) }
        assertFails { engine.assign(sinceRef, listOf(countdownTag.id)) }
        assertFails { engine.assign(countdownRef, listOf(timerTag.id)) }
        val sql = database.openHelper.readableDatabase
        val filter = com.gernalix.personalhub.core.database.capsules.history.HistoryQueryCapsule.Filter(modules = setOf("countdown"))
        val query = com.gernalix.personalhub.core.database.capsules.history.HistoryQueryCapsule
        assertEquals(listOf(countdown.canonicalId), query.records(sql, filter).groups.map { it.groupId })
        assertTrue(query.page(sql, filter).groups.isNotEmpty())
        assertTrue(query.page(sql, filter).groups.all { it.module == "countdown" })
        assertEquals(setOf(countdownTag.id), engine.tags(countdownRef).map { it.id }.toSet())
        dao.delete(countdown)
        assertTrue(query.records(sql, filter).groups.isEmpty())
        assertTrue(query.page(sql, filter).groups.isNotEmpty())
        assertEquals(setOf(sinceTag.id), engine.tags(sinceRef).map { it.id }.toSet())
    }

    @Test fun timerNamespacesRemainIndependentForRenameArchiveDeleteAndMerge() = runBlocking {
        val now = engine.createStable("timer.now:7", HubTagNamespaces.TIMER_NOW, "Focus")
        val events = engine.createStable("timer.events:7", HubTagNamespaces.TIMER_EVENTS, "Focus")
        val since = engine.createStable("timer.since_when:7", HubTagNamespaces.SINCE_WHEN, "Focus")
        engine.assign(canonicalFixture(database, "timer", "session", "1"), listOf(now.id))
        engine.assign(canonicalFixture(database, "timer", "quick_event_entry", "2"), listOf(events.id))
        engine.assign(canonicalFixture(database, "timer", "life_period", "3"), listOf(since.id))

        engine.rename(now.id, "Deep focus")
        engine.archive(events.id, true)
        assertEquals("Deep focus", database.hubTagDao().tag(now.id)?.name)
        assertEquals("Focus", database.hubTagDao().tag(events.id)?.name)
        assertEquals("Focus", database.hubTagDao().tag(since.id)?.name)
        assertTrue(database.hubTagDao().tag(events.id)?.archived == true)
        assertFalse(database.hubTagDao().tag(since.id)?.archived == true)
        assertFails { engine.merge(now.id, events.id) }
        assertFalse(engine.deleteUnused(now.id))
        engine.remove(canonicalFixture(database, "timer", "life_period", "3"), listOf(since.id))
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
        val transaction = canonicalFixture(database, "soldi", "transaction", "tx-1")
        engine.assign(transaction, listOf(duplicate.id))
        engine.merge(duplicate.id, canonical.id)
        assertEquals(listOf(canonical.id), engine.tags(transaction).map { it.id })
        assertNull(database.hubTagDao().tag(duplicate.id))
    }

    @Test fun refreshUsageDoesNotRewriteTagsWhenDerivedValuesAreAlreadyCurrent() = runBlocking {
        val tag = engine.createStable("timer.now:99", HubTagNamespaces.TIMER_NOW, "Stable")
        engine.assign(canonicalFixture(database, "timer", "session", "99"), listOf(tag.id))
        val sqlite = database.openHelper.writableDatabase
        fun totalChanges(): Long = sqlite.query("SELECT total_changes()").use { it.moveToFirst(); it.getLong(0) }
        val before = totalChanges()

        database.hubTagDao().refreshUsage()

        assertEquals(before, totalChanges())
    }

    @Test fun soldiCategoryIsTypedAndLimitedToOnePrimaryAssignment() = runBlocking {
        val food = requireNotNull(engine.create(HubTagNamespaces.SOLDI_CATEGORY, "Food", kind = HubTagKinds.CATEGORY).tag)
        val travel = requireNotNull(engine.create(HubTagNamespaces.SOLDI_CATEGORY, "Travel", kind = HubTagKinds.CATEGORY).tag)
        val transaction = canonicalFixture(database, "soldi", "transaction", "tx-category")
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
