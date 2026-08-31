package com.example.multitimetracker.capsules.quickevents.public

import com.example.multitimetracker.model.QuickEventEntry
import com.example.multitimetracker.model.QuickEventFieldDefinition
import com.example.multitimetracker.model.QuickEventFieldValue
import com.example.multitimetracker.model.QuickEventMacro
import com.example.multitimetracker.model.QuickEventMacroAction
import com.example.multitimetracker.model.QuickEventTemplate

data class QuickEventsSnapshot(
    val templates: List<QuickEventTemplate>,
    val entries: List<QuickEventEntry>,
    val fieldDefinitions: List<QuickEventFieldDefinition>,
    val fieldValues: List<QuickEventFieldValue>,
    val macros: List<QuickEventMacro>,
    val macroActions: List<QuickEventMacroAction>,
) {
    companion object {
        val EMPTY = QuickEventsSnapshot(
            templates = emptyList(),
            entries = emptyList(),
            fieldDefinitions = emptyList(),
            fieldValues = emptyList(),
            macros = emptyList(),
            macroActions = emptyList(),
        )
    }
}
