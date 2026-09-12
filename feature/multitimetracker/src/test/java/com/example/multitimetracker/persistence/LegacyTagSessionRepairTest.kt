package com.example.multitimetracker.persistence

import com.example.multitimetracker.core.contracts.TaggedSessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegacyTagSessionRepairTest {
    private fun record(
        sessionTitle: String = "Legacy",
        startTs: Long = 100L,
        endTs: Long = 200L,
    ) = TaggedSessionRecord(
        tagId = 7L,
        tagName = "Tag",
        sessionId = 42L,
        sessionTitle = sessionTitle,
        startTs = startTs,
        endTs = endTs,
    )

    @Test
    fun uniqueIntervalMatchDoesNotDependOnLegacySessionId() {
        val rows = listOf(
            LegacyTagSessionRepair.ClosedSessionRow(
                id = 900L,
                title = "Modern title",
                startMs = 100L,
                endMs = 200L,
            )
        )

        assertEquals(900L, LegacyTagSessionRepair.resolveModernSessionId(record(), rows))
    }

    @Test
    fun duplicateIntervalUsesExactTitleToDisambiguate() {
        val rows = listOf(
            LegacyTagSessionRepair.ClosedSessionRow(900L, "Other", 100L, 200L),
            LegacyTagSessionRepair.ClosedSessionRow(901L, "Legacy", 100L, 200L),
        )

        assertEquals(901L, LegacyTagSessionRepair.resolveModernSessionId(record(), rows))
    }

    @Test
    fun ambiguousIntervalIsNotGuessed() {
        val rows = listOf(
            LegacyTagSessionRepair.ClosedSessionRow(900L, "Same", 100L, 200L),
            LegacyTagSessionRepair.ClosedSessionRow(901L, "Same", 100L, 200L),
        )

        assertNull(
            LegacyTagSessionRepair.resolveModernSessionId(
                record(sessionTitle = "Same"),
                rows,
            )
        )
    }

    @Test
    fun differentIntervalIsNotMatched() {
        val rows = listOf(
            LegacyTagSessionRepair.ClosedSessionRow(900L, "Legacy", 101L, 200L),
        )

        assertNull(LegacyTagSessionRepair.resolveModernSessionId(record(), rows))
    }
}
