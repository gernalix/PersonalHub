package com.gernalix.luoghi

import com.gernalix.luoghi.capsules.explore.PlacesProximity
import com.gernalix.luoghi.capsules.mapviewer.OsmAndReference
import com.gernalix.luoghi.data.PlaceEntity
import org.junit.Assert.*
import org.junit.Test

class PlacesProximityTest {
    @Test fun distanceWinsOverNameAndTiesAreStableWithMissingCoordinatesLast() {
        val places = listOf(
            PlaceEntity(uuid = "far", nickname = "A", lat = 0.0, lon = 1.0),
            PlaceEntity(uuid = "z", nickname = "Same", lat = 0.0, lon = 0.0),
            PlaceEntity(uuid = "a", nickname = "Same", lat = 0.0, lon = 0.0),
            PlaceEntity(uuid = "missing", nickname = "B"),
            PlaceEntity(uuid = "invalid", nickname = "A", lat = Double.NaN, lon = 0.0),
            PlaceEntity(uuid = "archived", nickname = "Hidden", lat = 0.0, lon = 0.0, archived = true),
        )
        val result = PlacesProximity.ordered(places, OsmAndReference(0.0, 0.0, "Reference"))
        assertEquals(listOf("a", "z", "far", "invalid", "missing"), result.map { it.place.uuid })
        assertEquals(0.0, result.first().distanceMeters!!, 0.0)
        assertTrue(result[2].distanceMeters!! > 100_000)
        assertNull(result.last().distanceMeters)
        assertEquals(result, PlacesProximity.ordered(places.reversed(), OsmAndReference(0.0, 0.0, "Reference")))
    }

    @Test fun allPlacesAreRetainedWithoutSuggestionOrMapLimits() {
        val places = (0..1500).map { PlaceEntity(uuid = "p-$it", nickname = "Place $it", lat = 1.0, lon = 2.0) }
        assertEquals(1501, PlacesProximity.ordered(places, OsmAndReference(1.0, 2.0, "Reference")).size)
    }
}
