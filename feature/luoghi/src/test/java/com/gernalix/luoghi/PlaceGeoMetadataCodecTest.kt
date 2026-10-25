package com.gernalix.luoghi

import com.gernalix.luoghi.capsules.geometadata.PlaceGeoMetadataCodec
import com.gernalix.luoghi.capsules.places.PlaceSearch
import com.gernalix.luoghi.data.PlaceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaceGeoMetadataCodecTest {
    private val place = PlaceEntity(uuid = "example", nickname = "Casa", lat = 55.665, lon = 12.6)

    @Test fun preservesFullResponseAndSearchesAdministrativeAddressAndAliasesOffline() {
        val raw = """
            {"display_name":"Amagerbro, Copenhagen, Denmark",
             "address":{"road":"Amagerbrogade","house_number":"17","suburb":"Amagerbro",
                        "city_district":"Amager","city":"Copenhagen",
                        "state":"Capital Region of Denmark","country":"Denmark","postcode":"2300"},
             "namedetails":{"name:da":"København","name:it":"Copenaghen"},
             "extratags":{"wikipedia":"da:Amager"}}
        """.trimIndent()
        val metadata = PlaceGeoMetadataCodec.found(place, raw)
        assertEquals(raw, metadata.rawJson)
        assertEquals("FOUND", metadata.status)
        assertEquals(place.uuid, metadata.placeUuid)
        assertTrue(PlaceSearch.matches(place, emptyList(), "amager", metadata.searchText))
        assertTrue(PlaceSearch.matches(place, emptyList(), "amagerbrogade 17", metadata.searchText))
        assertTrue(PlaceSearch.matches(place, emptyList(), "copenaghen", metadata.searchText))
        assertTrue(PlaceSearch.matches(place, emptyList(), "denmark", metadata.searchText))
        assertFalse(PlaceSearch.matches(place, emptyList(), "berlin", metadata.searchText))
    }

    @Test fun emptyResultIsCachedAndNeverInventsLocalities() {
        val metadata = PlaceGeoMetadataCodec.noMatch(place)
        assertEquals("NO_MATCH", metadata.status)
        assertEquals("", metadata.searchText)
        assertFalse(PlaceSearch.matches(place, emptyList(), "Amager", metadata.searchText))
    }
}
