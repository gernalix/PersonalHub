package com.gernalix.luoghi

import com.gernalix.luoghi.ui.visits.formatVisitTimestampForInput
import com.gernalix.luoghi.ui.visits.parseVisitTimestampInput
import java.time.Instant
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class VisitTimestampTest {
    private lateinit var previousZone: TimeZone
    @Before fun setup() { previousZone = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("Europe/Copenhagen")) }
    @After fun restore() { TimeZone.setDefault(previousZone) }

    @Test fun secondsAreVisibleAndUnchangedInputPreservesTheExactInstant() {
        val original = Instant.parse("2026-10-19T12:30:59.987Z").toEpochMilli()
        val text = formatVisitTimestampForInput(original)
        assertEquals("19/10/2026 14:30:59", text)
        assertEquals(original, parseVisitTimestampInput(text, original))
        assertEquals(original - 987, parseVisitTimestampInput(text))
        assertEquals(Instant.parse("2026-10-19T12:30:00Z").toEpochMilli(), parseVisitTimestampInput("19/10/26 14:30"))
    }

    @Test fun notesOnlyEditsPreserveEitherOccurrenceOfTheRepeatedAutumnHour() {
        listOf("2026-10-25T00:30:15.123Z", "2026-10-25T01:30:15.456Z").forEach {
            val original = Instant.parse(it).toEpochMilli()
            assertEquals(original, parseVisitTimestampInput(formatVisitTimestampForInput(original), original))
        }
    }

    @Test fun invalidDatesAndTheMissingSpringHourAreRejected() {
        assertNull(parseVisitTimestampInput("31/2/2026 14:30"))
        assertNull(parseVisitTimestampInput("29/3/2026 02:30"))
        assertNull(parseVisitTimestampInput("19/10/2026 25:00"))
    }
}
