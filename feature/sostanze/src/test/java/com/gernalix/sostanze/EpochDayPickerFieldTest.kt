package com.gernalix.sostanze

import com.gernalix.sostanze.ui.epochDayToUtcMillis
import com.gernalix.sostanze.ui.utcMillisToEpochDay
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class EpochDayPickerFieldTest {
    @Test
    fun epochDayRoundTripIsTimezoneIndependent() {
        val days = listOf(
            LocalDate.of(1970, 1, 1).toEpochDay(),
            LocalDate.of(2026, 3, 29).toEpochDay(),
            LocalDate.of(2026, 10, 25).toEpochDay(),
            LocalDate.of(2030, 12, 31).toEpochDay(),
        )
        days.forEach { day ->
            assertEquals(day, utcMillisToEpochDay(epochDayToUtcMillis(day)))
        }
    }
}
