package com.supercontacts.app.data.local

import androidx.room.ColumnInfo

data class ContactHomeMetricRow(
    @ColumnInfo(name = "contact_id")
    val contactId: Long,
    @ColumnInfo(name = "open_count")
    val openCount: Int,
    @ColumnInfo(name = "initiative_count")
    val initiativeCount: Int,
    @ColumnInfo(name = "last_interaction_at")
    val lastInteractionAt: Long?,
)
