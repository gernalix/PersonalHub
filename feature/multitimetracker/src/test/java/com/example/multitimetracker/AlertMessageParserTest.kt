package com.example.multitimetracker

import com.example.multitimetracker.ui.alerts.AlertMessageSegment
import com.example.multitimetracker.ui.alerts.parseAlertMessageSegments
import org.junit.Assert.assertEquals
import org.junit.Test

class AlertMessageParserTest {
    @Test
    fun nakedHttpLinkUsesReadableDomainLabel() {
        val segments = parseAlertMessageSegments("https://www.workflowy.blablabla.com/#/a3b177f21c87")

        assertEquals(
            listOf(
                AlertMessageSegment.Link(
                    label = "workflowy",
                    url = "https://www.workflowy.blablabla.com/#/a3b177f21c87",
                )
            ),
            segments
        )
    }

    @Test
    fun exactCustomDeepLinkIsClickableAfterTrim() {
        val segments = parseAlertMessageSegments("  workflowy://open/a3b177f21c87  ")

        assertEquals(
            listOf(
                AlertMessageSegment.Link(
                    label = "workflowy",
                    url = "workflowy://open/a3b177f21c87",
                )
            ),
            segments
        )
    }
}
