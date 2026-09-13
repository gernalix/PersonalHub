package com.wordpulse.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "word_entries",
    foreignKeys = [
        ForeignKey(
            entity = WordSession::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["normalized_word"]),
        Index(value = ["session_id"]),
        Index(value = ["created_at_utc_ms"]),
        Index(value = ["normalized_word", "created_at_utc_ms"]),
    ],
)
data class WordEntry(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "original_word")
    val originalWord: String,
    @ColumnInfo(name = "normalized_word")
    val normalizedWord: String,
    @ColumnInfo(name = "created_at_utc_ms")
    val createdAtUtcMs: Long,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "capture_policy_version")
    val capturePolicyVersion: String? = null,
    @ColumnInfo(name = "typing_started_at_utc_ms")
    val typingStartedAtUtcMs: Long? = null,
    @ColumnInfo(name = "submitted_at_utc_ms")
    val submittedAtUtcMs: Long? = null,
    @ColumnInfo(name = "typing_duration_ms")
    val typingDurationMs: Long? = null,
    @ColumnInfo(name = "final_character_count")
    val finalCharacterCount: Int? = null,
    @ColumnInfo(name = "inserted_character_count")
    val insertedCharacterCount: Int? = null,
    @ColumnInfo(name = "deleted_character_count")
    val deletedCharacterCount: Int? = null,
    @ColumnInfo(name = "replacement_count")
    val replacementCount: Int? = null,
    @ColumnInfo(name = "correction_action_count")
    val correctionActionCount: Int? = null,
    @ColumnInfo(name = "longest_inter_key_pause_ms")
    val longestInterKeyPauseMs: Long? = null,
    @ColumnInfo(name = "mean_inter_key_interval_ms")
    val meanInterKeyIntervalMs: Double? = null,
    @ColumnInfo(name = "inter_key_interval_variability_ms")
    val interKeyIntervalVariabilityMs: Double? = null,
    @ColumnInfo(name = "invalid_input_attempt_count")
    val invalidInputAttemptCount: Int? = null,
    @ColumnInfo(name = "median_inter_key_interval_ms")
    val medianInterKeyIntervalMs: Double? = null,
    @ColumnInfo(name = "p95_inter_key_interval_ms")
    val p95InterKeyIntervalMs: Double? = null,
    @ColumnInfo(name = "inter_key_interval_cv")
    val interKeyIntervalCoefficientOfVariation: Double? = null,
    @ColumnInfo(name = "micro_pause_count")
    val microPauseCount: Int? = null,
    @ColumnInfo(name = "last_edit_to_submit_ms")
    val lastEditToSubmitMs: Long? = null,
    @ColumnInfo(name = "fatigue_score")
    val fatigueScore: Int? = null,
)
