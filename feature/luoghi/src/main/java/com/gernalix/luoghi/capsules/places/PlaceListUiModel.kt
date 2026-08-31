package com.gernalix.luoghi.capsules.places

import com.gernalix.luoghi.capsules.stats.PlaceStatsUi
import com.gernalix.luoghi.capsules.visits.VisitUiModel
import com.gernalix.luoghi.data.PlaceEntity

data class PlaceListUiModel(
    val place: PlaceEntity,
    val totalTimeMs: Long,
    val visitCount: Int,
    val lastVisitAt: Long?,
    val activeStartedAt: Long?,
)

object PlaceListUiMapper {
    fun map(
        places: List<PlaceEntity>,
        stats: List<PlaceStatsUi>,
        visits: List<VisitUiModel>,
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
            )
        }.sortedWith(
            compareByDescending<PlaceListUiModel> { it.activeStartedAt != null }
                .thenByDescending { it.lastVisitAt ?: it.place.updatedAt }
                .thenBy { it.place.nickname.lowercase() }
        )
    }
}
