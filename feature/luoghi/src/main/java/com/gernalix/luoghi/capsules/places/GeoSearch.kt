package com.gernalix.luoghi.capsules.places

import com.gernalix.luoghi.data.PlaceEntity
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoBoundingBox(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
)

data class PlaceDistance(
    val place: PlaceEntity,
    val distanceM: Double,
)

object GeoSearch {
    private const val EARTH_RADIUS_M = 6_371_008.8
    private const val METERS_PER_LATITUDE_DEGREE = 111_320.0

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val a = sin(dLat / 2).pow(2) + cos(rLat1) * cos(rLat2) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a))
    }

    fun boundingBox(lat: Double, lon: Double, radiusM: Double): GeoBoundingBox {
        val safeRadius = radiusM.coerceAtLeast(0.0)
        val latDelta = safeRadius / METERS_PER_LATITUDE_DEGREE
        val lonMeters = METERS_PER_LATITUDE_DEGREE * cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        val lonDelta = safeRadius / lonMeters
        return GeoBoundingBox(
            minLat = max(-90.0, lat - latDelta),
            maxLat = min(90.0, lat + latDelta),
            minLon = max(-180.0, lon - lonDelta),
            maxLon = min(180.0, lon + lonDelta),
        )
    }

    fun withinRadius(
        places: List<PlaceEntity>,
        lat: Double,
        lon: Double,
        radiusM: Double,
        includePlaceRadius: Boolean = true,
    ): List<PlaceDistance> =
        places.mapNotNull { place ->
            val placeLat = place.lat ?: return@mapNotNull null
            val placeLon = place.lon ?: return@mapNotNull null
            val distance = distanceMeters(lat, lon, placeLat, placeLon)
            val matchRadius = radiusM + if (includePlaceRadius) place.radiusM.orZero() else 0.0
            if (distance <= matchRadius) PlaceDistance(place, distance) else null
        }.sortedWith(compareBy<PlaceDistance> { it.distanceM }.thenBy { it.place.nickname.lowercase() })

    fun nearest(
        places: List<PlaceEntity>,
        lat: Double,
        lon: Double,
        limit: Int,
    ): List<PlaceDistance> =
        places.mapNotNull { place ->
            val placeLat = place.lat ?: return@mapNotNull null
            val placeLon = place.lon ?: return@mapNotNull null
            PlaceDistance(place, distanceMeters(lat, lon, placeLat, placeLon))
        }
            .sortedWith(compareBy<PlaceDistance> { it.distanceM }.thenBy { it.place.nickname.lowercase() })
            .take(limit.coerceAtLeast(1))

    private fun Double?.orZero(): Double = this ?: 0.0
}
