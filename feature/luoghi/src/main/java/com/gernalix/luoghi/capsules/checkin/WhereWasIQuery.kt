package com.gernalix.luoghi.capsules.checkin

import com.gernalix.luoghi.capsules.visits.VisitUiModel
import com.gernalix.luoghi.data.PlaceEntity

sealed interface WhereWasIResult {
    data class Inside(val place: PlaceEntity, val visit: VisitUiModel) : WhereWasIResult
    data class Overlapping(val places: List<PlaceEntity>) : WhereWasIResult
    data class Between(val previous: PlaceEntity, val next: PlaceEntity) : WhereWasIResult
    data class OnlyPrevious(val previous: PlaceEntity) : WhereWasIResult
    data class OnlyNext(val next: PlaceEntity) : WhereWasIResult
    data object NoData : WhereWasIResult
}

object WhereWasIQuery {
    fun at(visits: List<VisitUiModel>, places: List<PlaceEntity>, instantMs: Long, observedAtMs: Long? = null): WhereWasIResult {
        val placesById = places.associateBy { it.uuid }
        val ordered = visits
            .filter { it.startedAt != null && it.placeId in placesById }
            .sortedWith(compareBy<VisitUiModel> { it.startedAt }.thenBy { it.stableId })
        // Only the current active visit has an open interval, bounded by the observed
        // duration. An old check-in without checkout is not proof of staying forever.
        val inside = ordered.filter { visit ->
            val start = visit.startedAt ?: return@filter false
            val end = visit.endedAt ?: if (visit.isActive) observedAtMs ?: (start + visit.durationMs) else return@filter false
            instantMs in start..end
        }.let { matches ->
            if (matches.any { it.startedAt == instantMs }) matches.filterNot { it.endedAt == instantMs && it.startedAt != instantMs }
            else matches
        }
        val insidePlaces = inside.mapNotNull { placesById[it.placeId] }.distinctBy { it.uuid }
        if (insidePlaces.size > 1) return WhereWasIResult.Overlapping(insidePlaces)
        inside.lastOrNull()?.let { return WhereWasIResult.Inside(placesById.getValue(it.placeId), it) }
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
