package com.gernalix.personalhub.salute

import org.junit.Assert.assertEquals
import org.junit.Test

class HealthTurnaroundSummaryTest {
    @Test
    fun formatsAverageAsWholeDaysAndHours() {
        val summary = HealthTurnaroundSummary(
            count = 3,
            averageMs = 1L * 86_400_000L + 7L * 3_600_000L + 59L * 60_000L,
        )

        assertEquals("1g 7h", summary.formatted)
    }

    @Test
    fun keepsSubDayAverageAtZeroDays() {
        val summary = HealthTurnaroundSummary(
            count = 2,
            averageMs = 9L * 3_600_000L,
        )

        assertEquals("0g 9h", summary.formatted)
    }
}
