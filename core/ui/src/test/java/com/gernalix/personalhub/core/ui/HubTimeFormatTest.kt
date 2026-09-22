package com.gernalix.personalhub.core.ui

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class HubTimeFormatTest {
    @Test
    fun appliesCopenhagenDstFromOneSharedFormatter() {
        val zone = ZoneId.of("Europe/Copenhagen")
        val before = Instant.parse("2026-03-29T00:30:00Z").toEpochMilli()
        val after = Instant.parse("2026-03-29T01:30:00Z").toEpochMilli()

        assertEquals("2026-03-29 01:30", HubTimeFormat.pattern(before, "uuuu-MM-dd HH:mm", zone, Locale.ROOT))
        assertEquals("2026-03-29 03:30", HubTimeFormat.pattern(after, "uuuu-MM-dd HH:mm", zone, Locale.ROOT))
    }

    @Test
    fun compactDurationPreservesTimerContract() {
        assertEquals("5h 11m 22s", HubTimeFormat.compactDuration(18_682_000L))
        assertEquals("1m", HubTimeFormat.compactDuration(60_000L, showSeconds = false))
        assertEquals("0m", HubTimeFormat.compactDuration(0L, showSeconds = false))
    }
}
