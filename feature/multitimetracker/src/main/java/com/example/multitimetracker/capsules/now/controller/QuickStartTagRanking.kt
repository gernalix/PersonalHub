package com.example.multitimetracker.capsules.now.controller

import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.Tag

private const val RECENCY_WEIGHT = 0.65
private const val FREQUENCY_WEIGHT = 0.35

/**
 * Ranks Timer quick-start tags by a stable blend of recent use and historical frequency.
 *
 * The score intentionally depends only on persisted usage facts, not on the current clock, so the
 * order does not reshuffle while the user is looking at the screen.
 */
internal fun rankQuickStartTags(
    visibleTags: List<Tag>,
    chronologySessions: List<SessionUi>,
    tagLastUsedMsByTagId: Map<Long, Long>,
): List<Tag> {
    if (visibleTags.size <= 1) return visibleTags

    val frequencyByTagId = HashMap<Long, Int>()
    chronologySessions
        .asSequence()
        .filter { it.deletedAtMs == null }
        .distinctBy { it.id }
        .forEach { session ->
            session.tagIds.forEach { tagId ->
                frequencyByTagId[tagId] = (frequencyByTagId[tagId] ?: 0) + 1
            }
        }

    val tagsWithRecency = visibleTags
        .filter { (tagLastUsedMsByTagId[it.id] ?: 0L) > 0L }
        .sortedWith(
            compareByDescending<Tag> { tagLastUsedMsByTagId[it.id] ?: 0L }
                .thenBy { it.name.lowercase() }
        )

    val recencyScoreByTagId = HashMap<Long, Double>(tagsWithRecency.size)
    val denominator = tagsWithRecency.size.coerceAtLeast(1).toDouble()
    tagsWithRecency.forEachIndexed { index, tag ->
        recencyScoreByTagId[tag.id] = (tagsWithRecency.size - index).toDouble() / denominator
    }

    val maxFrequency = frequencyByTagId.values.maxOrNull()?.coerceAtLeast(1) ?: 1

    fun score(tag: Tag): Double {
        val recency = recencyScoreByTagId[tag.id] ?: 0.0
        val frequency = (frequencyByTagId[tag.id] ?: 0).toDouble() / maxFrequency.toDouble()
        return (recency * RECENCY_WEIGHT) + (frequency * FREQUENCY_WEIGHT)
    }

    return visibleTags.sortedWith(
        compareByDescending<Tag> { score(it) }
            .thenByDescending { tagLastUsedMsByTagId[it.id] ?: 0L }
            .thenByDescending { frequencyByTagId[it.id] ?: 0 }
            .thenBy { it.name.lowercase() }
    )
}
