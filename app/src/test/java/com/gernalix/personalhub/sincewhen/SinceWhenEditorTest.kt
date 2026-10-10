package com.gernalix.personalhub.sincewhen

import java.time.*
import org.junit.Assert.assertEquals
import org.junit.Test

class SinceWhenEditorTest {
    private val zone = ZoneId.of("Europe/Copenhagen")
    private fun date(value: String) = LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    @Test fun unchangedPickerPreservesSubMinutePrecisionAndLocalDay() {
        val original = ZonedDateTime.of(2026, 10, 10, 13, 47, 23, 456000000, zone).toInstant().toEpochMilli()
        assertEquals(original, sinceWhenPickedTimestamp(original, date("2026-10-10"), 13, 47, zone))
    }
    @Test fun changingDateAndTimeUsesLocalZoneAndPreservesSecondsAndMillis() {
        val original = Instant.parse("2026-01-02T13:47:23.456Z").toEpochMilli()
        val expected = ZonedDateTime.of(2026, 7, 10, 18, 29, 23, 456000000, zone).toInstant().toEpochMilli()
        assertEquals(expected, sinceWhenPickedTimestamp(original, date("2026-07-10"), 18, 29, zone))
    }
    @Test fun daylightSavingOverlapKeepsOriginalOffsetWhenUnchanged() {
        val original = Instant.parse("2026-10-25T01:37:12.345Z").toEpochMilli()
        assertEquals(original, sinceWhenPickedTimestamp(original, date("2026-10-25"), 2, 37, zone))
    }
}
