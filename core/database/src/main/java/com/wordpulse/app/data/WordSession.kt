package com.wordpulse.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "wordpulse_sessions",
    indices = [
        Index(value = ["started_at_utc_ms"]),
        Index(value = ["ended_at_utc_ms"]),
    ],
)
data class WordSession(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "started_at_utc_ms")
    val startedAtUtcMs: Long,
    @ColumnInfo(name = "ended_at_utc_ms")
    val endedAtUtcMs: Long? = null,
)
