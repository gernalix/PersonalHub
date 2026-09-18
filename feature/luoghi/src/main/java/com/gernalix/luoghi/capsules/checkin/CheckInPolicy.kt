package com.gernalix.luoghi.capsules.checkin

import com.gernalix.luoghi.capsules.location.LocationSample
import com.gernalix.luoghi.capsules.places.GeoSearch
import com.gernalix.luoghi.data.PlaceEntity
import com.gernalix.luoghi.data.PlaceEventEntity

object CheckInPolicy {
    const val DEFAULT_RADIUS_M = 75.0
    private const val MAX_ACCURACY_ALLOWANCE_M = 50.0
    private const val MAX_AMBIGUOUS_CHOICES = 5

    fun choosePlace(
        places: List<PlaceEntity>,
        location: LocationSample,
    ): CheckInMatchDecision {
        val accuracyAllowanceM = accuracyAllowanceM(location)
        val candidates = places
            .filter { !it.archived }
            .mapNotNull { place ->
                val lat = place.lat ?: return@mapNotNull null
                val lon = place.lon ?: return@mapNotNull null
                val distance = GeoSearch.distanceMeters(location.latitude, location.longitude, lat, lon)
                if (distance <= matchThresholdM(place, location)) {
                    CheckInCandidate(place, distance)
                } else {
                    null
                }
            }
            .sortedWith(compareBy<CheckInCandidate> { it.distanceM }.thenBy { it.place.nickname.lowercase() })

        if (candidates.isEmpty()) return CheckInMatchDecision.UnknownPlace
        if (candidates.size == 1) return CheckInMatchDecision.Matched(candidates.single())

        val nearest = candidates[0]
        val runnerUp = candidates[1]
        if (
            accuracyAllowanceM > 0.0 &&
            nearest.distanceM + accuracyAllowanceM < runnerUp.distanceM - accuracyAllowanceM
        ) {
            return CheckInMatchDecision.Matched(nearest)
        }

        return CheckInMatchDecision.Ambiguous(candidates.take(MAX_AMBIGUOUS_CHOICES))
    }

    fun activeVisit(events: List<PlaceEventEntity>): PlaceEventEntity? {
        val sorted = events.sortedWith(compareByDescending<PlaceEventEntity> { it.timestamp }.thenByDescending { it.id })
        val checkedOutPlaceIds = mutableSetOf<String>()
        for (event in sorted) {
            when (event.eventType) {
                PlaceEventTypes.CHECK_OUT -> checkedOutPlaceIds += event.placeId
                PlaceEventTypes.CHECK_IN -> if (event.placeId !in checkedOutPlaceIds) return event
            }
        }
        return null
    }

    fun effectiveRadiusM(place: PlaceEntity): Double =
        place.radiusM?.takeIf { it > 0.0 } ?: DEFAULT_RADIUS_M

    internal fun matchThresholdM(place: PlaceEntity, location: LocationSample): Double =
        effectiveRadiusM(place) + accuracyAllowanceM(location)

    private fun accuracyAllowanceM(location: LocationSample): Double =
        location.accuracyM
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.coerceAtMost(MAX_ACCURACY_ALLOWANCE_M)
            ?: 0.0
}
