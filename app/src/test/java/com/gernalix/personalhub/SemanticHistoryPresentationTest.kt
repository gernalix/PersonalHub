package com.gernalix.personalhub

import com.gernalix.personalhub.core.database.capsules.mutationevents.MutationEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SemanticHistoryPresentationTest {
    @Test
    fun oneActionWithDerivedWritesProducesOneHumanRowAndSearchesBeforeAfter() {
        val rows = semanticHistoryRows(listOf(
            event("tx", 1, "tags", "tags.tag.updated", "tag", """{"name":"Old"}""", """{"name":"New"}""", """{"name":"New"}"""),
            event("tx", 2, "hub", "hub.resource.created", "resource", null,
                """{"title":"Workflowy · Study","value":"https://workflowy.com/#/abc123"}""", """{"name":"Workflowy · Study"}"""),
            event("tx", 4, "places", "workflowy.link.assigned", "link", null,
                """{"name":"Workflowy · Study"}""", """{"name":"Workflowy · Study"}"""),
            event("tx", 3, "tags", "tags.tag.updated", "tag", """{"usage_count":1}""", """{"usage_count":2}""", """{"name":"New"}""", actor = "system"),
        ))
        assertEquals(1, rows.size)
        assertEquals("Linked “Study” to Workflowy", rows.single().text.title)
        assertEquals("places", rows.single().module)
        assertTrue(rows.single().text.searchText.contains("Old"))
        assertTrue(rows.single().text.searchText.contains("New"))
        assertFalse(rows.single().text.searchText.contains("usage_count"))
        assertFalse(rows.single().text.searchText.contains("abc123"))
    }

    @Test
    fun humanTitlesUseNameSnapshotAndNeverShowOpaqueIdentifiers() {
        val rows = semanticHistoryRows(listOf(
            event("timer", 1, "timer", "timer.session.created", "session", null,
                """{"title":"Study","start_ms":1790550000000}""", """{"name":"Study"}"""),
            event("intake", 1, "substances", "substances.intake.created", "intake", null,
                """{"dose":54,"dose_unit":"mg","timestamp_utc":"2026-09-27T20:00:00Z"}""", """{"name":"Test"}"""),
            event("people", 1, "people", "people.field.created", "field", null,
                """{"field_type":"name","value":"Mario Rossi"}""", """{"name":"Mario Rossi"}"""),
            event("deleted-person", 1, "people", "people.person.deleted", "person",
                """{"name":"Mario Rossi"}""", null, """{"name":"Mario Rossi"}"""),
        ))
        assertEquals(setOf("Created session “Study”", "Recorded 54 mg of “Test”", "Created person “Mario Rossi”", "Deleted person “Mario Rossi”"),
            rows.map { it.text.title }.toSet())
        rows.forEach { row ->
            assertFalse(row.text.searchText.contains("1790550000000"))
            assertFalse(row.text.searchText.contains("timestamp_utc"))
        }
    }

    @Test
    fun timerTagLinkIsVisibleInSameActionAndUsesHistoricalTagName() {
        val row = semanticHistoryRows(listOf(
            event("session-action", 1, "timer", "timer.session.created", "session", null,
                """{"title":"Walk"}""", """{"name":"Walk"}"""),
            event("session-action", 2, "timer", "timer.session.tag_added", "session", null,
                """{"tag":{"id":21,"name":"Exercise"}}""", """{"name":"Walk"}"""),
        )).single()
        assertEquals("Created session “Walk”", row.text.title)
        assertTrue(row.text.detail.orEmpty().contains("Added tag “Exercise” to session “Walk”"))
        assertTrue(row.text.searchText.contains("Exercise"))
    }

    @Test
    fun unnamedTechnicalFactDoesNotRenderAsNull() {
        val rows = semanticHistoryRows(listOf(event(
            "unnamed", 1, "people", "people.person.created", "person", null,
            """{"name":null}""", """{"name":null}""",
        )))
        assertEquals("Created person", rows.single().text.title)
        assertFalse(rows.single().text.searchText.contains("null"))
    }

    private fun event(
        transaction: String, sequence: Int, module: String, type: String, entity: String,
        before: String?, after: String?, context: String, actor: String = "user",
    ) = MutationEvent(
        eventId = "$transaction-$sequence", occurredAt = 1_790_550_000_000 + sequence,
        transactionId = transaction, sequence = sequence, module = module, eventType = type,
        entityType = entity, entityId = "00000000-0000-4000-8000-000000000000",
        actorType = actor, actorSource = "personalhub", beforeJson = before, afterJson = after,
        contextJson = context, schemaVersion = 1,
    )
}
