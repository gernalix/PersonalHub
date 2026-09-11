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
