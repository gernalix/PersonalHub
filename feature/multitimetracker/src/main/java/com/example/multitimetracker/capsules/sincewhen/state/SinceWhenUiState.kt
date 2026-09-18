package com.example.multitimetracker.capsules.sincewhen.state

import com.example.multitimetracker.model.LifePeriod
import com.example.multitimetracker.model.Tag

data class SinceWhenUiState(
    val tags: List<Tag>,
    val lifePeriods: List<LifePeriod>,
    val nowMs: Long,
)

data class SinceWhenHostState(
    val tags: List<Tag>,
    val nowMs: Long,
)
