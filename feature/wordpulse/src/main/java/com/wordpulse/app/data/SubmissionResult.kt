package com.wordpulse.app.data

import com.wordpulse.app.domain.TypingMetrics

data class SubmissionResult(
    val insertedEntryId: Long,
    val originalWord: String,
    val normalizedWord: String,
    val sessionId: String,
    val submittedAtUtcMs: Long,
    val previousOccurrences: Int,
    val totalOccurrences: Int,
    val previousOccurrenceUtcMs: Long?,
    val typingMetrics: TypingMetrics?,
) {
    val wasDuplicate: Boolean = previousOccurrences > 0
}
