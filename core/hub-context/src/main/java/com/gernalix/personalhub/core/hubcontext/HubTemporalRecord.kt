package com.gernalix.personalhub.core.hubcontext

enum class HubTemporalKind { POINT, INTERVAL }

data class HubTemporalRecord(
    val moduleId: String,
    val source: String,
    val stableId: String,
    val kind: HubTemporalKind,
    val startMs: Long,
    val endMs: Long? = null,
    val title: String,
    val subtitle: String? = null,
) {
    init {
        require(moduleId.isNotBlank())
        require(source.isNotBlank())
        require(stableId.isNotBlank())
        require(title.isNotBlank())
        if (kind == HubTemporalKind.POINT) require(endMs == null)
        if (endMs != null) require(endMs > startMs)
    }

    fun overlaps(fromMs: Long, toMs: Long): Boolean {
        require(fromMs < toMs)
        return when (kind) {
            HubTemporalKind.POINT -> startMs >= fromMs && startMs < toMs
            HubTemporalKind.INTERVAL -> startMs < toMs && (endMs == null || endMs > fromMs)
        }
    }
}

/**
 * Merges already-bounded provider results. Providers remain responsible for paged/bounded DB queries;
 * this helper must not be used as a substitute for database-side time filtering.
 */
fun mergeTemporalSlices(
    slices: Iterable<Iterable<HubTemporalRecord>>,
    fromMs: Long,
    toMs: Long,
    moduleFilter: Set<String> = emptySet(),
): List<HubTemporalRecord> {
    require(fromMs < toMs)
    return slices
        .asSequence()
        .flatten()
        .filter { moduleFilter.isEmpty() || it.moduleId in moduleFilter }
        .filter { it.overlaps(fromMs, toMs) }
        .sortedWith(
            compareByDescending<HubTemporalRecord> { it.startMs }
                .thenBy { it.moduleId }
                .thenBy { it.source }
                .thenBy { it.stableId },
        )
        .toList()
}
