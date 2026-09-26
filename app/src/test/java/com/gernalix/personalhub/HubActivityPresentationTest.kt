package com.gernalix.personalhub

import com.gernalix.personalhub.core.database.HubActivityEntity
import com.gernalix.personalhub.core.database.HubActivityPayloadKind
import com.gernalix.personalhub.core.database.HubActivityStatus
import com.gernalix.personalhub.core.database.capsules.gitdata.GitHistoryItem
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HubActivityPresentationTest {
    @Test
    fun gitTechnicalWritesShareOneLogicalRow() {
        val first = GitHistoryItem(
            id = "one", occurredAt = 1L, author = "user", source = "app", reason = null,
            groupId = "logical", table = "sessions", operation = "INSERT", rowKey = "1",
            changedColumns = "name", historyPath = "h", commitSha = "c", revertedBy = null,
        )
        val derived = first.copy(id = "two", table = "hub_context_members", changedColumns = "position")
        assertEquals(1, groupGitHistoryRows(listOf(first, derived)).size)
        assertFalse(displayableGitHistoryGroup(listOf(derived)))
        assertTrue(displayableGitHistoryGroup(listOf(first, derived)))
        assertFalse(displayableGitHistoryGroup(listOf(first.copy(
            table = "hub_tags", operation = "UPDATE", changedColumns = "usage_count,provenance,updated_at",
        ))))
        assertNull(humanFieldLabel("Provenance"))
        assertNull(humanFieldLabel("Position"))
        assertNull(humanFieldLabel("Usage Count"))
        assertNull(humanFieldLabel("Entity Kind"))
        assertNull(humanFieldLabel("Stock Current"))
        assertNull(humanFieldLabel("Timestamp Utc"))
        val workflowy = first.copy(
            table = "hub_resources", operation = "INSERT", changedColumns = "value,kind,position",
            displayAfter = "{\"value\":\"https://workflowy.com/#/59d823cea257\"}",
        )
        val (_, text) = humanizeGitHistoryGroup(listOf(workflowy, derived)) { "PersonalHub" }
        assertEquals("Linked Workflowy node", text.title)
        assertFalse(text.searchText.contains("position", ignoreCase = true))
    }

    @Test
    fun fallbackWorkflowyContextIsOneHumanAction() {
        val row = activity(
            moduleId = "hub", action = "episode_created", entityKind = "episode",
            entityLabel = "Workflowy", sourceTable = "hub_contexts",
        )
        val text = humanizeActivity(row, "Workflowy", "PersonalHub")
        assertEquals("Linked Workflowy node", text.title)
        assertFalse(text.searchText.contains("hub_contexts"))
    }

    @Test
    fun localDayGroupsUseTodayAndYesterday() {
        val zone = ZoneId.of("Europe/Copenhagen")
        val today = LocalDateTime.of(2026, 9, 26, 12, 0).atZone(zone).toInstant().toEpochMilli()
        val yesterday = LocalDateTime.of(2026, 9, 25, 12, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("Today", historyDayLabel(today, today, zone, Locale.ENGLISH))
        assertEquals("Yesterday", historyDayLabel(yesterday, today, zone, Locale.ENGLISH))
    }
    @Test
    fun gitHistoryProjectionIsHumanAndSearchesBothSides() {
        val item = GitHistoryItem(
            id = "technical-id", occurredAt = 1L, author = "technical-author", source = "backend",
            reason = null, groupId = null, table = "places", operation = "UPDATE",
            rowKey = "technical-row", changedColumns = "address,notes,updated_at",
            historyPath = "history/technical.jsonl", commitSha = "technical-sha", revertedBy = null,
            displayBefore = "{\"nickname\":\"Casa\",\"address\":\"Old street\",\"notes\":\"hidden-before\"}",
            displayAfter = "{\"nickname\":\"Casa\",\"address\":\"New street\",\"notes\":\"hidden-after\"}",
        )
        val text = humanizeGitHistory(item, "Places")
        assertTrue(text.title.contains("Updated place “Casa”"))
        assertTrue(text.detail!!.contains("Old street → New street"))
        assertTrue(text.searchText.contains("hidden-before"))
        assertTrue(text.searchText.contains("hidden-after"))
        listOf("technical-id", "technical-row", "technical-sha", "technical-author", "backend", "updated_at")
            .forEach { assertFalse(text.searchText.contains(it)) }
        assertEquals("places", gitHistoryModule(item.table))
    }
    @Test
    fun dateLabelUsesCompactLocalizedWeekdayFormat() {
        val zone = ZoneId.of("Europe/Copenhagen")
        val epoch = LocalDateTime.of(2026, 9, 24, 12, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

        assertEquals("Thu 24/9/26", historyDateLabel(epoch, zone, Locale.ENGLISH))
    }

    @Test
    fun searchNormalizationIsCaseAndDiacriticInsensitive() {
        assertEquals("jose cafe", normalizeHistorySearchText(" José CAFÉ ", Locale.ENGLISH))
    }
    @Test
    fun rowPayloadHumanizationShowsSemanticRenameAndSuppressesTechnicalIds() {
        val technicalId = "3f830d53-cc9d-45a5-92df-285843faba71"
        val activity = activity(
            moduleId = "places",
            action = "place_updated",
            entityKind = "place",
            entityId = technicalId,
            entityLabel = "Casa",
            payloadKind = HubActivityPayloadKind.ROW_V1,
            payloadColumns = "uuid,nickname,address,updated_at",
            beforePayload = rowPayload(technicalId, "Home", "Old street", 1_700_000_000_000L),
            afterPayload = rowPayload(technicalId, "Casa", "New street", 1_700_000_100_000L),
        )

        val text = humanizeActivity(activity, "Casa", "Places")

        assertTrue(text.title.contains("Home"))
        assertTrue(text.title.contains("Casa"))
        assertTrue(text.searchText.contains("Old street"))
        assertTrue(text.searchText.contains("New street"))
        assertFalse(text.searchText.contains(technicalId))
        assertFalse(text.searchText.contains("updated_at"))
    }

    @Test
    fun peopleFieldChangeSearchesHiddenBeforeAndAfterValues() {
        val activity = activity(
            moduleId = "people",
            action = "updated",
            entityKind = "person",
            entityId = "person-technical-id",
            entityLabel = "Marco Rossi",
            detailKey = "name",
            beforePayload = "Marco",
            afterPayload = "Marco Rossi",
            payloadKind = HubActivityPayloadKind.PEOPLE_EVENT_V1,
        )

        val text = humanizeActivity(activity, "Marco Rossi", "People")

        assertTrue(normalizeHistorySearchText(text.searchText).contains("marco"))
        assertTrue(normalizeHistorySearchText(text.searchText).contains("marco rossi"))
        assertFalse(text.searchText.contains("person-technical-id"))
    }

    @Test
    fun technicalUuidValueIsSuppressedEvenUnderHumanFieldLabel() {
        val uuid = "08afb1f0-225f-4fed-8a10-7081d8e28ec4"
        val activity = activity(
            moduleId = "people",
            action = "updated",
            entityKind = "person",
            entityLabel = "Marco Rossi",
            detailKey = "address",
            beforePayload = null,
            afterPayload = uuid,
            payloadKind = HubActivityPayloadKind.PEOPLE_EVENT_V1,
        )

        val text = humanizeActivity(activity, "Marco Rossi", "People")

        assertFalse(text.title.contains(uuid))
        assertFalse(text.searchText.contains(uuid))
        assertFalse(text.detail.orEmpty().contains(uuid))
    }

    @Test
    fun timerEpochMillisecondsAreNotRenderedOrIndexed() {
        val epoch = "1789794443437"
        val activity = activity(
            moduleId = "timer",
            action = "created",
            entityKind = "session",
            entityLabel = "filler",
            payloadKind = HubActivityPayloadKind.ROW_V1,
            payloadColumns = "timestamp_ms",
            afterPayload = rowPayload(epoch.toLong()),
        )

        val text = humanizeActivity(activity, "filler", "Timer")

        assertFalse(text.title.contains("Timestamp Ms"))
        assertFalse(text.title.contains(epoch))
        assertFalse(text.searchText.contains(epoch))
        assertFalse(text.detail.orEmpty().contains(epoch))
    }

    @Test
    fun groupingKeepsUngroupedRowsSeparateAndCombinesSharedGroup() {
        val groupedA = activity(id = "a", groupId = "group-1")
        val groupedB = activity(id = "b", groupId = "group-1")
        val lone = activity(id = "c")

        val groups = groupActivityRows(listOf(groupedA, groupedB, lone))

        assertEquals(2, groups.size)
        assertEquals(listOf("a", "b"), groups.first().map { it.id })
        assertEquals(listOf("c"), groups.last().map { it.id })
    }

    @Test
    fun groupedUndoNeverReversesOnlyOneTechnicalSubwrite() {
        val safe = activity(id = "safe", reversible = true)
        val other = activity(id = "other", reversible = false)
        assertNull(safeUndoActivityId(listOf(safe, other)))
        assertEquals("safe", safeUndoActivityId(listOf(safe)))

        val secondSafe = activity(id = "second", reversible = true)
        assertNull(safeUndoActivityId(listOf(safe, secondSafe)))
        assertNull(
            safeUndoActivityId(
                listOf(
                    activity(
                        id = "reverted",
                        reversible = true,
                        status = HubActivityStatus.REVERTED,
                    ),
                ),
            ),
        )
    }

    private fun activity(
        id: String = "event-1",
        moduleId: String = "places",
        action: String = "updated",
        entityKind: String? = "place",
        entityId: String? = "entity-1",
        entityLabel: String? = "Home",
        detailKey: String? = null,
        payloadKind: String? = null,
        payloadColumns: String? = null,
        beforePayload: String? = null,
        afterPayload: String? = null,
        groupId: String? = null,
        reversible: Boolean = false,
        status: String = HubActivityStatus.ACTIVE,
        sourceTable: String = "test_table",
    ) = HubActivityEntity(
        id = id,
        occurredAt = 1_790_000_000_000L,
        moduleId = moduleId,
        action = action,
        entityKind = entityKind,
        entityId = entityId,
        entityLabel = entityLabel,
        detailKey = detailKey,
        sourceTable = sourceTable,
        payloadKind = payloadKind,
        payloadColumns = payloadColumns,
        beforePayload = beforePayload,
        afterPayload = afterPayload,
        groupId = groupId,
        reversible = reversible,
        status = status,
    )

    private fun rowPayload(vararg values: Any?): String =
        values.joinToString(":") { value ->
            val sqliteLiteral = when (value) {
                null -> "NULL"
                is Number -> value.toString()
                else -> "'${value.toString().replace("'", "''")}'"
            }
            sqliteLiteral.toByteArray(Charsets.UTF_8)
                .joinToString("") { byte -> "%02X".format(byte.toInt() and 0xff) }
        }
}
