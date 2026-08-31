package com.example.multitimetracker.core.session

/**
 * Pure, reusable Timeline stats computation.
 *
 * Why this exists:
 * - keeps UI light
 * - centralizes the UNION-duration rule (no double counting on overlaps)
 */
object TimelineStatsCore {

    data class TimelineStats(
        val totalMs: Long,
        val avgSessionMs: Long,
        val periodMs: Long,
        val elapsedPeriodMs: Long
    )

    /**
     * @param rawIntervals list of [startMs,endMs] pairs (endMs must be >= startMs; running sessions should already
     * have a concrete endMs for the purpose of stats).
     * @param lowerMs inclusive
     * @param upperMsExclusive exclusive
     */
    fun compute(
        rawIntervals: List<Pair<Long, Long>>,
        lowerMs: Long,
        upperMsExclusive: Long,
        nowMs: Long
    ): TimelineStats {
        val periodMs = (upperMsExclusive - lowerMs).coerceAtLeast(0L)

        // For "% of elapsed" we consider the portion of the selected period that has actually elapsed until now.
        val elapsedEndMs = minOf(upperMsExclusive, nowMs)
        val elapsedPeriodMs = (elapsedEndMs - lowerMs).coerceIn(0L, periodMs)

        // Clip to [lowerMs, upperMsExclusive)
        val clipped = rawIntervals.mapNotNull { (s0, e0) ->
            val s = maxOf(s0, lowerMs)
            val e = minOf(e0, upperMsExclusive)
            if (e > s) (s to e) else null
        }

        // IMPORTANT: total must be the UNION of time intervals, not a raw sum.
        val totalUnionMs = unionDurationMs(clipped)
        val avgSessionMs = if (clipped.isNotEmpty()) {
            (clipped.sumOf { (a, b) -> (b - a).coerceAtLeast(0L) } / clipped.size)
        } else 0L

        return TimelineStats(
            totalMs = totalUnionMs,
            avgSessionMs = avgSessionMs,
            periodMs = periodMs,
            elapsedPeriodMs = elapsedPeriodMs
        )
    }

    private fun unionDurationMs(intervals: List<Pair<Long, Long>>): Long {
        if (intervals.isEmpty()) return 0L
        val sorted = intervals
            .map { (a, b) -> if (b >= a) (a to b) else (b to a) }
            .sortedBy { it.first }

        var total = 0L
        var curStart = sorted[0].first
        var curEnd = sorted[0].second

        for (i in 1 until sorted.size) {
            val (s, e) = sorted[i]
            if (s <= curEnd) {
                curEnd = maxOf(curEnd, e)
            } else {
                total += (curEnd - curStart).coerceAtLeast(0L)
                curStart = s
                curEnd = e
            }
        }
        total += (curEnd - curStart).coerceAtLeast(0L)
        return total
    }
}
