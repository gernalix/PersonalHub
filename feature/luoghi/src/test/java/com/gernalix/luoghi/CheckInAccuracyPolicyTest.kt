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
    private val rema = PlaceEntity(
        uuid = "rema",
        nickname = "Rema",
        lat = 55.6610736,
        lon = 12.6207364,
        radiusM = 75.0,
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
    fun realCarloVisdaAttemptPrefersClearlySeparatedNearestPlace() {
        val location = LocationSample(
            latitude = 55.6607845,
            longitude = 12.6215530,
            accuracyM = 12.128000259399414,
        )

        val decision = CheckInPolicy.choosePlace(
            places = listOf(carloVisda, rema),
            location = location,
        )

        assertTrue(decision is CheckInMatchDecision.Matched)
        assertEquals("carlo-visda", (decision as CheckInMatchDecision.Matched).candidate.place.uuid)
        assertTrue(decision.candidate.distanceM < 10.0)
    }

    @Test
    fun overlappingCandidatesStayAmbiguousWhenGpsUncertaintyCannotSeparateThem() {
        val near = PlaceEntity(
            uuid = "near",
            nickname = "Near",
            lat = 0.0,
            lon = 0.0,
            radiusM = 100.0,
        )
        val close = PlaceEntity(
            uuid = "close",
            nickname = "Close",
            lat = 0.0,
            lon = 0.00005,
            radiusM = 100.0,
        )

        val decision = CheckInPolicy.choosePlace(
            places = listOf(near, close),
            location = LocationSample(0.0, 0.0, accuracyM = 4.0),
        )

        assertTrue(decision is CheckInMatchDecision.Ambiguous)
        assertEquals(
            listOf("near", "close"),
            (decision as CheckInMatchDecision.Ambiguous).candidates.map { it.place.uuid },
        )
    }

    @Test
    fun loggedMatchThresholdIncludesAccuracyAllowance() {
        val location = LocationSample(
            latitude = 55.6607845,
            longitude = 12.6215530,
            accuracyM = 12.128000259399414,
        )

        assertEquals(
            52.128000259399414,
            CheckInPolicy.matchThresholdM(carloVisda, location),
            0.001,
        )
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
