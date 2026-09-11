package com.gernalix.personalhub.core.hubcontext

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
