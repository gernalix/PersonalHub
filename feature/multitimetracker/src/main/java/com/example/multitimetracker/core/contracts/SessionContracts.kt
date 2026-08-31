// v471
package com.example.multitimetracker.core.contracts

/**
 * Core contract DTOs used across capsules/layers.
 */
data class ClosedSessionRecord(
    val sessionId: Long,
    val sessionTitle: String,
    val startTs: Long,
    val endTs: Long
)

data class TaggedSessionRecord(
    val tagId: Long,
    val tagName: String,
    val sessionId: Long,
    val sessionTitle: String,
    val startTs: Long,
    val endTs: Long
)
