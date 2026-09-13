package com.wordpulse.app.data

import androidx.room.ColumnInfo

data class TypingPerformanceRow(
    @ColumnInfo(name = "id")
    val id: Long,
    @ColumnInfo(name = "original_word")
    val originalWord: String,
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
    @ColumnInfo(name = "median_inter_key_interval_ms")
    val medianInterKeyIntervalMs: Double?,
    @ColumnInfo(name = "p95_inter_key_interval_ms")
    val p95InterKeyIntervalMs: Double?,
    @ColumnInfo(name = "inter_key_interval_cv")
    val interKeyIntervalCoefficientOfVariation: Double?,
    @ColumnInfo(name = "micro_pause_count")
    val microPauseCount: Int?,
    @ColumnInfo(name = "last_edit_to_submit_ms")
    val lastEditToSubmitMs: Long?,
)
