package com.gernalix.luoghi.capsules.stats

import com.gernalix.luoghi.capsules.checkin.PlaceEventTypes
import com.gernalix.luoghi.data.GlobalStatsStateEntity
import com.gernalix.luoghi.data.PlaceEntity
import com.gernalix.luoghi.data.PlaceEventEntity
import com.gernalix.luoghi.data.PlaceRepository
import kotlinx.coroutines.flow.Flow
import com.gernalix.luoghi.capsules.visits.VisitUiModel

data class PlaceStatsUi(
    val place: PlaceEntity,
    val totalTimeAtPlaceMs: Long,
    val ratioGlobalPct: Double?,
    val ratioPlaceLifetimePct: Double?,
)

data class GlobalStatsUi(
    val totalTrackedTimeMs: Long,
    val ratioTrackedGlobalPct: Double?,
    val totalCheckIns: Int,
    val firstCheckInAtGlobal: Long?,
)

data class StatsSnapshot(
    val places: List<PlaceStatsUi> = emptyList(),
    val global: GlobalStatsUi = GlobalStatsUi(
        totalTrackedTimeMs = 0L,
        ratioTrackedGlobalPct = null,
        totalCheckIns = 0,
        firstCheckInAtGlobal = null,
    ),
)

class StatsCapsule(
    private val repository: PlaceRepository,
) {
    val globalStatsState: Flow<GlobalStatsStateEntity?> = repository.globalStatsState

    fun snapshot(
        places: List<PlaceEntity>,
        events: List<PlaceEventEntity>,
        globalState: GlobalStatsStateEntity?,
        nowMs: Long,
        visits: List<VisitUiModel>? = null,
    ): StatsSnapshot = visits?.let { calculateFromVisits(places, it, globalState?.firstCheckInAtGlobal, nowMs) }
        ?: calculate(places, events, globalState?.firstCheckInAtGlobal, nowMs)

    companion object {
        fun calculateFromVisits(places: List<PlaceEntity>, visits: List<VisitUiModel>, firstCheckInAtGlobal: Long?, nowMs: Long): StatsSnapshot {
            val totalByPlace = visits.groupBy { it.placeId }.mapValues { (_, rows) -> rows.sumOf { it.durationMs } }
            val globalTotalMs = totalByPlace.values.sum()
            return StatsSnapshot(
                places.map { place ->
                    val total = totalByPlace[place.uuid] ?: 0L
                    PlaceStatsUi(place, total, percent(total, firstCheckInAtGlobal, nowMs), percent(total, place.firstCheckInAtPlace, nowMs))
                },
                GlobalStatsUi(globalTotalMs, percent(globalTotalMs, firstCheckInAtGlobal, nowMs), visits.size, firstCheckInAtGlobal),
            )
        }
        fun calculate(
            places: List<PlaceEntity>,
            events: List<PlaceEventEntity>,
            firstCheckInAtGlobal: Long?,
            nowMs: Long,
        ): StatsSnapshot {
            val totalByPlace = totalTrackedTimeByPlace(events, nowMs)
            val globalTotalMs = totalByPlace.values.sum()
            val totalCheckIns = events.count { it.eventType == PlaceEventTypes.CHECK_IN }
            val placeStats = places.map { place ->
                val totalMs = totalByPlace[place.uuid] ?: 0L
                PlaceStatsUi(
                    place = place,
                    totalTimeAtPlaceMs = totalMs,
                    ratioGlobalPct = percent(totalMs, firstCheckInAtGlobal, nowMs),
                    ratioPlaceLifetimePct = percent(totalMs, place.firstCheckInAtPlace, nowMs),
                )
            }
            return StatsSnapshot(
                places = placeStats,
                global = GlobalStatsUi(
                    totalTrackedTimeMs = globalTotalMs,
                    ratioTrackedGlobalPct = percent(globalTotalMs, firstCheckInAtGlobal, nowMs),
                    totalCheckIns = totalCheckIns,
                    firstCheckInAtGlobal = firstCheckInAtGlobal,
                ),
            )
        }

        private fun totalTrackedTimeByPlace(
            events: List<PlaceEventEntity>,
            nowMs: Long,
        ): Map<String, Long> {
            val totals = linkedMapOf<String, Long>()
            val openCheckIns = linkedMapOf<String, PlaceEventEntity>()
            events.sortedWith(compareBy<PlaceEventEntity> { it.timestamp }.thenBy { it.id }).forEach { event ->
                when (event.eventType) {
                    PlaceEventTypes.CHECK_IN -> openCheckIns[event.placeId] = event
                    PlaceEventTypes.CHECK_OUT -> {
                        val checkIn = openCheckIns.remove(event.placeId) ?: return@forEach
                        val durationMs = event.timestamp - checkIn.timestamp
                        if (durationMs > 0L) {
                            totals[event.placeId] = (totals[event.placeId] ?: 0L) + durationMs
                        }
                    }
                }
            }
            openCheckIns.values.forEach { checkIn ->
                val durationMs = (nowMs - checkIn.timestamp).coerceAtLeast(0L)
                totals[checkIn.placeId] = (totals[checkIn.placeId] ?: 0L) + durationMs
            }
            return totals
        }

        private fun percent(totalMs: Long, startMs: Long?, nowMs: Long): Double? {
            val start = startMs ?: return null
            val denominatorMs = nowMs - start
            if (denominatorMs <= 0L) return null
            return totalMs.toDouble().coerceAtLeast(0.0) * 100.0 / denominatorMs.toDouble()
        }
    }
}
