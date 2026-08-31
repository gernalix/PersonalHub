package com.wordpulse.app.data

import androidx.room.ColumnInfo

data class DuplicateHistory(
    @ColumnInfo(name = "previous_occurrences")
    val previousOccurrences: Int,
    @ColumnInfo(name = "previous_occurrence_utc_ms")
    val previousOccurrenceUtcMs: Long?,
)
