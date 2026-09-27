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
        assertEquals("Today", historyDayLabel(today, today, zone, Locale.ITALIAN))
        assertEquals("Yesterday", historyDayLabel(yesterday, today, zone, Locale.ITALIAN))
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
    fun everyCurrentSemanticAuditTableHasAnExplicitHumanFormatter() {
        val tables = setOf(
            "finance_accounts", "finance_products", "finance_titles", "finance_chains", "finance_stores",
            "finance_transactions", "finance_tags", "finance_transaction_tags", "finance_transfers",
            "finance_macros", "finance_recurrences", "finance_recurrence_tags", "finance_recurrence_overrides",
            "finance_attachments", "finance_photo_index", "finance_owned_items", "contacts", "contact_fields",
            "contact_events", "contact_initiatives", "contact_messaging_links", "saved_searches",
            "saved_search_tags", "tags", "contact_tags", "places", "place_aliases", "place_links",
            "place_events", "check_in_attempts", "check_in_attempt_candidates", "place_geofence_configs",
            "place_geofence_transition_log", "place_tags", "place_tag_cross_ref", "alert_rules",
            "alert_rule_targets", "alert_firings", "substances", "intake_events", "stock_adjustments", "prescriptions",
            "interaction_rules", "interaction_targets", "settings", "macros", "macro_items", "app_state",
            "correction_events", "word_entries", "wordpulse_sessions", "pvt_results", "quick_event_entries",
            "quick_event_entry_field_values", "quick_event_entry_tags", "quick_event_macro_actions",
            "quick_event_macro_tags", "quick_event_macros", "quick_event_template_fields",
            "quick_event_template_tags", "quick_event_templates", "session_tags", "sessions", "people_photos",
            "hub_preferences", "hub_entity_bindings", "hub_context_types", "hub_context_type_fields",
            "hub_contexts", "hub_context_members", "hub_tags", "hub_tag_aliases", "hub_tag_assignments",
            "hub_tag_parents", "hub_saved_tag_filters", "hub_resources", "since_when_counters",
        )
        assertTrue(tables.all(GIT_HISTORY_ENTITY_TYPES::containsKey))
        tables.forEach { table ->
            val type = gitHistoryEntityType(table)
            assertFalse("$table must not fall back to a generic item", type == "item")
            assertFalse("$table type must be human-readable", type.contains('_'))
            val item = GitHistoryItem(
                id = "audit-$table", occurredAt = 1L, author = "user", source = "ui", reason = null,
                groupId = null, table = table, operation = "INSERT", rowKey = "1",
                changedColumns = "name", historyPath = "h", commitSha = "c", revertedBy = null,
                displayAfter = "{\"name\":\"Example\"}",
            )
            val text = humanizeGitHistory(item, gitHistoryModule(table))
            if (table != "alert_firings") {
                assertTrue("$table must render its human entity type", text.title.contains(type))
            }
            if ('_' in table) {
                assertFalse("$table must not leak its raw table name", text.title.contains(table))
            }
        }
        assertEquals("substances", gitHistoryModule("macros"))
        assertEquals("wordpulse", gitHistoryModule("app_state"))
        assertEquals("timer", gitHistoryModule("quick_event_templates"))
        assertEquals("people", gitHistoryModule("people_photos"))

        GIT_HISTORY_ENTITY_TYPES.forEach { (table, expectedType) ->
            val operations = if (table == "alert_firings") listOf("INSERT") else listOf("INSERT", "UPDATE", "DELETE")
            operations.forEach { operation ->
                val item = GitHistoryItem(
                    id = "$table-$operation",
                    occurredAt = 1L,
                    author = "user",
                    source = "ui",
                    reason = null,
                    groupId = null,
                    table = table,
                    operation = operation,
                    rowKey = "1",
                    changedColumns = "",
                    historyPath = "h",
                    commitSha = "c",
                    revertedBy = null,
                )
                val text = humanizeGitHistory(item, "PersonalHub")
                if (table == "alert_firings") {
                    assertTrue(text.title.startsWith("Alert fired"))
                } else {
                    assertTrue("$table/$operation must name its human entity type", text.title.contains(expectedType))
                }
                if ('_' in table) {
                    assertFalse("$table/$operation must not expose raw snake_case table names", text.title.contains(table))
                }
            }
        }
    }

    @Test
    fun futureUnknownAuditTableStillGetsAHumanFallback() {
        val item = GitHistoryItem(
            id = "future", occurredAt = 1L, author = "user", source = "ui", reason = null,
            groupId = null, table = "future_feature_events", operation = "INSERT", rowKey = "1",
            changedColumns = "name", historyPath = "h", commitSha = "c", revertedBy = null,
            displayAfter = "{\"name\":\"Example\"}",
        )
        assertEquals("future feature event", gitHistoryEntityType(item.table))
        val text = humanizeGitHistory(item, "PersonalHub")
        assertEquals("Created future feature event “Example” · Name: — → Example", text.title)
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
    fun alertFiringHistoryIsEnglishAndCarriesEventEntityTagsAndDelivery() {
        val item = GitHistoryItem(
            id = "fire-1",
            occurredAt = 1L,
            author = "system",
            source = "alerts",
            reason = null,
            groupId = null,
            table = "alert_firings",
            operation = "INSERT",
            rowKey = "fire-1",
            changedColumns = "domain,trigger,entity_label,tag_names,delivery,message",
            historyPath = "h",
            commitSha = "c",
            revertedBy = null,
            displayAfter = "{\"domain\":\"timer\",\"trigger\":\"TIMER_START\",\"entity_label\":\"Work session\",\"tag_names\":\"Work, Deep focus\",\"delivery\":\"notification\",\"message\":\"Take a break\"}",
        )

        assertEquals("timer", gitHistoryModule(item))
        val text = humanizeGitHistory(item, "Timer")
        assertEquals("Alert fired · Timer · session started · Work session", text.title)
        assertEquals("Tags: Work, Deep focus\nDelivery: notification\nMessage: Take a break", text.detail)
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
