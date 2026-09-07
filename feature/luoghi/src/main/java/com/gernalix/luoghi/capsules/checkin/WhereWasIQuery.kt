package com.gernalix.luoghi.capsules.checkin

import com.gernalix.luoghi.capsules.visits.VisitUiModel
import com.gernalix.luoghi.data.PlaceEntity

sealed interface WhereWasIResult {
    data class Inside(val place: PlaceEntity, val visit: VisitUiModel) : WhereWasIResult
    data class Between(val previous: PlaceEntity, val next: PlaceEntity) : WhereWasIResult
    data class OnlyPrevious(val previous: PlaceEntity) : WhereWasIResult
    data class OnlyNext(val next: PlaceEntity) : WhereWasIResult
    data object NoData : WhereWasIResult
}

object WhereWasIQuery {
    fun at(visits: List<VisitUiModel>, places: List<PlaceEntity>, instantMs: Long): WhereWasIResult {
        val placesById = places.associateBy { it.uuid }
        val ordered = visits
            .filter { it.startedAt != null }
            .sortedWith(compareBy<VisitUiModel> { it.startedAt }.thenBy { it.stableId })
        ordered.firstOrNull { visit ->
            val start = visit.startedAt ?: return@firstOrNull false
            val end = visit.endedAt ?: Long.MAX_VALUE
            instantMs in start..end
        }?.let { visit ->
            placesById[visit.placeId]?.let { return WhereWasIResult.Inside(it, visit) }
        }
        val previous = ordered
            .filter { (it.endedAt ?: it.startedAt ?: Long.MIN_VALUE) < instantMs }
            .maxByOrNull { it.endedAt ?: it.startedAt ?: Long.MIN_VALUE }
            ?.let { placesById[it.placeId] }
        val next = ordered
            .filter { (it.startedAt ?: Long.MAX_VALUE) > instantMs }
            .minByOrNull { it.startedAt ?: Long.MAX_VALUE }
            ?.let { placesById[it.placeId] }
        return when {
            previous != null && next != null -> WhereWasIResult.Between(previous, next)
            previous != null -> WhereWasIResult.OnlyPrevious(previous)
            next != null -> WhereWasIResult.OnlyNext(next)
            else -> WhereWasIResult.NoData
        }
    }
}
