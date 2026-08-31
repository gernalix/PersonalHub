package com.example.multitimetracker.ui.util

import com.example.multitimetracker.model.Tag

/**
 * Shared ordering for all tag pickers:
 * 1) selected tags first
 * 2) most recently used (max session start_ms) first
 * 3) name (case-insensitive)
 */
object TagSelectionOrder {
    fun sortForPicker(
        tags: List<Tag>,
        selectedIds: Set<Long>,
        lastUsedMsByTagId: Map<Long, Long>
    ): List<Tag> {
        return tags.sortedWith(
            compareByDescending<Tag> { selectedIds.contains(it.id) }
                .thenByDescending { lastUsedMsByTagId[it.id] ?: 0L }
                .thenBy { it.name.lowercase() }
        )
    }
}
