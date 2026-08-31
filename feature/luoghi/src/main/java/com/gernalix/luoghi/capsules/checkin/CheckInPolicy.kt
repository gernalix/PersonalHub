package com.gernalix.luoghi.capsules.checkin

import com.gernalix.luoghi.capsules.location.LocationSample
import com.gernalix.luoghi.capsules.places.GeoSearch
import com.gernalix.luoghi.data.PlaceEntity
import com.gernalix.luoghi.data.PlaceEventEntity

object CheckInPolicy {
    const val DEFAULT_RADIUS_M = 75.0
    private const val AMBIGUOUS_DISTANCE_MARGIN_M = 10.0
    private const val MAX_AMBIGUOUS_CHOICES = 5

    fun choosePlace(
        places: List<PlaceEntity>,
        location: LocationSample,
    ): CheckInMatchDecision {
        val candidates = places
            .filter { !it.archived }
            .mapNotNull { place ->
                val lat = place.lat ?: return@mapNotNull null
                val lon = place.lon ?: return@mapNotNull null
                val distance = GeoSearch.distanceMeters(location.latitude, location.longitude, lat, lon)
                if (distance <= effectiveRadiusM(place)) {
                    CheckInCandidate(place, distance)
                } else {
                    null
                }
            }
            .sortedWith(compareBy<CheckInCandidate> { it.distanceM }.thenBy { it.place.nickname.lowercase() })

        if (candidates.isEmpty()) return CheckInMatchDecision.UnknownPlace
        if (candidates.size == 1) return CheckInMatchDecision.Matched(candidates.single())

        val first = candidates[0]
        val second = candidates[1]
        return if ((second.distanceM - first.distanceM) <= AMBIGUOUS_DISTANCE_MARGIN_M) {
            CheckInMatchDecision.Ambiguous(candidates.take(MAX_AMBIGUOUS_CHOICES))
        } else {
            CheckInMatchDecision.Matched(first)
        }
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
}
