package com.gernalix.luoghi

import com.gernalix.luoghi.capsules.places.PlaceSearch
import com.gernalix.luoghi.data.PlaceEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceSearchTest {
    private val place = PlaceEntity(uuid = "internal-id", nickname = "Café Central", address = "Main Street", notes = "Quiet courtyard")

    @Test fun searchesAcrossFieldsWithCaseAccentsTagsAndWhitespace() {
        assertTrue(PlaceSearch.matches(place, listOf("Lunch"), " CAFE   street LUNCH "))
        assertTrue(PlaceSearch.matches(place, emptyList(), "QUIET"))
        assertTrue(PlaceSearch.matches(place, emptyList(), " "))
        assertFalse(PlaceSearch.matches(place, emptyList(), "cafe missing"))
        assertFalse(PlaceSearch.matches(place, emptyList(), "internal-id"))
        assertTrue(PlaceSearch.matches(place, emptyList(), "Amager", "Amagerbro Amager Copenhagen Denmark"))
        assertTrue(PlaceSearch.matches(place, emptyList(), "amager denmark", "Amager Copenhagen Denmark"))
        assertFalse(PlaceSearch.matches(place, emptyList(), "Amager", ""))
    }
}
