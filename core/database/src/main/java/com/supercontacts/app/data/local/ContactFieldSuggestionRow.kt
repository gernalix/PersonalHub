package com.supercontacts.app.data.local

import androidx.room.ColumnInfo

data class ContactFieldSuggestionRow(
    val value: String,
    @ColumnInfo(name = "usage_count")
    val usageCount: Int,
    @ColumnInfo(name = "last_used_at")
    val lastUsedAt: Long,
)
