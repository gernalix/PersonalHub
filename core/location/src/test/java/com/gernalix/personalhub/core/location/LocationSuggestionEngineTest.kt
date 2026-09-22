package com.gernalix.personalhub.core.location

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationSuggestionEngineTest {
    @Test
    fun combinesProvidersWithoutCollapsingDifferentSources() = runBlocking {
        val local = FakeProvider(
            LocationSuggestionSource.ExistingPlace,
            listOf(LocationSuggestion("same", "Home", "Local", LocationSuggestionSource.ExistingPlace)),
        )
        val google = FakeProvider(
            LocationSuggestionSource.Google,
            listOf(LocationSuggestion("same", "Home", "Google", LocationSuggestionSource.Google)),
        )
        val engine = LocationSuggestionEngine(listOf(local, google), maxSuggestions = 10)

        val results = engine.search("home")
        assertEquals(2, results.size)
        assertEquals(
            listOf(LocationSuggestionSource.ExistingPlace, LocationSuggestionSource.Google),
            results.map { it.source },
        )
    }

    @Test
    fun resolveRoutesToOwningProvider() = runBlocking {
        val google = FakeProvider(
            LocationSuggestionSource.Google,
            listOf(LocationSuggestion("g1", "Place", "Address", LocationSuggestionSource.Google)),
        )
        val engine = LocationSuggestionEngine(listOf(google))
        val resolved = engine.resolve(google.values.single())
        assertEquals("g1", resolved.id)
        assertEquals(LocationSuggestionSource.Google, resolved.source)
    }

    private class FakeProvider(
        override val source: LocationSuggestionSource,
        val values: List<LocationSuggestion>,
    ) : LocationSuggestionProvider {
        override val isConfigured = true
        override suspend fun search(query: String) = values
        override suspend fun resolve(suggestion: LocationSuggestion) =
            ResolvedLocation(
                id = suggestion.id,
                displayName = suggestion.primaryText,
                address = suggestion.secondaryText,
                latitude = null,
                longitude = null,
                source = source,
            )
    }
}
