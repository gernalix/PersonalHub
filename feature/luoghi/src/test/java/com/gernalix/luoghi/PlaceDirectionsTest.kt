package com.gernalix.luoghi

import android.content.Intent
import android.net.Uri
import com.gernalix.luoghi.ui.places.PlaceDirections
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaceDirectionsTest {
    @Test fun preservesHouseNumberAndReservedCharactersWithoutFixingAMapProvider() {
        val address = "Nørrebrogade 12A, 2. th & café #3, København"
        val intent = requireNotNull(PlaceDirections.intent("  $address  ", 10.0, 20.0))
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("geo", intent.data!!.scheme)
        assertEquals("0,0", intent.data!!.schemeSpecificPart.substringBefore('?'))
        assertEquals(address, Uri.decode(intent.data!!.encodedSchemeSpecificPart.substringAfter("?q=")))
        assertNull(intent.`package`)
        assertNull(intent.component)
        assertFalse(intent.data!!.encodedSchemeSpecificPart.substringAfter("?q=").contains("&"))
        assertNull(intent.data!!.fragment)
    }

    @Test fun missingAddressUsesSavedCoordinates() {
        val intent = requireNotNull(PlaceDirections.intent("  ", 55.67, 12.56))
        assertEquals("geo:55.67,12.56?q=55.67,12.56", intent.dataString)
        assertNull(intent.`package`)
    }

    @Test fun missingOrInvalidLocationDoesNotInventCoordinates() {
        assertNull(PlaceDirections.intent(null, null, null))
        assertNull(PlaceDirections.intent("", Double.NaN, 12.0))
        assertNull(PlaceDirections.intent(null, 91.0, 12.0))
    }
}
