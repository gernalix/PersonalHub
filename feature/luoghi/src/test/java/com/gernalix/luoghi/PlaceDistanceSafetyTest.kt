package com.gernalix.luoghi

import com.gernalix.luoghi.capsules.places.*
import com.gernalix.luoghi.data.PlaceEntity
import org.junit.Assert.*
import org.junit.Test

class PlaceDistanceSafetyTest {
    @Test fun antipodalDistancesAreFiniteAndKeepAllPlaces() {
        val invalidLatitude = (1..8999).map { it / 100.0 }.firstOrNull {
            !GeoSearch.distanceMeters(it, 0.0, -it, 180.0).isFinite()
        }
        assertNull("Valid antipodal latitude produced an invalid distance: $invalidLatitude", invalidLatitude)
        val distance = GeoSearch.distanceMeters(0.08, 0.0, -0.08, 180.0)
        assertTrue("A valid opposite-side-of-Earth distance must remain finite", distance.isFinite())
        assertEquals(Math.PI * 6371008.8, distance, 0.1)
        val place = PlaceEntity(uuid = "opposite", nickname = "Opposite", lat = -0.08, lon = 180.0)
        assertNotNull(PlaceListUiMapper.map(listOf(place), emptyList(), emptyList(),
            currentLocation = PlaceListLocation(0.08, 0.0)).single().distanceMeters)
    }
    @Test fun invalidSavedCoordinatesRemainUnknownInsteadOfCrashingOrInventingADistance() {
        val places = listOf(
            PlaceEntity(uuid = "nan", nickname = "NaN", lat = Double.NaN, lon = 12.0),
            PlaceEntity(uuid = "infinity", nickname = "Infinity", lat = 55.0, lon = Double.POSITIVE_INFINITY),
            PlaceEntity(uuid = "out-of-range", nickname = "Invalid", lat = 91.0, lon = 12.0),
        )
        val rows = PlaceListUiMapper.map(places, emptyList(), emptyList(), currentLocation = PlaceListLocation(55.0, 12.0))
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.distanceMeters == null })
    }
    @Test fun invalidReferenceCannotProduceMisleadingDistances() {
        val place = PlaceEntity(uuid = "place", nickname = "Place", lat = 55.0, lon = 12.0)
        for (reference in listOf(PlaceListLocation(Double.NaN, 12.0), PlaceListLocation(55.0, 181.0))) {
            assertNull(PlaceListUiMapper.map(listOf(place), emptyList(), emptyList(), currentLocation = reference).single().distanceMeters)
        }
    }
}
