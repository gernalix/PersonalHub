package com.gernalix.personalhub.core.hubcontext

import org.junit.Assert.assertEquals
import org.junit.Test

class HubTemporalProviderTest {
    @Test
    fun acceptsBoundedQueryAndCursor() {
        val query = HubTemporalQuery(fromMs = 10, toMs = 20, limit = 50, cursor = "next")
        assertEquals(10, query.fromMs)
        assertEquals(20, query.toMs)
        assertEquals(50, query.limit)
        assertEquals("next", query.cursor)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnboundedLimit() {
        HubTemporalQuery(fromMs = 10, toMs = 20, limit = 201)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidWindow() {
        HubTemporalQuery(fromMs = 20, toMs = 20)
    }
}
