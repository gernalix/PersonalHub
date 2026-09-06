package com.wordpulse.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "correction_events",
    indices = [
        Index(value = ["original_word_entry_id"]),
        Index(value = ["session_id"]),
        Index(value = ["corrected_at_utc_ms"]),
    ],
)
data class CorrectionEvent(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "original_word_entry_id")
    val originalWordEntryId: Long,
    @ColumnInfo(name = "original_word")
    val originalWord: String,
    @ColumnInfo(name = "normalized_word")
    val normalizedWord: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "submitted_at_utc_ms")
    val submittedAtUtcMs: Long,
    @ColumnInfo(name = "corrected_at_utc_ms")
    val correctedAtUtcMs: Long,
    @ColumnInfo(name = "correction_latency_ms")
    val correctionLatencyMs: Long,
    @ColumnInfo(name = "action_type")
    val actionType: String,
)
