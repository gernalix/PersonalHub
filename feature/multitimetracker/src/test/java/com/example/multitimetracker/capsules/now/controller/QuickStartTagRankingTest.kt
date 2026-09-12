package com.example.multitimetracker.capsules.now.controller

import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.Tag
import org.junit.Assert.assertEquals
import org.junit.Test

class QuickStartTagRankingTest {

    @Test
    fun `more recent tag ranks first when frequency is equal`() {
        val recent = tag(1, "Recent")
        val older = tag(2, "Older")

        val ranked = rankQuickStartTags(
            visibleTags = listOf(older, recent),
            chronologySessions = listOf(session(1, setOf(1)), session(2, setOf(2))),
            tagLastUsedMsByTagId = mapOf(1L to 300L, 2L to 100L),
        )

        assertEquals(listOf(1L, 2L), ranked.map { it.id })
    }

    @Test
    fun `frequency breaks equal recency`() {
        val frequent = tag(1, "Frequent")
        val occasional = tag(2, "Occasional")

        val ranked = rankQuickStartTags(
            visibleTags = listOf(occasional, frequent),
            chronologySessions = listOf(
                session(1, setOf(1)),
                session(2, setOf(1)),
                session(3, setOf(1)),
                session(4, setOf(2)),
            ),
            tagLastUsedMsByTagId = mapOf(1L to 500L, 2L to 500L),
        )

        assertEquals(listOf(1L, 2L), ranked.map { it.id })
    }

    @Test
    fun `deleted sessions do not increase frequency`() {
        val used = tag(1, "Used")
        val deletedOnly = tag(2, "Deleted only")

        val ranked = rankQuickStartTags(
            visibleTags = listOf(deletedOnly, used),
            chronologySessions = listOf(
                session(1, setOf(1)),
                session(2, setOf(2), deletedAtMs = 900L),
                session(3, setOf(2), deletedAtMs = 901L),
            ),
            tagLastUsedMsByTagId = emptyMap(),
        )

        assertEquals(listOf(1L, 2L), ranked.map { it.id })
    }

    @Test
    fun `unused ties are deterministic by name`() {
        val zulu = tag(1, "Zulu")
        val alpha = tag(2, "Alpha")

        val ranked = rankQuickStartTags(
            visibleTags = listOf(zulu, alpha),
            chronologySessions = emptyList(),
            tagLastUsedMsByTagId = emptyMap(),
        )

        assertEquals(listOf(2L, 1L), ranked.map { it.id })
    }

    private fun tag(id: Long, name: String) = Tag(
        id = id,
        name = name,
        activeChildrenCount = 0,
        totalMs = 0L,
        lastStartedAtMs = null,
    )

    private fun session(
        id: Long,
        tagIds: Set<Long>,
        deletedAtMs: Long? = null,
    ) = SessionUi(
        id = id,
        title = "",
        startMs = id * 1_000L,
        endMs = id * 1_000L + 500L,
        tagIds = tagIds,
        deletedAtMs = deletedAtMs,
    )
}
