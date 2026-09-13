package com.gernalix.luoghi

import com.gernalix.luoghi.capsules.checkin.CheckInMatchDecision
import com.gernalix.luoghi.capsules.checkin.CheckInPolicy
import com.gernalix.luoghi.capsules.location.LocationSample
import com.gernalix.luoghi.data.PlaceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckInAccuracyPolicyTest {
    private val carloVisda = PlaceEntity(
        uuid = "carlo-visda",
        nickname = "Carlo Visda",
        lat = 55.6607113,
        lon = 12.6215103,
        radiusM = 40.0,
    )

    @Test
    fun reportedAccuracyExtendsSmallPlaceRadius() {
        val decision = CheckInPolicy.choosePlace(
            places = listOf(carloVisda),
            location = LocationSample(
                latitude = 55.6612113,
                longitude = 12.6215103,
                accuracyM = 20.0,
            ),
        )

        assertTrue(decision is CheckInMatchDecision.Matched)
        assertEquals("carlo-visda", (decision as CheckInMatchDecision.Matched).candidate.place.uuid)
        assertTrue(decision.candidate.distanceM > 40.0)
    }

    @Test
    fun reportedAccuracyDoesNotMatchPlaceBeyondItsUncertainty() {
        val decision = CheckInPolicy.choosePlace(
            places = listOf(carloVisda),
            location = LocationSample(
                latitude = 55.6617113,
                longitude = 12.6215103,
                accuracyM = 25.0,
            ),
        )

        assertTrue(decision is CheckInMatchDecision.UnknownPlace)
    }

    @Test
    fun accuracyAllowanceIsCapped() {
        val decision = CheckInPolicy.choosePlace(
            places = listOf(carloVisda),
            location = LocationSample(
                latitude = 55.6622113,
                longitude = 12.6215103,
                accuracyM = 500.0,
            ),
        )

        assertTrue(decision is CheckInMatchDecision.UnknownPlace)
    }
}
