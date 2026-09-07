package com.example.multitimetracker.ui.components

import com.example.multitimetracker.model.Tag

object SessionTagPickerRules {
    fun cleanQuery(query: String): String = query.trim()

    fun canCreateExactName(query: String, tags: List<Tag>): Boolean {
        val clean = cleanQuery(query)
        return clean.isNotEmpty() && tags.none { tag -> tag.name.trim().equals(clean, ignoreCase = true) }
    }

    fun filterByQuery(tags: List<Tag>, query: String): List<Tag> {
        val clean = cleanQuery(query)
        return if (clean.isEmpty()) tags else tags.filter { tag -> tag.name.contains(clean, ignoreCase = true) }
    }

    fun selectedTags(tags: List<Tag>, selectedIds: Set<Long>): List<Tag> = tags.filter { it.id in selectedIds }
}
