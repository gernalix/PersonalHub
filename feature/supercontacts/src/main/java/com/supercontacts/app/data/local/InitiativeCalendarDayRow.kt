package com.supercontacts.app.data.local

import androidx.room.ColumnInfo

data class InitiativeCalendarDayRow(
    @ColumnInfo(name = "local_date")
    val localDate: String,
    @ColumnInfo(name = "self_count")
    val selfCount: Int,
    @ColumnInfo(name = "other_count")
    val otherCount: Int,
)
