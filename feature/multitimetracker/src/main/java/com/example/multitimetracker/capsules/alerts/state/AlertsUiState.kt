package com.example.multitimetracker.capsules.alerts.state

import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.TimeFenceRule
import com.example.multitimetracker.model.PreFencePrompt

data class AlertsUiState(
    val tags: List<Tag>,
    val timeFenceRules: List<TimeFenceRule>,
    val tagLastUsedMsByTagId: Map<Long, Long>,
    val preFencePrompts: List<PreFencePrompt> = emptyList(),
)

data class AlertsHostState(
    val tags: List<Tag>,
    val tagLastUsedMsByTagId: Map<Long, Long>,
)
