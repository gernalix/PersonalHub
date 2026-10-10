package com.gernalix.personalhub

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.multitimetracker.api.LegacySinceWhenMigrationApi
import com.gernalix.personalhub.hub.SinceWhenCounterHubAdapter
import com.gernalix.personalhub.contracts.database.*
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.hubcontext.HubContextRuntime
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Canonical UI with only a Since When adapter and a deliberately failing, pending Timer import. */
@RunWith(AndroidJUnit4::class)
class SinceWhenAutonomyDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun pendingFailedLegacyImportDoesNotBlockCanonicalCreateEditOrHubAccess() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "com.gernalix.personalhub.qa")
        val db = PersonalHubDatabase.get(context)
        val sql = db.openHelper.writableDatabase
        val key = "timer.life_periods.to_since_when.v1"
        val previous = sql.query("SELECT json,saved_at_ms FROM snapshot WHERE id=1").use { if (it.moveToFirst()) it.getString(0) to it.getLong(1) else null }
        val marker = sql.query("SELECT completedAt FROM since_when_migration_state WHERE `key`=?", arrayOf(key)).use { if(it.moveToFirst()) it.getLong(0) else null }
        val title = "QA925611 autonomous ${System.currentTimeMillis()}"
        var scenario: ActivityScenario<SinceWhenActivity>? = null
        var sourceScenario: ActivityScenario<HubDeepLinkActivity>? = null
        try {
            sql.execSQL("DELETE FROM since_when_migration_state WHERE `key`=?", arrayOf(key))
            sql.execSQL("INSERT OR REPLACE INTO snapshot(id,json,saved_at_ms) VALUES(1,?,1)", arrayOf("malformed"))
            // This is the complete runtime fixture: no Timer adapter or Timer startup initialization.
            HubContextRuntime.initialize(context, listOf(SinceWhenCounterHubAdapter(context)))
            try { LegacySinceWhenMigrationApi.ensureMigrated(context); fail("Expected legacy failure") } catch (_: IllegalStateException) { }
            assertFalse(db.sinceWhenCounterDao().migrationComplete(key))
            scenario = ActivityScenario.launch(Intent(context, SinceWhenActivity::class.java))
            compose.onNodeWithTag("sincewhen-create").performClick()
            compose.onNodeWithTag("sincewhen-title").performTextReplacement(title)
            compose.onNodeWithTag("sincewhen-save").performClick()
            compose.waitUntil(10000) { runBlocking { db.sinceWhenCounterDao().all().any { it.title == title } } && compose.onAllNodesWithTag("sincewhen-save").fetchSemanticsNodes().isEmpty() }
            val counter = db.sinceWhenCounterDao().all().single { it.title == title }
            scenario.recreate()
            compose.onNodeWithTag("sincewhen-edit-${counter.id}").performClick()
            compose.onNodeWithTag("sincewhen-title").performTextReplacement("$title edited")
            compose.onNodeWithTag("sincewhen-save").performClick()
            compose.waitUntil(10000) { runBlocking { db.sinceWhenCounterDao().get(counter.id)?.title == "$title edited" } && compose.onAllNodesWithTag("sincewhen-save").fetchSemanticsNodes().isEmpty() }
            val adapter = SinceWhenCounterHubAdapter(context)
            assertTrue(adapter.exists(counter.id.toString()))
            assertNotNull(adapter.openTarget(counter.id.toString()))
            assertEquals("$title edited", adapter.summaries(setOf(counter.id.toString()))[counter.id.toString()]?.label)
            assertTrue(adapter.search(title, 10).isNotEmpty())
            scenario.close()
            scenario = null
            val source = SinceWhenSourceDescriptor("places/place", "qa925611-$title", "$title source", listOf(SinceWhenTimestampSource("createdAt", "QA start", 1790000012345L, true)))
            sourceScenario = ActivityScenario.launch(Intent(context, HubDeepLinkActivity::class.java).setData(HubDeepLinkContract.sinceWhenCreateUri(source, startEnabled = true)))
            compose.waitUntil(10000) { runBlocking { db.sinceWhenCounterDao().all().any { it.title == source.defaultCounterTitle } } }
            val sourceRow = db.sinceWhenCounterDao().all().single { it.title == source.defaultCounterTitle }
            assertEquals(1790000012345L, sourceRow.initialTimestamp)
            assertEquals(source.entityType, sourceRow.sourceEntityType)
            assertEquals(source.entityId, sourceRow.sourceEntityId)
            assertEquals("createdAt", sourceRow.sourceTimestampField)
            assertFalse(db.sinceWhenCounterDao().migrationComplete(key))
        } finally {
            scenario?.close()
            sourceScenario?.close()
            if (previous == null) sql.execSQL("DELETE FROM snapshot WHERE id=1")
            else sql.execSQL("INSERT OR REPLACE INTO snapshot(id,json,saved_at_ms) VALUES(1,?,?)", arrayOf(previous.first, previous.second))
            sql.execSQL("DELETE FROM since_when_migration_state WHERE `key`=?", arrayOf(key))
            if(marker != null) sql.execSQL("INSERT INTO since_when_migration_state(`key`,completedAt) VALUES(?,?)", arrayOf(key, marker))
        }
    }
}
