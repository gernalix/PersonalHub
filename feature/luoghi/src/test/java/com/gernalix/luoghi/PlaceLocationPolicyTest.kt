package com.gernalix.luoghi

import com.gernalix.luoghi.data.PlaceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceLocationPolicyTest {
    private fun saved() = PlaceFormState.from(
        PlaceEntity(uuid = "saved-place", nickname = "Home", lat = 55.67, lon = 12.56, address = "Old address"),
    )

    @Test fun editingSavedAddressKeepsOriginalPosition() {
        val form = saved().withAddressText("Corrected address")
        assertEquals("Corrected address", form.address)
        assertEquals(55.67, form.lat!!, 0.0)
        assertEquals(12.56, form.lon!!, 0.0)
        assertEquals("saved-place", form.uuid)
    }

    @Test fun resolvingAnotherAddressDoesNotRelocateSavedPlace() {
        val form = saved().withResolvedAddress("Resolved address", 48.85, 2.35)
        assertEquals("Resolved address", form.address)
        assertEquals(55.67, form.lat!!, 0.0)
        assertEquals(12.56, form.lon!!, 0.0)
    }

    @Test fun addingAnAddressToAnUnlocatedSavedPlaceDoesNotInventAPosition() {
        val form = PlaceFormState.from(PlaceEntity(uuid = "unlocated", nickname = "Archive"))
            .withResolvedAddress("Resolved address", 48.85, 2.35)
        assertNull(form.lat)
        assertNull(form.lon)
        assertEquals("Resolved address", form.address)
    }

    @Test fun newAddressBasedPlaceUsesSelectedAddressCoordinates() {
        val form = PlaceFormState(nickname = "Station").withResolvedAddress("Station address", 55.67, 12.56)
        assertEquals(55.67, form.lat!!, 0.0)
        assertEquals(12.56, form.lon!!, 0.0)
    }

    @Test fun changingNewAddressInvalidatesItsPreviouslyResolvedPosition() {
        val form = PlaceFormState().withResolvedAddress("First address", 55.67, 12.56)
            .withAddressText("Another address")
        assertNull(form.lat)
        assertNull(form.lon)
    }

    @Test fun addressEditsKeepTheExplicitCurrentPositionOfANewPlace() {
        val form = PlaceFormState(lat = 55.67, lon = 12.56, locationSource = PlaceLocationSource.CURRENT_LOCATION)
            .withAddressText("Corrected address").withResolvedAddress("Postal address", 48.85, 2.35)
        assertEquals("Postal address", form.address)
        assertEquals(55.67, form.lat!!, 0.0)
        assertEquals(12.56, form.lon!!, 0.0)
    }
}
