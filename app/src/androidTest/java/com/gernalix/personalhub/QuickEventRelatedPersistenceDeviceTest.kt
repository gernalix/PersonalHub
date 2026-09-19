package com.gernalix.personalhub

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.multitimetracker.model.QuickEventFieldDefinition
import com.example.multitimetracker.model.QuickEventFieldValue
import com.example.multitimetracker.model.QuickEventMacroAction
import com.example.multitimetracker.persistence.QuickEventRepository
import com.example.multitimetracker.core.session.DefaultSessionCore
import com.example.multitimetracker.persistence.IntegrityStatsSqlite
import com.example.multitimetracker.persistence.SnapshotStore
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuickEventRelatedPersistenceDeviceTest {
    @TableProbe("snapshot")
    @Test fun runtimeSnapshotPersistsThroughSnapshotStore() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "com.gernalix.personalhub.qa")
        val owner = PersonalHubDatabase.get(context)
        val original = SnapshotStore.load(context)
        val base = original ?: SnapshotStore.Snapshot(
            tasks = emptyList(), tags = emptyList(), closedSessions = emptyList(), tagSessions = emptyList(),
            installAtMs = System.currentTimeMillis(), appUsageMs = 0L,
            activeSessionStart = emptyMap(), activeTagStart = emptyList(), tagParents = emptyList(),
        )
        fun save(snapshot: SnapshotStore.Snapshot) = SnapshotStore.save(
            context, snapshot.tasks, snapshot.tags, snapshot.closedSessions, snapshot.tagSessions,
            lifePeriods = snapshot.lifePeriods, timeFenceRules = snapshot.timeFenceRules,
            installAtMs = snapshot.installAtMs, appUsageMs = snapshot.appUsageMs,
            activeSessionStart = snapshot.activeSessionStart, activeTagStart = snapshot.activeTagStart,
            tagParents = snapshot.tagParents, chains = snapshot.chains, activeChainRun = snapshot.activeChainRun,
            chronologySessions = snapshot.chronologySessions, runningSessions = snapshot.runningSessions,
            quickEventTemplates = snapshot.quickEventTemplates, quickEventEntries = snapshot.quickEventEntries,
            quickEventFieldDefinitions = snapshot.quickEventFieldDefinitions,
            quickEventFieldValues = snapshot.quickEventFieldValues,
            quickEventMacros = snapshot.quickEventMacros, quickEventMacroActions = snapshot.quickEventMacroActions,
        )
        try {
            save(base.copy(appUsageMs = base.appUsageMs + 1))
            assertEquals(base.appUsageMs + 1, SnapshotStore.load(context)?.appUsageMs)
            save(base.copy(appUsageMs = base.appUsageMs + 2))
            assertEquals(base.appUsageMs + 2, SnapshotStore.load(context)?.appUsageMs)
            owner.openHelper.readableDatabase.query("SELECT json FROM snapshot WHERE id=1").use {
                assertEquals(true, it.moveToFirst())
                assertEquals(base.appUsageMs + 2, org.json.JSONObject(it.getString(0)).getLong("appUsageMs"))
            }
        } finally {
            if (original == null) {
                owner.openHelper.writableDatabase.execSQL("DELETE FROM snapshot WHERE id=1")
                context.getSharedPreferences("multitimetracker_snapshot", Context.MODE_PRIVATE).edit().remove("snapshot_json").commit()
                context.getExternalFilesDir(null)?.resolve("snapshot.json")?.delete()
            } else save(original)
        }
    }

    @TableProbe("integrity_stats")
    @Test fun integrityStatsRefreshPersistsComputedValue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "com.gernalix.personalhub.qa")
        val db = PersonalHubDatabase.get(context).openHelper.writableDatabase
        val previous = db.query("SELECT json, computed_at_ms FROM integrity_stats WHERE id=1").use {
            if (it.moveToFirst()) it.getString(0) to it.getLong(1) else null
        }
        try {
            val stats = IntegrityStatsSqlite.refreshInternal(context)
            db.query("SELECT json, computed_at_ms FROM integrity_stats WHERE id=1").use {
                assertEquals(true, it.moveToFirst())
                assertEquals(stats.computedAtMs, it.getLong(1))
                assertEquals(stats.sessions, org.json.JSONObject(it.getString(0)).getLong("sessions"))
            }
        } finally {
            if (previous == null) db.execSQL("DELETE FROM integrity_stats WHERE id=1")
            else db.execSQL("UPDATE integrity_stats SET json=?, computed_at_ms=? WHERE id=1", arrayOf(previous.first, previous.second))
        }
    }

    @TableProbe("sessions", "session_tags")
    @Test fun timerSessionPersistsAndEditsThroughCore() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "com.gernalix.personalhub.qa")
        val owner = PersonalHubDatabase.get(context)
        val core = DefaultSessionCore(context)
        val marker = "QA914263Session${UUID.randomUUID()}"
        val start = System.currentTimeMillis() - 60_000
        val tagId = System.currentTimeMillis()
        val id = core.insertSession(marker, start, start + 30_000, setOf(tagId), null)
        try {
            assertEquals(marker, core.readSessionById(id)?.title)
            assertEquals(setOf(tagId), core.readSessionById(id)?.tagIds)
            owner.openHelper.readableDatabase.query("SELECT count(*) FROM session_tags WHERE session_id=? AND tag_id=?", arrayOf(id, tagId)).use {
                it.moveToFirst(); assertEquals(1L, it.getLong(0))
            }
            core.updateSessionMeta(id, "${marker}Edited", setOf(tagId))
            assertEquals("${marker}Edited", core.readSessionById(id)?.title)
            owner.openHelper.readableDatabase.query("SELECT title FROM sessions WHERE id=?", arrayOf(id)).use {
                assertEquals(true, it.moveToFirst()); assertEquals("${marker}Edited", it.getString(0))
            }
            core.softDeleteSession(id)
            assertEquals(null, core.readSessionById(id))
        } finally {
            owner.openHelper.writableDatabase.execSQL("DELETE FROM sessions WHERE id=?", arrayOf(id))
        }
    }

    @TableProbe("quick_event_templates", "quick_event_template_fields", "quick_event_entry_field_values", "quick_event_macros", "quick_event_macro_actions", "quick_event_template_tags", "quick_event_entry_tags", "quick_event_macro_tags")
    @Test fun templateFieldsEntryValuesAndMacroActionsPersist() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "com.gernalix.personalhub.qa")
        val owner = PersonalHubDatabase.get(context)
        val repo = QuickEventRepository(context)
        val marker = "QA914263Quick${UUID.randomUUID()}"
        val tagId = System.currentTimeMillis()
        var templateId = 0L
        var entryId = 0L
        var macroId = 0L
        fun count(table: String, key: String, id: Long) = owner.openHelper.readableDatabase.query(
            "SELECT count(*) FROM $table WHERE $key=?", arrayOf(id),
        ).use { it.moveToFirst(); it.getLong(0) }
        try {
            templateId = repo.insertTemplate(marker, setOf(tagId), 0, false, listOf(
                QuickEventFieldDefinition(0, 0, "${marker}Field"),
            ))
            assertEquals(marker, repo.readTemplateById(templateId)?.title)
            assertEquals(setOf(tagId), repo.readTemplateById(templateId)?.tagIds)
            assertEquals(1L, count("quick_event_template_tags", "template_id", templateId))
            val field = repo.readFieldDefinitionsForTemplate(templateId).single()
            assertEquals("${marker}Field", field.label)
            entryId = repo.insertEntry(templateId, null, marker, System.currentTimeMillis(), setOf(tagId), listOf(
                QuickEventFieldValue(0, 0, field.id, field.label, value = "one"),
            ))
            assertEquals(1L, count("quick_event_entry_field_values", "entry_id", entryId))
            assertEquals(1L, count("quick_event_entry_tags", "entry_id", entryId))
            repo.updateEntry(entryId, "${marker}Edited", System.currentTimeMillis(), setOf(tagId), listOf(
                QuickEventFieldValue(0, entryId, field.id, field.label, value = "two"),
            ))
            assertEquals("two", repo.readFieldValuesForEntries(setOf(entryId)).single().value)
            repo.updateTemplate(templateId, "${marker}Edited", setOf(tagId), 1, false, listOf(
                field.copy(label = "${marker}Updated"),
            ))
            assertEquals("${marker}Edited", repo.readTemplateById(templateId)?.title)
            assertEquals("${marker}Updated", repo.readFieldDefinitionsForTemplate(templateId).single().label)

            macroId = repo.insertMacro(marker, setOf(tagId), 0, false, listOf(QuickEventMacroAction(0, templateId)))
            assertNotNull(repo.readMacroById(macroId))
            assertEquals(1L, count("quick_event_macro_actions", "macro_id", macroId))
            assertEquals(1L, count("quick_event_macro_tags", "macro_id", macroId))
            repo.updateMacro(macroId, "${marker}Edited", setOf(tagId), 1, false, listOf(QuickEventMacroAction(macroId, templateId)))
            assertEquals("${marker}Edited", repo.readMacroById(macroId)?.title)
        } finally {
            val db = owner.openHelper.writableDatabase
            if (macroId != 0L) db.execSQL("DELETE FROM quick_event_macros WHERE id=?", arrayOf(macroId))
            if (entryId != 0L) db.execSQL("DELETE FROM quick_event_entries WHERE id=?", arrayOf(entryId))
            if (templateId != 0L) db.execSQL("DELETE FROM quick_event_templates WHERE id=?", arrayOf(templateId))
            assertEquals(0L, count("quick_event_templates", "id", templateId))
            assertEquals(0L, count("quick_event_entries", "id", entryId))
            assertEquals(0L, count("quick_event_macros", "id", macroId))
        }
    }
}
