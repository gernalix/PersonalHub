package com.example.multitimetracker.widget

import com.example.multitimetracker.core.quickevent.QuickEventTarget

data class QuickEventWidgetChoice(
    val target: QuickEventTarget,
    val title: String,
    val sortOrder: Int,
)

object QuickEventWidgetPicker {
    fun ordered(choices: List<QuickEventWidgetChoice>): List<QuickEventWidgetChoice> =
        choices.sortedWith(compareBy({ it.sortOrder }, { it.title.lowercase() }))

    fun filter(choices: List<QuickEventWidgetChoice>, query: String): List<QuickEventWidgetChoice> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return ordered(choices)
        return ordered(choices).filter { it.title.contains(normalized, ignoreCase = true) }
    }

    fun visibleSelection(
        selected: QuickEventTarget?,
        visibleChoices: List<QuickEventWidgetChoice>
    ): QuickEventTarget? =
        selected?.takeIf { target -> visibleChoices.any { it.target == target } }
}
