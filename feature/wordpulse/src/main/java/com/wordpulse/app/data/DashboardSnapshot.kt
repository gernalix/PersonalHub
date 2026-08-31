package com.wordpulse.app.data

data class DashboardSnapshot(
    val totalWords: Int = 0,
    val uniqueWords: Int = 0,
    val todaysWords: Int = 0,
    val lastSevenDaysWords: Int = 0,
    val currentSessionWords: Int = 0,
    val currentSessionStartedAtUtcMs: Long? = null,
    val currentSessionLatestWordUtcMs: Long? = null,
)
