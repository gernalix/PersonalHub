package com.supercontacts.app.data.local

import androidx.room.ColumnInfo

data class HistoryCalendarDayRow(
    @ColumnInfo(name = "local_date")
    val localDate: String,
    @ColumnInfo(name = "event_count")
    val eventCount: Int,
)
