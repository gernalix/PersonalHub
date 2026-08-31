package com.gernalix.luoghi.capsules.routedistance

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

object RouteDistanceMath {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun haversineMeters(origin: RouteCoordinates, destination: RouteCoordinates): Long {
        val originLat = Math.toRadians(origin.latitude)
        val destinationLat = Math.toRadians(destination.latitude)
        val deltaLat = Math.toRadians(destination.latitude - origin.latitude)
        val deltaLon = Math.toRadians(destination.longitude - origin.longitude)

        val a = sin(deltaLat / 2).pow(2.0) +
            cos(originLat) * cos(destinationLat) * sin(deltaLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return (EARTH_RADIUS_M * c).roundToLong().coerceAtLeast(0L)
    }
}
