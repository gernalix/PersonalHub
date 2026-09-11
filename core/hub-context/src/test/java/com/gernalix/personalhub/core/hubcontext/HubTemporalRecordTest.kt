package com.gernalix.personalhub.core.hubcontext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HubTemporalRecordTest {
    @Test
    fun pointUsesHalfOpenWindow() {
        val pointAtStart = point("a", 100)
        val pointInside = point("b", 199)
        val pointAtEnd = point("c", 200)

        assertTrue(pointAtStart.overlaps(100, 200))
        assertTrue(pointInside.overlaps(100, 200))
        assertFalse(pointAtEnd.overlaps(100, 200))
    }

    @Test
    fun intervalUsesStrictOverlapAndRunningIntervalsStayOpen() {
        val endsAtFrom = interval("a", 0, 100)
        val startsBeforeEnd = interval("b", 199, 250)
        val startsAtEnd = interval("c", 200, 250)
        val running = interval("d", 50, null)

        assertFalse(endsAtFrom.overlaps(100, 200))
        assertTrue(startsBeforeEnd.overlaps(100, 200))
        assertFalse(startsAtEnd.overlaps(100, 200))
        assertTrue(running.overlaps(100, 200))
    }

    @Test
    fun mergeFiltersModulesAndUsesStableTieBreakAcrossThreeModules() {
        val timer = point("timer", 150, module = "timer", source = "session")
        val money = point("money", 150, module = "soldi", source = "transaction")
        val substance = point("dose", 175, module = "substances", source = "intake")
        val outside = point("outside", 250, module = "wordpulse", source = "session")

        val merged = mergeTemporalSlices(
            slices = listOf(listOf(timer), listOf(money), listOf(substance, outside)),
            fromMs = 100,
            toMs = 200,
        )
        assertEquals(listOf("dose", "money", "timer"), merged.map { it.stableId })

        val filtered = mergeTemporalSlices(
            slices = listOf(listOf(timer), listOf(money), listOf(substance)),
            fromMs = 100,
            toMs = 200,
            moduleFilter = setOf("timer", "substances"),
        )
        assertEquals(listOf("dose", "timer"), filtered.map { it.stableId })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyWindow() {
        point("x", 1).overlaps(10, 10)
    }

    private fun point(
        id: String,
        start: Long,
        module: String = "test",
        source: String = "point",
    ) = HubTemporalRecord(module, source, id, HubTemporalKind.POINT, start, title = id)

    private fun interval(id: String, start: Long, end: Long?) = HubTemporalRecord(
        moduleId = "timer",
        source = "session",
        stableId = id,
        kind = HubTemporalKind.INTERVAL,
        startMs = start,
        endMs = end,
        title = id,
    )
}
