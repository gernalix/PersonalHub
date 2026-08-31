package com.wordpulse.app.data

import androidx.room.ColumnInfo
import com.wordpulse.app.domain.TypingMetrics
import com.wordpulse.app.domain.TypingPerformanceSample

data class TypingPerformanceRow(
    @ColumnInfo(name = "id")
    val id: Long,
    @ColumnInfo(name = "typing_started_at_utc_ms")
    val typingStartedAtUtcMs: Long?,
    @ColumnInfo(name = "submitted_at_utc_ms")
    val submittedAtUtcMs: Long?,
    @ColumnInfo(name = "typing_duration_ms")
    val typingDurationMs: Long?,
    @ColumnInfo(name = "final_character_count")
    val finalCharacterCount: Int?,
    @ColumnInfo(name = "inserted_character_count")
    val insertedCharacterCount: Int?,
    @ColumnInfo(name = "deleted_character_count")
    val deletedCharacterCount: Int?,
    @ColumnInfo(name = "replacement_count")
    val replacementCount: Int?,
    @ColumnInfo(name = "correction_action_count")
    val correctionActionCount: Int?,
    @ColumnInfo(name = "longest_inter_key_pause_ms")
    val longestInterKeyPauseMs: Long?,
    @ColumnInfo(name = "mean_inter_key_interval_ms")
    val meanInterKeyIntervalMs: Double?,
    @ColumnInfo(name = "inter_key_interval_variability_ms")
    val interKeyIntervalVariabilityMs: Double?,
    @ColumnInfo(name = "invalid_input_attempt_count")
    val invalidInputAttemptCount: Int?,
) {
    fun toPerformanceSample(): TypingPerformanceSample? {
        val submittedAt = submittedAtUtcMs ?: return null
        val finalCount = finalCharacterCount ?: return null
        val metrics = TypingMetrics(
            typingStartedAtUtcMs = typingStartedAtUtcMs,
            submittedAtUtcMs = submittedAt,
            typingDurationMs = typingDurationMs,
            finalCharacterCount = finalCount,
            insertedCharacterCount = insertedCharacterCount ?: return null,
            deletedCharacterCount = deletedCharacterCount ?: return null,
            replacementCount = replacementCount ?: return null,
            correctionActionCount = correctionActionCount ?: return null,
            longestInterKeyPauseMs = longestInterKeyPauseMs,
            meanInterKeyIntervalMs = meanInterKeyIntervalMs,
            interKeyIntervalVariabilityMs = interKeyIntervalVariabilityMs,
            invalidInputAttemptCount = invalidInputAttemptCount ?: return null,
        )
        return TypingPerformanceSample.fromMetrics(id, metrics)
    }
}
