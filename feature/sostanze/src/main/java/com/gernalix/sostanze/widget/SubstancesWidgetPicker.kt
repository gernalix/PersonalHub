package com.gernalix.sostanze.widget

import com.gernalix.sostanze.data.SubstanceEntity

data class SubstancesWidgetChoice(
    val substanceId: Long,
    val title: String,
)

object SubstancesWidgetPicker {
    fun choices(substances: List<SubstanceEntity>): List<SubstancesWidgetChoice> =
        substances
            .filter { !it.archived }
            .map { SubstancesWidgetChoice(it.id, it.name) }
            .sortedBy { it.title.lowercase() }

    fun filter(choices: List<SubstancesWidgetChoice>, query: String): List<SubstancesWidgetChoice> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return choices
        return choices.filter { it.title.contains(normalized, ignoreCase = true) }
    }

    fun visibleSelection(
        selected: Long?,
        visibleChoices: List<SubstancesWidgetChoice>
    ): Long? =
        selected?.takeIf { id -> visibleChoices.any { it.substanceId == id } }
}
