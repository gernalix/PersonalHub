package com.gernalix.luoghi.capsules.visits

enum class VisitPairingStatus {
    PAIRED,
    ACTIVE,
    INCOMPLETE,
    ORPHAN_CHECK_OUT,
    ANOMALOUS,
}

enum class VisitAnomaly {
    MISSING_CHECK_IN,
    MISSING_CHECK_OUT,
    MULTIPLE_CHECK_INS,
    MULTIPLE_CHECK_OUTS,
    NON_POSITIVE_DURATION,
    OVERLAP,
    MULTIPLE_OPEN_VISITS,
    PLACE_MISMATCH,
    UNUSUAL_DURATION,
    UNKNOWN_EVENT_TYPE,
}

data class VisitUiModel(
    val stableId: String,
    val sessionUuid: String?,
    val placeId: String,
    val placeName: String,
    val address: String?,
    val checkInEventId: Long?,
    val checkOutEventId: Long?,
    val checkInEventUuid: String?,
    val checkOutEventUuid: String?,
    val startedAt: Long?,
    val endedAt: Long?,
    val durationMs: Long,
    val isActive: Boolean,
    val pairingStatus: VisitPairingStatus,
    val anomalies: Set<VisitAnomaly>,
    val underlyingEventIds: List<Long>,
    val relatedPeople: List<String> = emptyList(),
) {
    val sortTimestamp: Long = startedAt ?: endedAt ?: Long.MIN_VALUE
    val isAnomalous: Boolean = anomalies.isNotEmpty()
}
