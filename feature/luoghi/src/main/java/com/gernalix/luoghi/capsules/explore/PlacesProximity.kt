package com.gernalix.luoghi.capsules.explore

import com.gernalix.luoghi.capsules.mapviewer.OsmAndMapPlan
import com.gernalix.luoghi.capsules.mapviewer.OsmAndReference
import com.gernalix.luoghi.capsules.places.GeoSearch
import com.gernalix.luoghi.data.PlaceEntity
import java.util.Locale

data class NearbyPlace(val place: PlaceEntity, val distanceMeters: Double?)

object PlacesProximity {
    fun ordered(places: List<PlaceEntity>, reference: OsmAndReference): List<NearbyPlace> {
        require(OsmAndMapPlan.valid(reference.latitude, reference.longitude))
        return places.filterNot { it.archived }.map { place ->
            val latitude = place.lat
            val longitude = place.lon
            val distance = if (latitude != null && longitude != null && OsmAndMapPlan.valid(latitude, longitude)) {
                GeoSearch.distanceMeters(reference.latitude, reference.longitude, latitude, longitude)
            } else null
            NearbyPlace(place, distance)
        }.sortedWith(compareBy<NearbyPlace> { it.distanceMeters == null }
            .thenBy { it.distanceMeters }.thenBy { it.place.nickname.lowercase(Locale.ROOT) }.thenBy { it.place.uuid })
    }
}
