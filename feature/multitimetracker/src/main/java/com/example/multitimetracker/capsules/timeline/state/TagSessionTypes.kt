// v471
// v348
package com.example.multitimetracker.capsules.timeline.state

/**
 * Shared UI types used by multiple screens.
 *
 */
enum class TaggedSessionRecordSortKey { DATE, DURATION, TASK }

data class TaggedSessionRecordUi(
    val sessionTitle: String,
    val startTs: Long,
    /**
     * Null when the session is running.
     *
     * IMPORTANT: Keep this nullable so UI grouping/sorting can be cached without depending on nowMs.
     */
    val endTs: Long?,
    val isRunning: Boolean
)



