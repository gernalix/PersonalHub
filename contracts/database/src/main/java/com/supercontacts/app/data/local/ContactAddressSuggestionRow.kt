package com.supercontacts.app.data.local

import androidx.room.ColumnInfo

data class ContactAddressSuggestionRow(
    val value: String,
    val latitude: Double?,
    val longitude: Double?,
    @ColumnInfo(name = "usage_count")
    val usageCount: Int,
    @ColumnInfo(name = "last_used_at")
    val lastUsedAt: Long,
)
