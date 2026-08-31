package com.wordpulse.app.data

import androidx.room.ColumnInfo

data class SearchResultRow(
    @ColumnInfo(name = "normalized_word") val normalizedWord: String,
    @ColumnInfo(name = "sample_original_word") val sampleOriginalWord: String,
    @ColumnInfo(name = "occurrences") val occurrences: Int,
    @ColumnInfo(name = "latest_occurrence_utc_ms") val latestOccurrenceUtcMs: Long,
    @ColumnInfo(name = "length") val length: Int,
)
