package com.gernalix.personalhub.core.hubcontext

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gernalix.personalhub.core.database.PersonalHubDatabase
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
}
