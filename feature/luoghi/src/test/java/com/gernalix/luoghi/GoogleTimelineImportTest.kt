package com.gernalix.luoghi

import com.gernalix.luoghi.capsules.checkin.PlaceEventTypes
import com.gernalix.luoghi.data.GoogleTimelineImport
import com.gernalix.luoghi.data.PlaceEntity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GoogleTimelineImportTest {
    private val samePlace = "ChIJERERERERERERIiIiIiIiIiI"

    private fun visit(
        start: String, end: String,
        externalId: String = samePlace,
        location: String = "55.6761°, 12.5683°",
        name: String = "Synthetic place",
    ): JSONObject = JSONObject()
        .put("startTime", start)
        .put("endTime", end)
        .put("visit", JSONObject().put("topCandidate", JSONObject()
            .put("placeId", externalId)
            .put("placeName", name)
            .put("placeAddress", "Test Road 1")
            .put("placeLocation", JSONObject().put("latLng", location))))

    private fun body(vararg segments: JSONObject): String =
        JSONObject().put("semanticSegments", JSONArray(segments.toList())).toString()

    @Test fun repeatedVisitsRemainSeparateAndDeterministic() {
        val source = body(
            visit("2026-01-01T10:00:00+01:00", "2026-01-01T11:00:00+01:00"),
            visit("2026-01-02T10:00:00+01:00", "2026-01-02T11:00:00+01:00"),
        )
        val (visits, rejected) = GoogleTimelineImport.parse(source)
        assertEquals(2, visits.size)
        assertEquals(0, rejected)
        assertEquals(visits[0].placeUuid, visits[1].placeUuid)
        assertNotEquals(visits[0].sessionUuid, visits[1].sessionUuid)
        assertEquals(visits[0].eventUuid(PlaceEventTypes.CHECK_IN),
            GoogleTimelineImport.parse(source).first[0].eventUuid(PlaceEventTypes.CHECK_IN))
        assertNotEquals(visits[0].eventUuid(PlaceEventTypes.CHECK_IN),
            visits[0].eventUuid(PlaceEventTypes.CHECK_OUT))
        assertEquals("Synthetic place", visits[0].name)
        assertEquals("Test Road 1", visits[0].address)
    }

    @Test fun identicalVisitsAreDeduplicatedByStableId() {
        val duplicate = visit("2026-02-01T10:00:00Z", "2026-02-01T11:00:00Z")
        val (visits, rejected) = GoogleTimelineImport.parse(body(duplicate, duplicate))
        assertEquals(1, visits.size)
        assertEquals(1, rejected)
    }

    @Test fun invalidCoordinatesAndBackwardsTimeCannotBeImported() {
        val (visits, rejected) = GoogleTimelineImport.parse(body(
            visit("2026-02-01T10:00:00Z", "2026-02-01T11:00:00Z"),
            visit("2026-02-01T12:00:00Z", "2026-02-01T11:00:00Z"),
            visit("2026-02-02T12:00:00Z", "2026-02-02T13:00:00Z", location = "95°, 12°"),
            visit("2026-02-03T12:00:00Z", "2026-02-03T13:00:00Z", location = "bad"),
        ))
        assertEquals(1, visits.size)
        assertEquals(3, rejected)
    }

    @Test fun consentWallLabelsCannotPollutePlaces() {
        val (visits, _) = GoogleTimelineImport.parse(body(
            visit("2026-02-01T10:00:00Z", "2026-02-01T11:00:00Z",
                name = "Before you continue to Google Maps"),
        ))
        assertNull(visits.single().name)
    }

    @Test fun existingPlaceMatchingRequiresUniqueNameAndProximity() {
        val record = GoogleTimelineImport.Visit(
            externalId = samePlace,
            startMs = 1_760_000_000_000L,
            endMs = 1_760_000_900_000L,
            lat = 55.6761, lon = 12.5683,
            name = "Lidl", address = "Test Road 1",
        )
        val matching = PlaceEntity(
            uuid = "existing-uuid", nickname = "LiDl",
            lat = 55.676105, lon = 12.568305,
        )
        assertEquals("existing-uuid", GoogleTimelineImport.matchExistingPlace(record, listOf(matching)))
        assertNull(GoogleTimelineImport.matchExistingPlace(
            record, listOf(matching, matching.copy(uuid = "another-id"))))
        assertNull(GoogleTimelineImport.matchExistingPlace(
            record, listOf(matching.copy(nickname = "Different business"))))
        assertNull(GoogleTimelineImport.matchExistingPlace(
            record, listOf(matching.copy(lat = 55.7000))))
        assertNull(GoogleTimelineImport.matchExistingPlace(
            record.copy(name = null), listOf(matching)))
    }

    @Test fun offsetsAreConvertedToAbsoluteInstants() {
        val (visits, _) = GoogleTimelineImport.parse(body(
            visit("2026-03-01T11:00:00+02:00", "2026-03-01T12:00:00+02:00"),
        ))
        assertEquals(3600000L, visits.single().endMs - visits.single().startMs)
    }
}
