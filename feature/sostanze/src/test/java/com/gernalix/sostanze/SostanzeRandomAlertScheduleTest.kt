package com.gernalix.sostanze

import com.gernalix.sostanze.notifications.SostanzeRandomAlertWindow
import com.gernalix.sostanze.notifications.planSostanzeRandomAlertInstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SostanzeRandomAlertScheduleTest {
    @Test
    fun plansRequestedUniqueInstantsInsideWindow() {
        var next = 0L
        val planned = planSostanzeRandomAlertInstants(
            nowMs = 2_000L,
            count = 4,
            window = SostanzeRandomAlertWindow.DAY,
        ) {
            next += 7L
            next
        }

        assertEquals(4, planned.size)
        assertEquals(planned.size, planned.toSet().size)
        assertTrue(planned.all { it in 2_001L..(2_000L + SostanzeRandomAlertWindow.DAY.millis) })
    }
}
