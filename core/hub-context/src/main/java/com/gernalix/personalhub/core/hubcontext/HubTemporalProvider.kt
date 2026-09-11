package com.gernalix.personalhub.core.hubcontext

import com.gernalix.personalhub.contracts.database.HubEntitySummary

data class HubTemporalQuery(
    val fromMs: Long,
    val toMs: Long,
    val limit: Int = 100,
    val cursor: String? = null,
) {
    init {
        require(fromMs < toMs)
        require(limit in 1..200)
    }
}

data class HubTemporalPage(
    val records: List<HubTemporalRecord>,
    val nextCursor: String? = null,
)

data class HubTemporalCursor(val sortMs: Long, val stableId: String)

private const val HUB_TEMPORAL_CURSOR_SEPARATOR = '\u001F'

fun encodeHubTemporalCursor(sortMs: Long, stableId: String): String {
    require(stableId.isNotBlank())
    return buildString {
        append(sortMs)
        append(HUB_TEMPORAL_CURSOR_SEPARATOR)
        append(stableId)
    }
}

fun decodeHubTemporalCursor(cursor: String?): HubTemporalCursor? {
    if (cursor.isNullOrBlank()) return null
    val separator = cursor.indexOf(HUB_TEMPORAL_CURSOR_SEPARATOR)
    if (separator <= 0 || separator >= cursor.lastIndex) return null
    val sortMs = cursor.substring(0, separator).toLongOrNull() ?: return null
    val stableId = cursor.substring(separator + 1)
    if (stableId.isBlank()) return null
    return HubTemporalCursor(sortMs, stableId)
}

/**
 * Module-owned bounded temporal read API. Implementations must filter in their data layer and must
 * not materialize an entire table merely to apply [HubTemporalQuery] in UI code.
 */
interface HubTemporalProvider {
    val moduleId: String
    suspend fun queryTemporal(query: HubTemporalQuery): HubTemporalPage
}

data class HubPlaceSuggestions(val preselect: HubEntitySummary?, val candidates: List<HubEntitySummary>)

interface HubPlaceSuggestionProvider {
    suspend fun suggestPlaces(latitude: Double?, longitude: Double?, limit: Int = 5): HubPlaceSuggestions
}
