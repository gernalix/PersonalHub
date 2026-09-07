package com.gernalix.luoghi.capsules.places

import com.gernalix.luoghi.capsules.stats.PlaceStatsUi
import com.gernalix.luoghi.capsules.visits.VisitUiModel
import com.gernalix.luoghi.data.PlaceEntity
import kotlin.math.roundToLong

enum class PlaceSortCriterion {
    DISTANCE,
    LAST_VISIT,
    TOTAL_TIME,
    VISIT_COUNT,
}

enum class PlaceSortDirection {
    ASC,
    DESC,
}

data class PlaceSortState(
    val criterion: PlaceSortCriterion = PlaceSortCriterion.LAST_VISIT,
    val direction: PlaceSortDirection = PlaceSortDirection.DESC,
)

data class PlaceListLocation(
    val latitude: Double,
    val longitude: Double,
)

data class PlaceListUiModel(
    val place: PlaceEntity,
    val totalTimeMs: Long,
    val visitCount: Int,
    val lastVisitAt: Long?,
    val activeStartedAt: Long?,
    val distanceMeters: Long?,
)

object PlaceListUiMapper {
    fun map(
        places: List<PlaceEntity>,
        stats: List<PlaceStatsUi>,
        visits: List<VisitUiModel>,
        sort: PlaceSortState = PlaceSortState(),
        currentLocation: PlaceListLocation? = null,
    ): List<PlaceListUiModel> {
        val statsByPlace = stats.associateBy { it.place.uuid }
        val visitsByPlace = visits.groupBy { it.placeId }
        return places.map { place ->
            val placeVisits = visitsByPlace[place.uuid].orEmpty()
            PlaceListUiModel(
                place = place,
                totalTimeMs = statsByPlace[place.uuid]?.totalTimeAtPlaceMs ?: 0L,
                visitCount = placeVisits.count { it.startedAt != null },
                lastVisitAt = placeVisits.mapNotNull { it.startedAt }.maxOrNull(),
                activeStartedAt = placeVisits.firstOrNull { it.isActive }?.startedAt,
                distanceMeters = distanceMeters(currentLocation, place),
            )
        }.sortedWith(comparator(sort))
    }

    private fun comparator(sort: PlaceSortState): Comparator<PlaceListUiModel> {
        val base = compareByDescending<PlaceListUiModel> { it.activeStartedAt != null }
        val criterion = when (sort.criterion) {
            PlaceSortCriterion.DISTANCE -> knownNullableComparator<PlaceListUiModel, Long>(sort.direction) { it.distanceMeters }
            PlaceSortCriterion.LAST_VISIT -> knownNullableComparator(sort.direction) { it.lastVisitAt }
            PlaceSortCriterion.TOTAL_TIME -> valueComparator(sort.direction) { it.totalTimeMs }
            PlaceSortCriterion.VISIT_COUNT -> valueComparator(sort.direction) { it.visitCount.toLong() }
        }
        return base.then(criterion).thenBy { it.place.nickname.lowercase() }.thenBy { it.place.uuid }
    }

    private fun <T> valueComparator(
        direction: PlaceSortDirection,
        value: (T) -> Long,
    ): Comparator<T> =
        if (direction == PlaceSortDirection.ASC) compareBy(value) else compareByDescending(value)

    private fun <T, R : Comparable<R>> knownNullableComparator(
        direction: PlaceSortDirection,
        value: (T) -> R?,
    ): Comparator<T> = Comparator { left, right ->
        val l = value(left)
        val r = value(right)
        when {
            l == null && r == null -> 0
            l == null -> 1
            r == null -> -1
            direction == PlaceSortDirection.ASC -> l.compareTo(r)
            else -> r.compareTo(l)
        }
    }

    private fun distanceMeters(currentLocation: PlaceListLocation?, place: PlaceEntity): Long? {
        val lat = place.lat ?: return null
        val lon = place.lon ?: return null
        val location = currentLocation ?: return null
        return GeoSearch.distanceMeters(location.latitude, location.longitude, lat, lon).roundToLong()
    }
}
