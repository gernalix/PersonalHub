package com.wordpulse.app.data

import androidx.room.ColumnInfo

data class SessionSummaryRow(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "started_at_utc_ms") val startedAtUtcMs: Long,
    @ColumnInfo(name = "ended_at_utc_ms") val endedAtUtcMs: Long?,
    @ColumnInfo(name = "word_count") val wordCount: Int,
    @ColumnInfo(name = "unique_count") val uniqueCount: Int,
)
