package com.example.multitimetracker.model

object QuickEventFilters {
    data class Section<T>(
        val tagId: Long?,
        val tagName: String?,
        val items: List<T>,
    )

    fun filterTemplates(
        templates: List<QuickEventTemplate>,
        tags: List<Tag>,
        query: String,
        selectedTagIds: Set<Long>,
        includeArchived: Boolean = false,
    ): List<QuickEventTemplate> {
        val q = query.trim()
        val tagNamesById = tags.associateBy({ it.id }, { it.name })
        return templates
            .asSequence()
            .filter { template -> !template.isArchived || includeArchived }
            .filter { template -> selectedTagIds.isEmpty() || selectedTagIds.any { it in template.tagIds } }
            .filter { template ->
                if (q.isBlank()) {
                    true
                } else {
                    template.title.contains(q, ignoreCase = true) ||
                        template.tagIds.any { tagId -> tagNamesById[tagId]?.contains(q, ignoreCase = true) == true }
                }
            }
            .sortedWith(compareBy<QuickEventTemplate> { it.sortOrder }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            .toList()
    }

    fun filterMacros(
        macros: List<QuickEventMacro>,
        tags: List<Tag>,
        query: String,
        selectedTagIds: Set<Long>,
        includeArchived: Boolean = false,
    ): List<QuickEventMacro> {
        val q = query.trim()
        val tagNamesById = tags.associateBy({ it.id }, { it.name })
        return macros
            .asSequence()
            .filter { macro -> !macro.isArchived || includeArchived }
            .filter { macro -> selectedTagIds.isEmpty() || selectedTagIds.any { it in macro.tagIds } }
            .filter { macro ->
                if (q.isBlank()) {
                    true
                } else {
                    macro.title.contains(q, ignoreCase = true) ||
                        macro.tagIds.any { tagId -> tagNamesById[tagId]?.contains(q, ignoreCase = true) == true }
                }
            }
            .sortedWith(compareBy<QuickEventMacro> { it.sortOrder }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            .toList()
    }

    fun filterEntries(
        entries: List<QuickEventEntry>,
        tags: List<Tag>,
        query: String,
        selectedTagIds: Set<Long>,
        includeDeleted: Boolean = false,
    ): List<QuickEventEntry> {
        val q = query.trim()
        val tagNamesById = tags.associateBy({ it.id }, { it.name })
        return entries
            .asSequence()
            .filter { entry -> includeDeleted || entry.deletedAtMs == null }
            .filter { entry -> selectedTagIds.isEmpty() || selectedTagIds.any { it in entry.tagIds } }
            .filter { entry ->
                if (q.isBlank()) {
                    true
                } else {
                    entry.title.contains(q, ignoreCase = true) ||
                        entry.tagIds.any { tagId -> tagNamesById[tagId]?.contains(q, ignoreCase = true) == true }
                }
            }
            .sortedWith(compareByDescending<QuickEventEntry> { it.timestampMs }.thenByDescending { it.id })
            .toList()
    }

    fun groupTemplatesByPrimaryTag(
        templates: List<QuickEventTemplate>,
        tags: List<Tag>,
    ): List<Section<QuickEventTemplate>> {
        return groupByPrimaryTag(
            items = templates,
            tags = tags,
            tagIdsForItem = { it.tagIds },
            titleForItem = { it.title },
            sortOrderForItem = { it.sortOrder },
        )
    }

    fun groupMacrosByPrimaryTag(
        macros: List<QuickEventMacro>,
        tags: List<Tag>,
    ): List<Section<QuickEventMacro>> {
        return groupByPrimaryTag(
            items = macros,
            tags = tags,
            tagIdsForItem = { it.tagIds },
            titleForItem = { it.title },
            sortOrderForItem = { it.sortOrder },
        )
    }

    private fun <T> groupByPrimaryTag(
        items: List<T>,
        tags: List<Tag>,
        tagIdsForItem: (T) -> Set<Long>,
        titleForItem: (T) -> String,
        sortOrderForItem: (T) -> Int,
    ): List<Section<T>> {
        val visibleTagById = tags
            .filter { !it.isDeleted && !it.isArchived }
            .associateBy { it.id }
        val tagOrder = compareBy<Long> { visibleTagById[it]?.name?.lowercase().orEmpty() }.thenBy { it }
        val grouped = linkedMapOf<Long?, MutableList<T>>()
        items.forEach { item ->
            val primaryTagId = tagIdsForItem(item)
                .filter { it in visibleTagById }
                .sortedWith(tagOrder)
                .firstOrNull()
            grouped.getOrPut(primaryTagId) { mutableListOf() } += item
        }
        return grouped.map { (tagId, sectionItems) ->
            Section(
                tagId = tagId,
                tagName = tagId?.let { visibleTagById[it]?.name },
                items = sectionItems.sortedWith(
                    compareBy<T> { sortOrderForItem(it) }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { titleForItem(it) }
                ),
            )
        }.sortedWith(
            compareBy<Section<T>> { it.tagName == null }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.tagName.orEmpty() }
        )
    }
}
