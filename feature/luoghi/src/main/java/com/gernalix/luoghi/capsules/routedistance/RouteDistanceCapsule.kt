package com.gernalix.luoghi.capsules.routedistance

import com.gernalix.luoghi.data.PlaceEntity
import com.gernalix.luoghi.data.PlaceEventEntity
import java.time.ZoneId

class RouteDistanceCapsule(
    private val repository: RouteDistanceRepository,
) {
    suspend fun stats(
        places: List<PlaceEntity>,
        events: List<PlaceEventEntity>,
        nowMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): RouteDistanceStatsUi =
        repository.distanceStats(
            places = places,
            events = events,
            nowMs = nowMs,
            zoneId = zoneId,
        )

    suspend fun distanceForPeriod(
        places: List<PlaceEntity>,
        events: List<PlaceEventEntity>,
        period: RouteDistancePeriod,
        mode: RouteTravelMode = RouteTravelMode.BICYCLE,
    ): RouteDistancePeriodUi =
        repository.distanceForPeriod(
            places = places,
            events = events,
            period = period,
            mode = mode,
        )
}
