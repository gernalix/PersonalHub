package com.gernalix.personalhub.core.hubcontext

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.runBlocking

class HubTemporalProviderTest {
    @Test
    fun acceptsBoundedQueryAndCursor() {
        val query = HubTemporalQuery(fromMs = 10, toMs = 20, limit = 50, cursor = "next")
        assertEquals(10, query.fromMs)
        assertEquals(20, query.toMs)
        assertEquals(50, query.limit)
        assertEquals("next", query.cursor)
    }

    @Test
    fun keysetCursorRoundTripsOpaqueStableIds() {
        val encoded = encodeHubTemporalCursor(1234L, "uuid:with:punctuation")
        assertEquals(HubTemporalCursor(1234L, "uuid:with:punctuation"), decodeHubTemporalCursor(encoded))
        assertEquals(null, decodeHubTemporalCursor("broken"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnboundedLimit() {
        HubTemporalQuery(fromMs = 10, toMs = 20, limit = 201)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidWindow() {
        HubTemporalQuery(fromMs = 20, toMs = 20)
    }

    @Test fun providerPagesRemainBoundedAndStableAtHalfOpenEdges() = runBlocking {
        val provider = object : HubTemporalProvider {
            override val moduleId = "test"
            private val rows = listOf(10L, 15L, 19L, 20L).map { HubTemporalRecord(moduleId, "event", it.toString(), HubTemporalKind.POINT, it, title = it.toString()) }
            override suspend fun queryTemporal(query: HubTemporalQuery): HubTemporalPage {
                val cursor = decodeHubTemporalCursor(query.cursor)
                val candidates = rows
                    .filter { it.overlaps(query.fromMs, query.toMs) }
                    .sortedWith(compareByDescending<HubTemporalRecord> { it.startMs }.thenByDescending { it.stableId })
                    .filter { row -> cursor == null || row.startMs < cursor.sortMs || (row.startMs == cursor.sortMs && row.stableId < cursor.stableId) }
                val page = candidates.take(query.limit)
                return HubTemporalPage(
                    page,
                    if (candidates.size > query.limit) page.lastOrNull()?.let { encodeHubTemporalCursor(it.startMs, it.stableId) } else null,
                )
            }
        }
        val first = provider.queryTemporal(HubTemporalQuery(10, 20, limit = 2))
        val second = provider.queryTemporal(HubTemporalQuery(10, 20, limit = 2, cursor = first.nextCursor))
        assertEquals(listOf("19", "15"), first.records.map { it.stableId })
        assertEquals(listOf("10"), second.records.map { it.stableId })
        assertEquals(null, second.nextCursor)
    }
}
