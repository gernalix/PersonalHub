package com.gernalix.personalhub.sincewhen

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.junit.Assert.*
import org.junit.Test

class CounterElapsedTest {
    private val utc = ZoneId.of("UTC")
    private fun time(value: String) = Instant.parse(value).toEpochMilli()

    @Test fun countdownIsNegativeUntilTheTargetAndContinuesAfterIt() {
        val target = time("2026-10-20T12:00:00Z")
        val before = CounterElapsed.between(target, target - 90_000, setOf("MINUTES", "SECONDS"), utc)
        assertTrue(before.negative)
        assertEquals(listOf(ChronoUnit.MINUTES to 1L, ChronoUnit.SECONDS to 30L), before.parts)
        val zero = CounterElapsed.between(target, target, setOf("SECONDS"), utc)
        assertFalse(zero.negative)
        assertEquals(listOf(ChronoUnit.SECONDS to 0L), zero.parts)
        assertEquals(before.copy(negative = false), CounterElapsed.between(target, target + 90_000, setOf("MINUTES", "SECONDS"), utc))
    }

    @Test fun calendarUnitsAndClockUnitsKeepTheirMeaningAcrossDstAndLeapDates() {
        val start = time("2026-03-28T11:00:00Z")
        val end = time("2026-03-29T10:00:00Z")
        val zone = ZoneId.of("Europe/Copenhagen")
        assertEquals(listOf(ChronoUnit.DAYS to 1L), CounterElapsed.between(start, end, setOf("DAYS"), zone).parts)
        assertEquals(listOf(ChronoUnit.HOURS to 23L), CounterElapsed.between(start, end, setOf("HOURS"), zone).parts)
        assertEquals(listOf(ChronoUnit.YEARS to 1L, ChronoUnit.DAYS to 1L),
            CounterElapsed.between(time("2024-02-29T12:00:00Z"), time("2025-03-01T12:00:00Z"), setOf("YEARS", "DAYS"), utc).parts)
    }
}
