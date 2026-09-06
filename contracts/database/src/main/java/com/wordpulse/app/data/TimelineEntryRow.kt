package com.wordpulse.app.data

import androidx.room.ColumnInfo

data class TimelineEntryRow(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "original_word") val originalWord: String,
    @ColumnInfo(name = "normalized_word") val normalizedWord: String,
    @ColumnInfo(name = "created_at_utc_ms") val createdAtUtcMs: Long,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "duplicate_ordinal") val duplicateOrdinal: Int,
)
