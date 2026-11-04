package com.gernalix.personalhub.core.hubcontext

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.database.DatabasePreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HubSavedSearchStoreTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DATABASE_NAME)
    }

    @After fun tearDown() {
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DATABASE_NAME)
    }

    @Test fun savedQueryRoundTripsAndDeletesAcrossStoreInstances() = runBlocking {
        val first = HubSavedSearchStore(context)
        val saved = first.save(
            HubSavedSearch(
                id = "saved-search-1",
                name = "  Places with location  ",
                query = HubSearchQuery(
                    text = " Copenhagen ",
                    modules = setOf(" places "),
                    entityKinds = setOf(" place "),
                    capabilities = setOf(" location "),
                    includeArchived = true,
                    perAdapterLimit = 7,
                    limit = 11,
                ),
            ),
        )
        val second = HubSavedSearchStore(context)
        assertEquals("Places with location", saved.name)
        assertEquals(saved, second.list().single())
        assertEquals("Copenhagen", saved.query.text)
        assertEquals(setOf("places"), saved.query.modules)
        assertEquals(setOf("place"), saved.query.entityKinds)
        assertEquals(setOf("location"), saved.query.capabilities)
        assertTrue(second.delete(saved.id))
        assertFalse(second.delete(saved.id))
        assertTrue(first.list().isEmpty())
    }

    @Test fun concurrentSavesAcrossInstancesKeepEverySearch() = runBlocking {
        // Initialize before racing, so this exercises updates rather than database startup.
        HubSavedSearchStore(context).list()
        val ready = CompletableDeferred<Unit>()
        val saves = (1..24).map { index ->
            async(Dispatchers.IO) {
                ready.await()
                HubSavedSearchStore(context).save(HubSavedSearch(
                    id = "search-$index", name = "Search $index", query = HubSearchQuery(text = "$index"),
                ))
            }
        }
        ready.complete(Unit)
        saves.awaitAll()
        assertEquals((1..24).map { "search-$it" }.toSet(), HubSavedSearchStore(context).list().map { it.id }.toSet())
    }

    @Test fun malformedStoredSearchesCannotBeSilentlyOverwritten() = runBlocking {
        val preferences = DatabasePreferences(context, "hub_search_v1")
        val original = "[{broken]"
        preferences.edit().putString("saved_queries", original).commit()
        val failure = runCatching {
            HubSavedSearchStore(context).save(HubSavedSearch(name = "New", query = HubSearchQuery()))
        }.exceptionOrNull()
        assertTrue("Invalid saved data must be reported", failure != null)
        assertEquals(original, preferences.getString("saved_queries", null))
    }
}
