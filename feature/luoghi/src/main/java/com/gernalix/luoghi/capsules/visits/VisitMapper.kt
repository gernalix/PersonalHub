package com.gernalix.luoghi.capsules.visits

import com.gernalix.luoghi.capsules.checkin.PlaceEventTypes
import com.gernalix.luoghi.data.PlaceEntity
import com.gernalix.luoghi.data.PlaceEventEntity

/**
 * Builds immutable visit presentation models before Compose renders them.
 * Stable session UUIDs are authoritative. Only legacy rows without one use the
 * deterministic single-global-visit fallback used by Luoghi's check-in flow.
 */
object VisitMapper {
    private const val UNUSUAL_DURATION_MS = 7L * 24L * 60L * 60L * 1_000L

    fun map(
        events: List<PlaceEventEntity>,
        places: List<PlaceEntity>,
        nowMs: Long,
    ): List<VisitUiModel> {
        val placesById = places.associateBy { it.uuid }
        val explicitGroups = events
            .filter { it.sessionUuid.isNotBlank() }
            .groupBy { it.sessionUuid }
            .toSortedMap()
            .map { (sessionUuid, sessionEvents) ->
                mapGroup(
                    stableId = "session:$sessionUuid",
                    sessionUuid = sessionUuid,
                    events = sessionEvents,
                    placesById = placesById,
                    nowMs = nowMs,
                )
            }
        val legacyVisits = mapLegacyEvents(
            events = events.filter { it.sessionUuid.isBlank() },
            placesById = placesById,
            nowMs = nowMs,
        )

        return markGlobalAnomalies(explicitGroups + legacyVisits, nowMs)
            .sortedWith(
                compareByDescending<VisitUiModel> { it.isActive }
                    .thenByDescending { it.sortTimestamp }
                    .thenByDescending { it.stableId }
            )
    }

    private fun mapGroup(
        stableId: String,
        sessionUuid: String?,
        events: List<PlaceEventEntity>,
        placesById: Map<String, PlaceEntity>,
        nowMs: Long,
    ): VisitUiModel {
        val ordered = events.sortedWith(compareBy<PlaceEventEntity> { it.timestamp }.thenBy { it.id }.thenBy { it.eventUuid })
        val checkIns = ordered.filter { it.eventType == PlaceEventTypes.CHECK_IN }
        val checkOuts = ordered.filter { it.eventType == PlaceEventTypes.CHECK_OUT }
        val checkIn = checkIns.firstOrNull()
        val checkOut = when {
            checkIn == null -> checkOuts.firstOrNull()
            else -> checkOuts.firstOrNull { it.timestamp > checkIn.timestamp }
                ?: checkOuts.firstOrNull()
        }
        val placeId = checkIn?.placeId ?: checkOut?.placeId ?: ordered.firstOrNull()?.placeId.orEmpty()
        val place = placesById[placeId]
        val endedAt = checkOut?.timestamp
        val durationMs = when {
            checkIn == null -> 0L
            endedAt == null -> (nowMs - checkIn.timestamp).coerceAtLeast(0L)
            else -> (endedAt - checkIn.timestamp).coerceAtLeast(0L)
        }
        val anomalies = buildSet {
            if (checkIn == null) add(VisitAnomaly.MISSING_CHECK_IN)
            if (checkIn != null && checkOut == null) add(VisitAnomaly.MISSING_CHECK_OUT)
            if (checkIns.size > 1) add(VisitAnomaly.MULTIPLE_CHECK_INS)
            if (checkOuts.size > 1) add(VisitAnomaly.MULTIPLE_CHECK_OUTS)
            if (checkIn != null && checkOut != null && checkOut.timestamp <= checkIn.timestamp) {
                add(VisitAnomaly.NON_POSITIVE_DURATION)
            }
            if (ordered.map { it.placeId }.distinct().size > 1) add(VisitAnomaly.PLACE_MISMATCH)
            if (durationMs > UNUSUAL_DURATION_MS) add(VisitAnomaly.UNUSUAL_DURATION)
            if (ordered.any { it.eventType != PlaceEventTypes.CHECK_IN && it.eventType != PlaceEventTypes.CHECK_OUT }) {
                add(VisitAnomaly.UNKNOWN_EVENT_TYPE)
            }
        }
        val isOpenCandidate = checkIn != null && checkOut == null
        return VisitUiModel(
            stableId = stableId,
            sessionUuid = sessionUuid,
            placeId = placeId,
            placeName = place?.nickname?.trim().orEmpty(),
            address = place?.address?.trim()?.takeIf { it.isNotEmpty() },
            checkInEventId = checkIn?.id,
            checkOutEventId = checkOut?.id,
            checkInEventUuid = checkIn?.eventUuid,
            checkOutEventUuid = checkOut?.eventUuid,
            startedAt = checkIn?.timestamp,
            endedAt = endedAt,
            durationMs = durationMs,
            isActive = isOpenCandidate,
            pairingStatus = when {
                checkIn == null -> VisitPairingStatus.ORPHAN_CHECK_OUT
                anomalies.any { it != VisitAnomaly.MISSING_CHECK_OUT } -> VisitPairingStatus.ANOMALOUS
                isOpenCandidate -> VisitPairingStatus.ACTIVE
                else -> VisitPairingStatus.PAIRED
            },
            anomalies = anomalies,
            underlyingEventIds = ordered.map { it.id },
        )
    }

    private fun mapLegacyEvents(
        events: List<PlaceEventEntity>,
        placesById: Map<String, PlaceEntity>,
        nowMs: Long,
    ): List<VisitUiModel> {
        if (events.isEmpty()) return emptyList()
        val ordered = events.sortedWith(compareBy<PlaceEventEntity> { it.timestamp }.thenBy { it.id }.thenBy { it.eventUuid })
        val visits = mutableListOf<VisitUiModel>()
        val openEvents = mutableListOf<PlaceEventEntity>()
        for (event in ordered) {
            when (event.eventType) {
                PlaceEventTypes.CHECK_IN -> {
                    openEvents += event
                }
                PlaceEventTypes.CHECK_OUT -> {
                    val candidate = openEvents.firstOrNull()
                    if (candidate != null && candidate.placeId == event.placeId && event.timestamp > candidate.timestamp) {
                        visits += mapGroup(
                            stableId = "legacy:${candidate.eventUuid.ifBlank { candidate.id.toString() }}",
                            sessionUuid = null,
                            events = openEvents + event,
                            placesById = placesById,
                            nowMs = nowMs,
                        )
                        openEvents.clear()
                    } else {
                        visits += mapGroup(
                            stableId = "legacy-orphan:${event.eventUuid.ifBlank { event.id.toString() }}",
                            sessionUuid = null,
                            events = listOf(event),
                            placesById = placesById,
                            nowMs = nowMs,
                        )
                    }
                }
                else -> visits += mapGroup(
                    stableId = "legacy-unknown:${event.eventUuid.ifBlank { event.id.toString() }}",
                    sessionUuid = null,
                    events = listOf(event),
                    placesById = placesById,
                    nowMs = nowMs,
                )
            }
        }
        openEvents.firstOrNull()?.let { event ->
            visits += mapGroup(
                stableId = "legacy:${event.eventUuid.ifBlank { event.id.toString() }}",
                sessionUuid = null,
                events = openEvents,
                placesById = placesById,
                nowMs = nowMs,
            )
        }
        return visits
    }

    private fun markGlobalAnomalies(visits: List<VisitUiModel>, nowMs: Long): List<VisitUiModel> {
        if (visits.isEmpty()) return visits
        val mutable = visits.associateBy { it.stableId }.toMutableMap()
        val timed = visits
            .filter { it.startedAt != null }
            .sortedWith(compareBy<VisitUiModel> { it.startedAt }.thenBy { it.stableId })
        var latestEnd = Long.MIN_VALUE
        var latestEndVisitId: String? = null
        timed.forEach { visit ->
            val start = visit.startedAt ?: return@forEach
            val end = visit.endedAt ?: nowMs
            if (start < latestEnd) {
                mutable[visit.stableId] = mutable.getValue(visit.stableId).withAnomaly(VisitAnomaly.OVERLAP)
                latestEndVisitId?.let { overlappingId ->
                    mutable[overlappingId] = mutable.getValue(overlappingId).withAnomaly(VisitAnomaly.OVERLAP)
                }
            }
            if (end > latestEnd) {
                latestEnd = end
                latestEndVisitId = visit.stableId
            }
        }

        val open = mutable.values.filter { it.endedAt == null && it.startedAt != null }
        if (open.size > 1) {
            open.forEach { visit ->
                mutable[visit.stableId] = mutable.getValue(visit.stableId)
                    .withAnomaly(VisitAnomaly.MULTIPLE_OPEN_VISITS)
                    .copy(pairingStatus = VisitPairingStatus.ANOMALOUS)
            }
        }

        val activeStableId = open.maxWithOrNull(compareBy<VisitUiModel> { it.startedAt }.thenBy { it.stableId })?.stableId
        return visits.map { visit ->
            val updated = mutable.getValue(visit.stableId)
            updated.copy(
                isActive = updated.stableId == activeStableId,
                pairingStatus = when {
                    updated.stableId == activeStableId && updated.anomalies.all { it == VisitAnomaly.MISSING_CHECK_OUT } -> VisitPairingStatus.ACTIVE
                    updated.anomalies.isNotEmpty() -> if (VisitAnomaly.MISSING_CHECK_IN in updated.anomalies) {
                        VisitPairingStatus.ORPHAN_CHECK_OUT
                    } else {
                        VisitPairingStatus.ANOMALOUS
                    }
                    else -> updated.pairingStatus
                },
            )
        }
    }

    private fun VisitUiModel.withAnomaly(anomaly: VisitAnomaly): VisitUiModel =
        copy(anomalies = anomalies + anomaly, pairingStatus = VisitPairingStatus.ANOMALOUS)
}
