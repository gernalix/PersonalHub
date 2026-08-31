package com.gernalix.luoghi.capsules.routedistance

import java.time.Instant

enum class RouteTravelMode(
    val cacheValue: String,
    val routesApiValue: String,
) {
    BICYCLE("BICYCLE", "BICYCLE"),
}

object RouteDistanceProviders {
    const val GOOGLE_ROUTES = "google_routes"
}

enum class RouteDistanceMethod {
    GOOGLE_BICYCLE,
    INVERSE_GOOGLE_BICYCLE,
    HAVERSINE_FALLBACK,
    MIXED,
    ZERO,
    UNAVAILABLE,
}

data class RouteCoordinates(
    val latitude: Double,
    val longitude: Double,
)

data class GoogleRouteDistance(
    val distanceMeters: Long,
    val durationSeconds: Long,
)

data class RouteDistanceResult(
    val distanceMeters: Long,
    val durationSeconds: Long?,
    val method: RouteDistanceMethod,
    val counted: Boolean = true,
) {
    val usesGoogle: Boolean =
        method == RouteDistanceMethod.GOOGLE_BICYCLE || method == RouteDistanceMethod.INVERSE_GOOGLE_BICYCLE

    val usesFallback: Boolean =
        method == RouteDistanceMethod.HAVERSINE_FALLBACK || method == RouteDistanceMethod.UNAVAILABLE

    companion object {
        fun zero(): RouteDistanceResult =
            RouteDistanceResult(
                distanceMeters = 0L,
                durationSeconds = 0L,
                method = RouteDistanceMethod.ZERO,
                counted = false,
            )

        fun unavailable(): RouteDistanceResult =
            RouteDistanceResult(
                distanceMeters = 0L,
                durationSeconds = null,
                method = RouteDistanceMethod.UNAVAILABLE,
                counted = false,
            )
    }
}

data class RouteDistancePeriod(
    val startInclusive: Instant,
    val endExclusive: Instant,
)

data class RouteDistancePeriodUi(
    val distanceMeters: Long = 0L,
    val method: RouteDistanceMethod = RouteDistanceMethod.UNAVAILABLE,
    val segmentCount: Int = 0,
    val fallbackSegmentCount: Int = 0,
    val unavailableSegmentCount: Int = 0,
)

data class RouteDistanceStatsUi(
    val today: RouteDistancePeriodUi = RouteDistancePeriodUi(),
    val week: RouteDistancePeriodUi = RouteDistancePeriodUi(),
    val month: RouteDistancePeriodUi = RouteDistancePeriodUi(),
    val loading: Boolean = false,
) {
    companion object {
        fun loading(): RouteDistanceStatsUi = RouteDistanceStatsUi(loading = true)
    }
}
