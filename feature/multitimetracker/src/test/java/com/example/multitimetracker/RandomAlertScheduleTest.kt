package com.example.multitimetracker

import com.example.multitimetracker.capsules.alerts.core.RandomAlertWindow
import com.example.multitimetracker.capsules.alerts.core.planRandomAlertInstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RandomAlertScheduleTest {
    @Test
    fun plansRequestedUniqueInstantsInsideWindow() {
        var next = 0L
        val planned = planRandomAlertInstants(
            nowMs = 1_000L,
            count = 3,
            window = RandomAlertWindow.HOUR,
        ) {
            next += 10L
            next
        }

        assertEquals(3, planned.size)
        assertEquals(planned.size, planned.toSet().size)
        assertTrue(planned.all { it in 1_001L..(1_000L + RandomAlertWindow.HOUR.millis) })
    }
}
