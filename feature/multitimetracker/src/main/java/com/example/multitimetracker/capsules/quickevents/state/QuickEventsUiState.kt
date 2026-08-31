package com.example.multitimetracker.capsules.quickevents.state

import com.example.multitimetracker.model.QuickEventEntry
import com.example.multitimetracker.model.QuickEventFieldDefinition
import com.example.multitimetracker.model.QuickEventFieldValue
import com.example.multitimetracker.model.QuickEventMacro
import com.example.multitimetracker.model.QuickEventMacroAction
import com.example.multitimetracker.model.QuickEventTemplate
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.TemporalContext

data class QuickEventsUiState(
    val tags: List<Tag>,
    val quickEventTemplates: List<QuickEventTemplate>,
    val quickEventEntries: List<QuickEventEntry>,
    val quickEventFieldDefinitions: List<QuickEventFieldDefinition>,
    val quickEventFieldValues: List<QuickEventFieldValue>,
    val quickEventMacros: List<QuickEventMacro>,
    val quickEventMacroActions: List<QuickEventMacroAction>,
    val tagLastUsedMsByTagId: Map<Long, Long>,
    val nowMs: Long,
    val timeMachineTargetMs: Long?,
    val isReadOnly: Boolean,
) {
    fun effectiveTimeContext() = TemporalContext(timeMachineTargetMs).effectiveTime(nowMs)
}

data class QuickEventsHostState(
    val tags: List<Tag>,
    val tagLastUsedMsByTagId: Map<Long, Long>,
    val nowMs: Long,
    val timeMachineTargetMs: Long?,
    val isReadOnly: Boolean,
)
