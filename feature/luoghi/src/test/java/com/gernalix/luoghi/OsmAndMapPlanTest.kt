package com.gernalix.luoghi

import com.gernalix.luoghi.capsules.mapviewer.*
import org.junit.Assert.*
import org.junit.Test

class OsmAndMapPlanTest {
    @Test fun arbitraryReferenceIsDistinctAndMissingOrInvalidCoordinatesAreExcluded() {
        val markers = listOf(
            MapMarkerModel("one", "One", null, null, 12.0, 30.0),
            MapMarkerModel("one", "Duplicate", null, null, 12.0, 30.0),
            MapMarkerModel("bad", "Invalid", null, null, Double.NaN, 30.0),
        )
        val points = OsmAndMapPlan.points(markers, OsmAndReference(0.0, 0.0, "Temporary"))
        assertEquals(2, points.size)
        assertTrue(points.first().reference)
        assertEquals(0.0, points.first().latitude, 0.0)
        assertFalse(points.last().reference)
        assertEquals("ph-place:one", points.last().id)
    }
    @Test fun selectedSavedPlaceIsNotDrawnTwiceAndLargeCollectionsAreNotSilentlyTruncated() {
        val markers = (0..1500).map { MapMarkerModel("place-$it", "Place $it", null, null, 1.0, 2.0) }
        val points = OsmAndMapPlan.points(markers, OsmAndReference(1.0, 2.0, "Selected", "place-0"))
        assertEquals(1501, points.size)
        assertEquals(1, points.count { it.reference })
        assertFalse(points.any { it.id == "ph-place:place-0" })
    }
}
